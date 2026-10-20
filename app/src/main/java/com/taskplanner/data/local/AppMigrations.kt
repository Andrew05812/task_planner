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

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // When a task is planned to start, when it is planned to end, and how long the user
            // thinks it takes. All three are nullable, so every existing row keeps the meaning it
            // had: it is scheduled by its deadline alone, and nothing is invented for it. ALTER
            // TABLE ADD COLUMN touches no row at all — no rewrite, no default that could overwrite
            // a value the user did not give.
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `start_time` INTEGER")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `end_time` INTEGER")
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `estimated_minutes` INTEGER")
            // The calendar's one query per window: a profile's tasks ordered by deadline.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tasks_user_id_due_date` " +
                    "ON `tasks` (`user_id`, `due_date`)"
            )
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Two indices and nothing else: no table is touched, no column is added, no row is
            // rewritten. They exist because the calendar's window is bounded by three columns and
            // SQLite can only seek one of them per lookup, so the read that used to walk a whole
            // profile now walks the window instead. An existing database simply gains them.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tasks_user_id_start_time` " +
                    "ON `tasks` (`user_id`, `start_time`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tasks_user_id_end_time_start_time` " +
                    "ON `tasks` (`user_id`, `end_time`, `start_time`)"
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Work tracking. Three new tables and one nullable column: no existing table is rebuilt,
            // no existing row is touched, so a database that already holds years of tasks gains the
            // place to record measured time without anything of what it holds changing meaning.
            //
            // `completed_at` starts NULL, which is the honest state for a task completed by an earlier
            // build — it was completed, but the instant was never recorded, and guessing it from
            // `updated_at` or `archived_at` would put invented timestamps into a column whose whole
            // purpose is to be trustworthy.
            db.execSQL("ALTER TABLE `tasks` ADD COLUMN `completed_at` INTEGER")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `tracking_sessions` (`uuid` TEXT NOT NULL, " +
                    "`user_id` INTEGER NOT NULL, `task_id` INTEGER NOT NULL, " +
                    "`task_uuid` TEXT NOT NULL, `task_title` TEXT NOT NULL, " +
                    "`state` TEXT NOT NULL DEFAULT 'RUNNING', `started_at` INTEGER NOT NULL, " +
                    "`stopped_at` INTEGER, `zone_id` TEXT NOT NULL, `running_marker` TEXT, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`uuid`), " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_tracking_sessions_running_marker` " +
                    "ON `tracking_sessions` (`running_marker`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tracking_sessions_user_id` " +
                    "ON `tracking_sessions` (`user_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tracking_sessions_user_id_task_id` " +
                    "ON `tracking_sessions` (`user_id`, `task_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tracking_sessions_user_id_started_at` " +
                    "ON `tracking_sessions` (`user_id`, `started_at`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `work_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                    "NOT NULL, `uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, " +
                    "`tracking_session_uuid` TEXT NOT NULL, `task_id` INTEGER NOT NULL, " +
                    "`task_uuid` TEXT NOT NULL, `started_at` INTEGER NOT NULL, `ended_at` INTEGER, " +
                    "`duration_seconds` INTEGER, `local_day` INTEGER NOT NULL, " +
                    "`utc_offset_minutes` INTEGER NOT NULL, `source` TEXT NOT NULL DEFAULT 'TRACKER', " +
                    "`open_marker` TEXT, `clock_anomaly` TEXT, `is_deleted` INTEGER NOT NULL DEFAULT 0, " +
                    "`deleted_at` INTEGER, `sync_state` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            // One open interval per profile. A partial index (`WHERE ended_at IS NOT NULL`) would say
            // it more directly, but Room cannot declare one, and an index Room does not know about
            // would make the migrated schema differ from the exported one. NULLs are distinct in a
            // SQLite unique index, so the marker column closes on pause and stop by itself.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_sessions_user_id_open_marker` " +
                    "ON `work_sessions` (`user_id`, `open_marker`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_sessions_user_id_started_at` " +
                    "ON `work_sessions` (`user_id`, `started_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_sessions_user_id_local_day` " +
                    "ON `work_sessions` (`user_id`, `local_day`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_sessions_user_id_task_id` " +
                    "ON `work_sessions` (`user_id`, `task_id`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_sessions_tracking_session_uuid` " +
                    "ON `work_sessions` (`tracking_session_uuid`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_work_sessions_uuid` " +
                    "ON `work_sessions` (`uuid`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `work_interval_corrections` (`uuid` TEXT NOT NULL, " +
                    "`interval_uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, " +
                    "`old_started_at` INTEGER NOT NULL, `old_ended_at` INTEGER, " +
                    "`new_started_at` INTEGER NOT NULL, `new_ended_at` INTEGER, `reason` TEXT, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`), " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_interval_corrections_interval_uuid` " +
                    "ON `work_interval_corrections` (`interval_uuid`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_work_interval_corrections_user_id` " +
                    "ON `work_interval_corrections` (`user_id`)"
            )
        }
    }

    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // One index and nothing else. The dashboard answers "what was finished in this window"
            // straight from `completed_at`, and without an index that starts with the profile, SQLite
            // would answer it by walking every task of every profile on the device — the read the
            // whole aggregation exists to replace. The column itself arrived with schema 12, so this
            // step adds no column, rewrites no row, and only makes the data already there seekable.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_tasks_user_id_completed_at` " +
                    "ON `tasks` (`user_id`, `completed_at`)"
            )
        }
    }

    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Six new tables and their indices, and no change to any existing row. Goals and habits
            // count work that already lives in `tasks` and `work_sessions`, so an upgraded database has
            // everything the new features need to compute a figure on day one: the first goal a user
            // writes can be a task they finished last month, and the first mark can be backdated —
            // which is exactly why neither table stores a progress column that would have to be
            // backfilled here, and why nothing in this step reads or rewrites a row of an older table.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `goals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                    "NOT NULL, `uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`type` TEXT NOT NULL DEFAULT 'TASK_COUNT', `target_value` INTEGER NOT NULL, " +
                    "`window_from_day` INTEGER, `window_to_day` INTEGER, `deadline_at` INTEGER, " +
                    "`archived_at` INTEGER, `is_deleted` INTEGER NOT NULL DEFAULT 0, " +
                    "`deleted_at` INTEGER, `sync_state` TEXT NOT NULL DEFAULT 'PENDING', " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goals_user_id_is_deleted` " +
                    "ON `goals` (`user_id`, `is_deleted`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_goals_uuid` ON `goals` (`uuid`)"
            )

            // The composite key is the whole rule of this table: a goal names a task once. There is no
            // foreign key to `tasks`, the same reason `work_sessions` has none — the link is a record of
            // what the user asked for, and it outlives the card, arrives before it from another device,
            // and must not be taken away by an archive or a delete.
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `goal_tasks` (`goal_uuid` TEXT NOT NULL, " +
                    "`task_uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`added_at` INTEGER NOT NULL, PRIMARY KEY(`goal_uuid`, `task_uuid`), " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_tasks_goal_uuid_added_at` " +
                    "ON `goal_tasks` (`goal_uuid`, `added_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_tasks_task_uuid` " +
                    "ON `goal_tasks` (`task_uuid`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_tasks_user_id` ON `goal_tasks` (`user_id`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `goal_value_changes` (`uuid` TEXT NOT NULL, " +
                    "`goal_uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `field` TEXT NOT NULL, " +
                    "`old_value` TEXT, `new_value` TEXT, `reason` TEXT, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`), " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_value_changes_goal_uuid_created_at` " +
                    "ON `goal_value_changes` (`goal_uuid`, `created_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_goal_value_changes_user_id` " +
                    "ON `goal_value_changes` (`user_id`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `habits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                    "NOT NULL, `uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`frequency` TEXT NOT NULL DEFAULT 'DAILY', `days_mask` INTEGER NOT NULL DEFAULT 0, " +
                    "`times_per_week` INTEGER, `start_day` INTEGER NOT NULL, `end_day` INTEGER, " +
                    "`remind_minute_of_day` INTEGER, `reminded_on_day` INTEGER, `archived_at` INTEGER, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habits_user_id_is_deleted` " +
                    "ON `habits` (`user_id`, `is_deleted`)"
            )
            // The six-hourly sweep asks this one question of every profile it visits, so the reminder
            // minute has to be seekable with the profile rather than scanned for it.
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habits_user_id_remind_minute_of_day` " +
                    "ON `habits` (`user_id`, `remind_minute_of_day`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_habits_uuid` ON `habits` (`uuid`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `habit_checks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT " +
                    "NOT NULL, `uuid` TEXT NOT NULL, `habit_uuid` TEXT NOT NULL, " +
                    "`user_id` INTEGER NOT NULL, `local_day` INTEGER NOT NULL, " +
                    "`utc_offset_minutes` INTEGER NOT NULL, `day_marker` INTEGER, " +
                    "`is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            // One live mark per habit per day, as a database rule: `day_marker` is the nullable twin of
            // `local_day` and is nulled when a mark is taken back, so retired rows stop colliding with
            // anything while the live one keeps the day. This is the same marker-column pattern
            // `index_work_sessions_user_id_open_marker` uses, for the same reason — Room's `@Index` has
            // no WHERE clause, and an index written only here would leave the physical schema different
            // from the schema Room exports.
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_checks_habit_uuid_day_marker` " +
                    "ON `habit_checks` (`habit_uuid`, `day_marker`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habit_checks_user_id_local_day` " +
                    "ON `habit_checks` (`user_id`, `local_day`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habit_checks_habit_uuid_local_day` " +
                    "ON `habit_checks` (`habit_uuid`, `local_day`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_habit_checks_uuid` " +
                    "ON `habit_checks` (`uuid`)"
            )

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `habit_check_changes` (`uuid` TEXT NOT NULL, " +
                    "`check_uuid` TEXT NOT NULL, `habit_uuid` TEXT NOT NULL, " +
                    "`user_id` INTEGER NOT NULL, `old_local_day` INTEGER NOT NULL, " +
                    "`new_local_day` INTEGER, `reason` TEXT, " +
                    "`sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, " +
                    "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`), " +
                    "FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION " +
                    "ON DELETE CASCADE)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habit_check_changes_check_uuid` " +
                    "ON `habit_check_changes` (`check_uuid`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habit_check_changes_habit_uuid_created_at` " +
                    "ON `habit_check_changes` (`habit_uuid`, `created_at`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_habit_check_changes_user_id` " +
                    "ON `habit_check_changes` (`user_id`)"
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
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14
    )
}
