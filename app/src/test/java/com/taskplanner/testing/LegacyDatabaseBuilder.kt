package com.taskplanner.testing

/**
 * Builds a legacy database (schema + a representative row of every entity), runs the production
 * migrations over it, and compares the result with the schema Room expects for the next version.
 */
class LegacyDatabaseBuilder {

    fun build(version: Int): SqliteTestDatabase =
        SqliteTestDatabase.open("legacy-v$version").also {
            TestSchemas.createVersion(it, version)
            Seeder.seed(it, version)
        }

    /** Fresh-install database of [version]: the structure every migration must produce. */
    fun reference(version: Int): SqliteTestDatabase =
        SqliteTestDatabase.open("reference-v$version").also { TestSchemas.createVersion(it, version) }

    /**
     * Compares each table declared by schema [version] with what [db] actually holds: column
     * affinity, NOT NULL, defaults, primary keys, indices and foreign keys. Tables left over from
     * older versions are tolerated (Room ignores them, migrations never rely on them); missing or
     * altered tables are reported.
     */
    fun assertMatchesSchema(db: SqliteTestDatabase, version: Int, context: String) {
        val expected = reference(version)
        val diffs = try {
            expected.tableNames()
                .filter { it != "room_master_table" }
                .flatMap { table -> describeDifferences(expected, db, table) }
        } finally {
            expected.close()
        }
        if (diffs.isNotEmpty()) {
            throw AssertionError(
                "$context: does not match exported schema v$version " +
                    "(${diffs.size} problem(s)):\n" + diffs.joinToString("\n")
            )
        }
    }

    private fun describeDifferences(
        expected: SqliteTestDatabase,
        actual: SqliteTestDatabase,
        table: String
    ): List<String> {
        val found = actual.columnInfo(table)
        if (found.isEmpty()) return listOf("`$table` does not exist")

        val problems = mutableListOf<String>()
        val expectedColumns = expected.columnInfo(table)
        (expectedColumns.keys - found.keys).forEach { problems += "`$table.$it` is missing" }
        (found.keys - expectedColumns.keys).forEach { problems += "`$table.$it` is not in the schema" }
        expectedColumns.forEach { (column, meta) ->
            val actualMeta = found[column] ?: return@forEach
            if (actualMeta.affinity != meta.affinity) {
                problems += "`$table.$column` affinity: expected ${meta.affinity}, found ${actualMeta.affinity}"
            }
            if (actualMeta.notNull != meta.notNull) {
                problems += "`$table.$column` NOT NULL: expected ${meta.notNull}, found ${actualMeta.notNull}"
            }
            if (actualMeta.defaultValue != meta.defaultValue) {
                // Compared literally: a default the schema does not declare is a schema mismatch,
                // even when the stored value would look the same.
                problems += "`$table.$column` default: expected ${meta.defaultValue}, found ${actualMeta.defaultValue}"
            }
        }
        if (expected.primaryKeys(table) != actual.primaryKeys(table)) {
            problems += "`$table` primary key: expected ${expected.primaryKeys(table)}, found ${actual.primaryKeys(table)}"
        }
        (expected.indexInfo(table) - actual.indexInfo(table))
            .forEach { problems += "`$table` index missing: $it" }
        (actual.indexInfo(table) - expected.indexInfo(table))
            .forEach { problems += "`$table` index mismatch: $it" }
        if (expected.foreignKeyInfo(table) != actual.foreignKeyInfo(table)) {
            problems += "`$table` foreign keys: expected ${expected.foreignKeyInfo(table)}, " +
                "found ${actual.foreignKeyInfo(table)}"
        }
        return problems
    }

    object Seeder {
        const val USER_ID = 1L
        const val CATEGORY_ID = 7L
        const val COMPLETED_TASK_ID = 11L
        const val TODO_TASK_ID = 12L

        fun seed(db: SqliteTestDatabase, version: Int) {
            insertUser(db, version)
            insertCategory(db, version)
            insertTask(db, version, COMPLETED_TASK_ID, "Pay rent", "COMPLETED", 1_600_000_000_000L)
            insertTask(db, version, TODO_TASK_ID, "Buy milk", "TODO", 1_700_000_000_000L)
            if (version == 1) db.exec("INSERT INTO preferences (key, value) VALUES ('dark_mode', 1)")
        }

        private fun insertUser(db: SqliteTestDatabase, version: Int) = when {
            version == 1 -> db.exec(
                "INSERT INTO users (id, name, email, createdAt, updatedAt) VALUES " +
                    "($USER_ID, 'ivan petrov', 'ivan@example.com', 1000, 1000)"
            )

            version == 2 -> db.exec(
                "INSERT INTO users (id, name, email, createdAt, updatedAt, isCurrent) VALUES " +
                    "($USER_ID, 'ivan petrov', 'ivan@example.com', 1000, 1000, 1)"
            )

            version <= 4 -> db.exec(
                "INSERT INTO users (id, email, name, is_current, created_at, updated_at) VALUES " +
                    "($USER_ID, 'ivan@example.com', 'ivan petrov', 1, 1000, 1000)"
            )

            else -> db.exec(
                "INSERT INTO users (id, email, name, avatar_color, avatar_initial, is_current, " +
                    "last_used, created_at, updated_at) VALUES " +
                    "($USER_ID, 'ivan@example.com', 'ivan petrov', -16777000, 'I', 1, 2000, 1000, 1000)"
            )
        }

        private fun insertCategory(db: SqliteTestDatabase, version: Int) = if (version <= 2) {
            db.exec(
                "INSERT INTO categories (id, name, color, icon, userId, parentCategoryId, isShared, " +
                    "createdAt, updatedAt) VALUES " +
                    "($CATEGORY_ID, 'Home', -16711936, 'ic_home', $USER_ID, NULL, 0, 1000, 1000)"
            )
        } else {
            db.exec(
                "INSERT INTO categories (id, name, color, icon, user_id, parent_category_id, is_shared, " +
                    "created_at, updated_at) VALUES " +
                    "($CATEGORY_ID, 'Home', -16711936, 'ic_home', $USER_ID, NULL, 0, 1000, 1000)"
            )
        }

        private fun insertTask(
            db: SqliteTestDatabase,
            version: Int,
            id: Long,
            title: String,
            status: String,
            dueDate: Long
        ) = when {
            version <= 2 -> db.exec(
                "INSERT INTO tasks (id, title, description, dueDate, priority, status, categoryId, " +
                    "userId, createdAt, updatedAt, isRecurring, recurrencePattern, estimatedTime, " +
                    "actualTime, tags) VALUES " +
                    "($id, '$title', 'note for $title', $dueDate, 'HIGH', '$status', $CATEGORY_ID, " +
                    "$USER_ID, 900, $dueDate, 0, NULL, 30, NULL, '[\"tag\"]')"
            )

            version == 3 -> db.exec(
                "INSERT INTO tasks (id, title, description, due_date, priority, status, category_id, " +
                    "user_id, created_at, updated_at, is_recurring, recurrence_pattern, estimated_time, " +
                    "actual_time, tags) VALUES " +
                    "($id, '$title', 'note for $title', $dueDate, 'HIGH', '$status', $CATEGORY_ID, " +
                    "$USER_ID, 900, $dueDate, 0, NULL, 30, NULL, '[\"tag\"]')"
            )

            else -> db.exec(
                "INSERT INTO tasks (id, title, description, due_date, priority, status, category_id, " +
                    "user_id, `order`, created_at, updated_at) VALUES " +
                    "($id, '$title', 'note for $title', $dueDate, 'HIGH', '$status', $CATEGORY_ID, " +
                    "$USER_ID, 0, 900, $dueDate)"
            )
        }
    }
}
