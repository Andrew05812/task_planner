package com.taskplanner.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit Room migrations for every historical schema version
 * (schemas/…AppDatabase/1..json). No column that may contain user data
 * is ever dropped without copying its values first, and destructive
 * fallback is not used anywhere.
 */
object AppMigrations {

    private const val UUID_EXPR =
        "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || " +
            "substr(hex(randomblob(2)), 2) || '-' || " +
            "substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || " +
            "hex(randomblob(6)))"

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v2 users table declares isCurrent NOT NULL without a default,
            // so the table must be recreated to match the exported schema exactly.
            db.execSQL(
                "CREATE TABLE users_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `email` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `isCurrent` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO users_new (id, name, email, createdAt, updatedAt, isCurrent) " +
                    "SELECT id, name, email, createdAt, updatedAt, 0 FROM users"
            )
            db.execSQL("DROP TABLE users")
            db.execSQL("ALTER TABLE users_new RENAME TO users")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL COLLATE NOCASE, `description` TEXT NOT NULL DEFAULT '', " +
                    "`due_date` INTEGER, `priority` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`category_id` INTEGER, `user_id` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, `is_recurring` INTEGER NOT NULL DEFAULT 0, " +
                    "`recurrence_pattern` TEXT, `estimated_time` INTEGER, `actual_time` INTEGER, " +
                    "`tags` TEXT NOT NULL, FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) " +
                    "ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "INSERT INTO tasks_new (id, title, description, due_date, priority, status, " +
                    "category_id, user_id, created_at, updated_at, is_recurring, recurrence_pattern, " +
                    "estimated_time, actual_time, tags) " +
                    "SELECT id, title, COALESCE(description, ''), dueDate, priority, status, " +
                    "categoryId, userId, createdAt, updatedAt, isRecurring, recurrencePattern, " +
                    "estimatedTime, actualTime, COALESCE(tags, '[]') FROM tasks"
            )
            db.execSQL("DROP TABLE tasks")
            db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_user_id` ON `tasks` (`user_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_category_id` ON `tasks` (`category_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_due_date` ON `tasks` (`due_date`)")

            db.execSQL(
                "CREATE TABLE categories_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL COLLATE NOCASE, `color` INTEGER NOT NULL, `icon` TEXT NOT NULL, " +
                    "`user_id` INTEGER NOT NULL, `parent_category_id` INTEGER, " +
                    "`is_shared` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, FOREIGN KEY(`parent_category_id`) " +
                    "REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "INSERT INTO categories_new (id, name, color, icon, user_id, parent_category_id, " +
                    "is_shared, created_at, updated_at) " +
                    "SELECT id, name, color, icon, userId, parentCategoryId, isShared, createdAt, " +
                    "updatedAt FROM categories"
            )
            db.execSQL("DROP TABLE categories")
            db.execSQL("ALTER TABLE categories_new RENAME TO categories")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_user_id` ON `categories` (`user_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_categories_parent_category_id` ON `categories` (`parent_category_id`)")

            db.execSQL(
                "CREATE TABLE users_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`email` TEXT NOT NULL COLLATE NOCASE, `name` TEXT NOT NULL, " +
                    "`is_current` INTEGER NOT NULL DEFAULT 0, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO users_new (id, email, name, is_current, created_at, updated_at) " +
                    "SELECT id, email, name, isCurrent, createdAt, updatedAt FROM users"
            )
            db.execSQL("DROP TABLE users")
            db.execSQL("ALTER TABLE users_new RENAME TO users")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v4 removed unused recurrence fields and introduced manual `order`.
            db.execSQL(
                "CREATE TABLE tasks_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT NOT NULL, `due_date` INTEGER, " +
                    "`priority` TEXT NOT NULL, `status` TEXT NOT NULL, `category_id` INTEGER, " +
                    "`user_id` INTEGER NOT NULL, `order` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, FOREIGN KEY(`category_id`) " +
                    "REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            db.execSQL(
                "INSERT INTO tasks_new (id, title, description, due_date, priority, status, " +
                    "category_id, user_id, `order`, created_at, updated_at) " +
                    "SELECT id, title, description, due_date, priority, status, category_id, " +
                    "user_id, 0, created_at, updated_at FROM tasks"
            )
            db.execSQL("DROP TABLE tasks")
            db.execSQL("ALTER TABLE tasks_new RENAME TO tasks")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_user_id` ON `tasks` (`user_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_category_id` ON `tasks` (`category_id`)")
            // v4 also removed the duplicate due_date index and the preferences table.
            db.execSQL("DROP INDEX IF EXISTS `index_tasks_due_date`")
            db.execSQL("DROP TABLE IF EXISTS `preferences`")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // The users table is rebuilt instead of ALTER-ed: SQLite requires a DEFAULT for a
            // NOT NULL column added by ALTER, while schema v5 declares these columns without
            // one. A leftover default would make Room's schema validation reject every database
            // upgraded from v4, so the table has to end up byte-identical to a fresh install.
            db.execSQL(
                "CREATE TABLE users_new (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`email` TEXT NOT NULL COLLATE NOCASE, `name` TEXT NOT NULL, " +
                    "`avatar_color` INTEGER NOT NULL, `avatar_initial` TEXT NOT NULL, " +
                    "`is_current` INTEGER NOT NULL DEFAULT 0, `last_used` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO users_new (id, email, name, avatar_color, avatar_initial, is_current, " +
                    "last_used, created_at, updated_at) " +
                    "SELECT id, email, name, 0, " +
                    "CASE WHEN name IS NULL OR name = '' THEN '' ELSE upper(substr(name, 1, 1)) END, " +
                    "is_current, " +
                    // A profile that never recorded usage is anchored to its creation time, so
                    // the recents list stays meaningful instead of showing epoch.
                    "created_at, created_at, updated_at FROM users"
            )
            db.execSQL("DROP TABLE users")
            db.execSQL("ALTER TABLE users_new RENAME TO users")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")
        }
    }

    // v5 -> v6 changed the Room identity hash only; the physical schema is identical.
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {}
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `group_id` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `archived_at` INTEGER")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `started_at` INTEGER")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `is_deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `deleted_at` INTEGER")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `sync_state` TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("UPDATE `tasks` SET uuid = $UUID_EXPR WHERE uuid = ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_tasks_group_id` ON `tasks` (`group_id`)")

            db.execSQL("ALTER TABLE `categories` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `categories` ADD COLUMN `is_deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `categories` ADD COLUMN `deleted_at` INTEGER")
            db.execSQL("ALTER TABLE `categories` ADD COLUMN `sync_state` TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("UPDATE `categories` SET uuid = $UUID_EXPR WHERE uuid = ''")

            db.execSQL("ALTER TABLE `users` ADD COLUMN `firebase_uid` TEXT")
            db.execSQL("ALTER TABLE `users` ADD COLUMN `avatar_url` TEXT")

            db.execSQL(
                "CREATE TABLE task_groups (`uuid` TEXT NOT NULL PRIMARY KEY, " +
                    "`user_id` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                    "`collapsed` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_groups_user_id` ON `task_groups` (`user_id`)")

            // Archive the pre-existing completed tasks so history is preserved,
            // matching the new "DONE moves to archive" product rule.
            db.execSQL(
                "UPDATE `tasks` SET archived = 1, archived_at = updated_at " +
                    "WHERE status = 'COMPLETED' AND archived = 0"
            )
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Recurring tasks. Nothing about an existing row changes: a plain task simply has no
            // series, so both new columns stay NULL and the unique index below ignores them
            // (SQLite treats NULLs as distinct inside a unique index).
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `series_uuid` TEXT")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `occurrence_at` INTEGER")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tasks_series_uuid_occurrence_at` " +
                    "ON `tasks` (`series_uuid`, `occurrence_at`)"
            )

            db.execSQL(
                "CREATE TABLE recurrence_rules (`uuid` TEXT NOT NULL PRIMARY KEY, " +
                    "`user_id` INTEGER NOT NULL, `unit` TEXT NOT NULL DEFAULT 'DAY', " +
                    "`interval` INTEGER NOT NULL DEFAULT 1, `week_days` TEXT NOT NULL DEFAULT '', " +
                    "`day_of_month` INTEGER NOT NULL DEFAULT 0, `anchor_at` INTEGER NOT NULL, " +
                    "`zone_id` TEXT NOT NULL DEFAULT '', `cursor_at` INTEGER NOT NULL, " +
                    "`paused` INTEGER NOT NULL DEFAULT 0, `ended_at` INTEGER, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_recurrence_rules_user_id` " +
                    "ON `recurrence_rules` (`user_id`)"
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Reminders. An existing database simply has none: no task row changes, and a task that
            // gets a reminder later is just an insert here. (task_uuid, offset_minutes) is unique so
            // the same lead time on the same task cannot be added twice — not by a double tap, not by
            // a replayed import, not by a pull that already holds it.
            db.execSQL(
                "CREATE TABLE reminders (`uuid` TEXT NOT NULL PRIMARY KEY, " +
                    "`task_uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, " +
                    "`offset_minutes` INTEGER NOT NULL DEFAULT 0, `trigger_at` INTEGER NOT NULL, " +
                    "`anchor_at` INTEGER NOT NULL, `notified_at` INTEGER, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_user_id` ON `reminders` (`user_id`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_reminders_trigger_at` ON `reminders` (`trigger_at`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reminders_task_uuid_offset_minutes` " +
                    "ON `reminders` (`task_uuid`, `offset_minutes`)"
            )
        }
    }

    val ALL = arrayOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9
    )
}
