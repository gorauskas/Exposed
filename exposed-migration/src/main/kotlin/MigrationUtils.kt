import org.jetbrains.exposed.sql.ExperimentalDatabaseMigrationApi
import org.jetbrains.exposed.sql.Index
import org.jetbrains.exposed.sql.SchemaUtils.addMissingColumnsStatements
import org.jetbrains.exposed.sql.SchemaUtils.checkExcessiveForeignKeyConstraints
import org.jetbrains.exposed.sql.SchemaUtils.checkExcessiveIndices
import org.jetbrains.exposed.sql.SchemaUtils.checkMappingConsistence
import org.jetbrains.exposed.sql.SchemaUtils.createStatements
import org.jetbrains.exposed.sql.SchemaUtils.statementsRequiredToActualizeScheme
import org.jetbrains.exposed.sql.Sequence
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.exposedLogger
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.SQLiteDialect
import org.jetbrains.exposed.sql.vendors.currentDialect
import java.io.File

object MigrationUtils {
    /**
     * This function simply generates the migration script without applying the migration. Its purpose is to show what
     * the migration script will look like before applying the migration. If a migration script with the same name
     * already exists, its content will be overwritten.
     *
     * @param tables The tables whose changes will be used to generate the migration script.
     * @param scriptName The name to be used for the generated migration script.
     * @param scriptDirectory The directory (path from repository root) in which to create the migration script.
     * @param withLogs By default, a description for each intermediate step, as well as its execution time, is logged at
     * the INFO level. This can be disabled by setting [withLogs] to `false`.
     *
     * @return The generated migration script.
     *
     * @throws IllegalArgumentException if no argument is passed for the [tables] parameter.
     */
    @ExperimentalDatabaseMigrationApi
    fun generateMigrationScript(vararg tables: Table, scriptDirectory: String, scriptName: String, withLogs: Boolean = true): File {
        require(tables.isNotEmpty()) { "Tables argument must not be empty" }

        val allStatements = statementsRequiredForDatabaseMigration(*tables, withLogs = withLogs)

        val migrationScript = File("$scriptDirectory/$scriptName.sql")
        migrationScript.createNewFile()

        // Clear existing content
        migrationScript.writeText("")

        // Append statements
        allStatements.forEach { statement ->
            // Add semicolon only if it's not already there
            val conditionalSemicolon = if (statement.last() == ';') "" else ";"

            migrationScript.appendText("$statement$conditionalSemicolon\n")
        }

        return migrationScript
    }

    /**
     * Returns the SQL statements that need to be executed to make the existing database schema compatible with
     * the table objects defined using Exposed. Unlike [statementsRequiredToActualizeScheme], DROP/DELETE statements are
     * included.
     *
     * **Note:** Some dialects, like SQLite, do not support `ALTER TABLE ADD COLUMN` syntax completely,
     * which restricts the behavior when adding some missing columns. Please check the documentation.
     *
     * By default, a description for each intermediate step, as well as its execution time, is logged at the INFO level.
     * This can be disabled by setting [withLogs] to `false`.
     */
    fun statementsRequiredForDatabaseMigration(vararg tables: Table, withLogs: Boolean = true): List<String> {
        val (tablesToCreate, tablesToAlter) = tables.partition { !it.exists() }
        val createStatements = logTimeSpent("Preparing create tables statements", withLogs) {
            createStatements(tables = tablesToCreate.toTypedArray())
        }
        val createSequencesStatements = logTimeSpent("Preparing create sequences statements", withLogs) {
            checkMissingSequences(tables = tables, withLogs).flatMap { it.createStatement() }
        }
        val alterStatements = logTimeSpent("Preparing alter table statements", withLogs) {
            addMissingColumnsStatements(tables = tablesToAlter.toTypedArray(), withLogs)
        }

        val modifyTablesStatements = logTimeSpent("Checking mapping consistence", withLogs) {
            mappingConsistenceRequiredStatements(
                tables = tables,
                withLogs
            ).filter { it !in (createStatements + alterStatements) }
        }

        val allStatements = createStatements + createSequencesStatements + alterStatements + modifyTablesStatements
        return allStatements
    }

    /**
     * Log Exposed table mappings <-> real database mapping problems and returns DDL Statements to fix them, including
     * DROP/DELETE statements (unlike [checkMappingConsistence])
     */
    private fun mappingConsistenceRequiredStatements(vararg tables: Table, withLogs: Boolean = true): List<String> {
        return checkMissingIndices(tables = tables, withLogs).flatMap { it.createStatement() } +
            checkUnmappedIndices(tables = tables, withLogs).flatMap { it.dropStatement() } +
            checkExcessiveForeignKeyConstraints(tables = tables, withLogs).flatMap { it.dropStatement() } +
            checkExcessiveIndices(tables = tables, withLogs).flatMap { it.dropStatement() } +
            checkUnmappedSequences(tables = tables, withLogs).flatMap { it.dropStatement() }
    }

    /**
     * Checks all [tables] for any that have indices that are missing in the database but are defined in the code. If
     * found, this function also logs the SQL statements that can be used to create these indices.
     *
     * @return List of indices that are missing and can be created.
     */
    private fun checkMissingIndices(vararg tables: Table, withLogs: Boolean): List<Index> {
        fun Collection<Index>.log(mainMessage: String) {
            if (withLogs && isNotEmpty()) {
                exposedLogger.warn(joinToString(prefix = "$mainMessage\n\t\t", separator = "\n\t\t"))
            }
        }

        val fKeyConstraints = currentDialect.columnConstraints(*tables).keys
        val existingIndices = currentDialect.existingIndices(*tables)

        fun List<Index>.filterForeignKeys() = if (currentDialect is MysqlDialect) {
            filterNot { it.table to LinkedHashSet(it.columns) in fKeyConstraints }
        } else {
            this
        }

        // SQLite: indices whose names start with "sqlite_" are meant for internal use
        fun List<Index>.filterInternalIndices() = if (currentDialect is SQLiteDialect) {
            filter { !it.indexName.startsWith("sqlite_") }
        } else {
            this
        }

        fun Table.existingIndices() = existingIndices[this].orEmpty().filterForeignKeys().filterInternalIndices()

        fun Table.mappedIndices() = this.indices.filterForeignKeys().filterInternalIndices()

        val missingIndices = HashSet<Index>()
        val nameDiffers = HashSet<Index>()

        tables.forEach { table ->
            val existingTableIndices = table.existingIndices()
            val mappedIndices = table.mappedIndices()

            for (index in existingTableIndices) {
                val mappedIndex = mappedIndices.firstOrNull { it.onlyNameDiffer(index) } ?: continue
                if (withLogs) {
                    exposedLogger.info(
                        "Index on table '${table.tableName}' differs only in name: in db ${index.indexName} -> in mapping ${mappedIndex.indexName}"
                    )
                }
                nameDiffers.add(index)
                nameDiffers.add(mappedIndex)
            }

            missingIndices.addAll(mappedIndices.subtract(existingTableIndices))
        }

        val toCreate = missingIndices.subtract(nameDiffers)
        toCreate.log("Indices missed from database (will be created):")
        return toCreate.toList()
    }

    /**
     * Checks all [tables] for any that have indices that exist in the database but are not mapped in the code. If
     * found, this function also logs the SQL statements that can be used to drop these indices.
     *
     * @return List of indices that are unmapped and can be dropped.
     */
    private fun checkUnmappedIndices(vararg tables: Table, withLogs: Boolean): List<Index> {
        fun Collection<Index>.log(mainMessage: String) {
            if (withLogs && isNotEmpty()) {
                exposedLogger.warn(joinToString(prefix = "$mainMessage\n\t\t", separator = "\n\t\t"))
            }
        }

        val foreignKeyConstraints = currentDialect.columnConstraints(*tables).keys
        val existingIndices = currentDialect.existingIndices(*tables)

        fun List<Index>.filterForeignKeys() = if (currentDialect is MysqlDialect) {
            filterNot { it.table to LinkedHashSet(it.columns) in foreignKeyConstraints }
        } else {
            this
        }

        // SQLite: indices whose names start with "sqlite_" are meant for internal use
        fun List<Index>.filterInternalIndices() = if (currentDialect is SQLiteDialect) {
            filter { !it.indexName.startsWith("sqlite_") }
        } else {
            this
        }

        fun Table.existingIndices() = existingIndices[this].orEmpty().filterForeignKeys().filterInternalIndices()

        fun Table.mappedIndices() = this.indices.filterForeignKeys().filterInternalIndices()

        val unmappedIndices = HashMap<String, MutableSet<Index>>()
        val nameDiffers = HashSet<Index>()

        tables.forEach { table ->
            val existingTableIndices = table.existingIndices()
            val mappedIndices = table.mappedIndices()

            for (index in existingTableIndices) {
                val mappedIndex = mappedIndices.firstOrNull { it.onlyNameDiffer(index) } ?: continue
                nameDiffers.add(index)
                nameDiffers.add(mappedIndex)
            }

            unmappedIndices.getOrPut(table.nameInDatabaseCase()) {
                hashSetOf()
            }.addAll(existingTableIndices.subtract(mappedIndices))
        }

        val toDrop = mutableSetOf<Index>()
        unmappedIndices.forEach { (name, indices) ->
            toDrop.addAll(
                indices.subtract(nameDiffers).also {
                    it.log("Indices exist in database and not mapped in code on class '$name':")
                }
            )
        }
        return toDrop.toList()
    }

    /**
     * Checks all [tables] for any that have sequences that are missing in the database but are defined in the code. If
     * found, this function also logs the SQL statements that can be used to create these sequences.
     *
     * @return List of sequences that are missing and can be created.
     */
    private fun checkMissingSequences(vararg tables: Table, withLogs: Boolean): List<Sequence> {
        if (!currentDialect.supportsCreateSequence) {
            return emptyList()
        }

        fun Collection<Sequence>.log(mainMessage: String) {
            if (withLogs && isNotEmpty()) {
                exposedLogger.warn(joinToString(prefix = "$mainMessage\n\t\t", separator = "\n\t\t"))
            }
        }

        val existingSequencesNames: Set<String> = currentDialect.sequences().toSet()

        val missingSequences = mutableSetOf<Sequence>()

        val mappedSequences: Set<Sequence> = tables.flatMap { table -> table.sequences }.toSet()

        missingSequences.addAll(mappedSequences.filterNot { it.identifier.inProperCase() in existingSequencesNames })

        missingSequences.log("Sequences missed from database (will be created):")
        return missingSequences.toList()
    }

    /**
     * Checks all [tables] for any that have sequences that exist in the database but are not mapped in the code. If
     * found, this function also logs the SQL statements that can be used to drop these sequences.
     *
     * @return List of sequences that are unmapped and can be dropped.
     */
    private fun checkUnmappedSequences(vararg tables: Table, withLogs: Boolean): List<Sequence> {
        if (!currentDialect.supportsCreateSequence || (currentDialect as? H2Dialect)?.majorVersion == H2Dialect.H2MajorVersion.One) {
            return emptyList()
        }

        fun Collection<Sequence>.log(mainMessage: String) {
            if (withLogs && isNotEmpty()) {
                exposedLogger.warn(joinToString(prefix = "$mainMessage\n\t\t", separator = "\n\t\t"))
            }
        }

        val existingSequencesNames: Set<String> = currentDialect.sequences().toSet()

        val unmappedSequences = mutableSetOf<Sequence>()

        val mappedSequencesNames: Set<String> = tables.flatMap { table -> table.sequences.map { it.identifier.inProperCase() } }.toSet()

        unmappedSequences.addAll(existingSequencesNames.subtract(mappedSequencesNames).map { Sequence(it) })

        unmappedSequences.log("Sequences exist in database and not mapped in code:")

        return unmappedSequences.toList()
    }

    private inline fun <R> logTimeSpent(message: String, withLogs: Boolean, block: () -> R): R {
        return if (withLogs) {
            val start = System.currentTimeMillis()
            val answer = block()
            exposedLogger.info(message + " took " + (System.currentTimeMillis() - start) + "ms")
            answer
        } else {
            block()
        }
    }
}

internal fun String.inProperCase(): String =
    TransactionManager.currentOrNull()?.db?.identifierManager?.inProperCase(this@inProperCase) ?: this