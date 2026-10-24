package com.taskplanner.data.local

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "legacy database" the device test plants must be the schema version 7 really had.
 *
 * `DatabaseLocationInstrumentedTest` rescues a database out of the cache and hands it to the app's own
 * migration chain, which is the only way to test the path a real user's file takes on upgrade. That
 * test used to build its fixture by hand — one two-column `tasks` table stamped `user_version = 7` —
 * and the chain correctly refused it: migration 9→10 creates `index_tasks_user_id_due_date`, and the
 * invented table has no `user_id`, because no released app ever had that schema. A fixture that describes
 * a database nobody owned cannot prove an upgrade works.
 *
 * The DDL therefore lives in `app/src/androidTest/assets/legacy-v7.sql` and is generated verbatim from
 * Room's exported schema. This test is what stops it going stale: it compares every `CREATE` statement in
 * the asset with the one in `schemas/com.taskplanner.data.local.AppDatabase/7.json`, which the build
 * regenerates from the entities whenever they change. Add a column, forget to refresh the asset, and this
 * fails with the two statements side by side.
 */
class LegacySchemaV7SourceTest {

    private val schemaJson: File by lazy {
        candidate("schemas/$DATABASE/7.json")
    }
    private val assetSql: File by lazy {
        candidate("src/androidTest/assets/legacy-v7.sql")
    }

    @Test
    fun `the planted schema is exactly the one version seven exported`() {
        val statementsFromSchema = exportedCreateStatements()
        val statementsInAsset = assetCreateStatements()

        assertTrue(
            "the exported v7 schema names tables, the asset names none",
            statementsFromSchema.isNotEmpty()
        )
        assertEquals(
            "every CREATE the v7 schema declares is present in the planted fixture, in the same order " +
                "and with the same text",
            statementsFromSchema,
            statementsInAsset
        )
    }

    @Test
    fun `the fixture declares the tables the migration chain needs and the version the rescue expects`() {
        val text = read(assetSql)
        // Migrations 7→16 touch every one of these; a fixture missing a table fails inside a migration
        // rather than in the assertion, which is the confusing failure this test exists to prevent.
        listOf("tasks", "categories", "users", "task_groups").forEach { table ->
            assertTrue(
                "the planted database creates `$table`",
                text.contains("CREATE TABLE IF NOT EXISTS `$table`")
            )
        }
        assertTrue(
            "the planted database announces itself as version 7, which is what makes the chain start " +
                "there rather than recreate the file",
            text.contains("PRAGMA user_version = 7;")
        )
        // The sketch that used to stand in for a legacy database: if it ever comes back, the migration
        // failure it produced comes back with it.
        assertTrue(
            "the fixture must not be a hand-written sketch of a tasks table",
            !Regex("CREATE TABLE\\s+tasks\\s*\\(\\s*id\\s+INTEGER PRIMARY KEY,\\s*title\\s+TEXT\\s*\\)")
                .containsMatchIn(text)
        )
    }

    /** Every `CREATE …;` line the exported schema declares, with Room's own placeholders resolved. */
    private fun exportedCreateStatements(): List<String> {
        val json = read(schemaJson)
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)
        assertEquals("the schema file read is the v7 one this fixture stands for", "7", version)

        val out = mutableListOf<String>()
        for (entity in json.split("\"tableName\"").drop(1)) {
            val table = Regex("^\\s*:\\s*\"(\\w+)\"").find(entity)?.groupValues?.get(1)
                ?: error("no table name next to a tableName field")
            val creates = Regex("\"createSql\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                .findAll(entity)
                .map { it.groupValues[1].unescapeJson().resolveTable(table) }
            out += creates.toList()
        }
        return out.map { it.trimEnd().trimEnd(';') + ";" }
    }

    private fun assetCreateStatements(): List<String> =
        read(assetSql).lines()
            .filterNot { it.isBlank() || it.trimStart().startsWith("--") }
            .filter { it.trimStart().startsWith("CREATE ") }
            .map { it.trim().removeSuffix(";") + ";" }

    private fun String.resolveTable(table: String) = replace("\${TABLE_NAME}", table)

    private fun String.unescapeJson() =
        replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\t", "\t")

    /** A file, resolved from wherever the test happens to run, with the tried paths in the message. */
    private fun candidate(relative: String): File {
        val tried = listOf(File(relative), File("app/$relative"), File("../$relative"))
        return tried.firstOrNull { it.isFile }
            ?: error("no $relative on disk; tried ${tried.map { it.absolutePath }}")
    }

    private fun read(file: File): String = file.readText()

    private companion object {
        const val DATABASE = "com.taskplanner.data.local.AppDatabase"
    }
}
