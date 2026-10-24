-- The schema this app actually shipped at database version 7, generated verbatim from
-- app/schemas/com.taskplanner.data.local.AppDatabase/7.json -- the schema Room exports on
-- every build, because AppDatabase sets exportSchema = true. Only Room's own table-name placeholder is resolved; no column, default or index is edited. A JVM test,
-- LegacySchemaV7SourceTest, fails if this file and that JSON ever drift apart: a
-- hand-written "legacy" table that no release ever had proves nothing about the upgrade
-- path a real user's database actually takes.

CREATE TABLE IF NOT EXISTS `tasks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL DEFAULT '', `title` TEXT NOT NULL, `description` TEXT NOT NULL, `due_date` INTEGER, `priority` TEXT NOT NULL, `status` TEXT NOT NULL, `category_id` INTEGER, `group_id` TEXT, `archived` INTEGER NOT NULL DEFAULT 0, `archived_at` INTEGER, `started_at` INTEGER, `user_id` INTEGER NOT NULL, `order` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL );
CREATE INDEX IF NOT EXISTS `index_tasks_user_id` ON `tasks` (`user_id`);
CREATE INDEX IF NOT EXISTS `index_tasks_category_id` ON `tasks` (`category_id`);
CREATE INDEX IF NOT EXISTS `index_tasks_group_id` ON `tasks` (`group_id`);

CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uuid` TEXT NOT NULL DEFAULT '', `name` TEXT NOT NULL COLLATE NOCASE, `color` INTEGER NOT NULL, `icon` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `parent_category_id` INTEGER, `is_shared` INTEGER NOT NULL DEFAULT 0, `is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, FOREIGN KEY(`parent_category_id`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL );
CREATE INDEX IF NOT EXISTS `index_categories_user_id` ON `categories` (`user_id`);
CREATE INDEX IF NOT EXISTS `index_categories_parent_category_id` ON `categories` (`parent_category_id`);

CREATE TABLE IF NOT EXISTS `users` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `email` TEXT NOT NULL COLLATE NOCASE, `name` TEXT NOT NULL, `avatar_color` INTEGER NOT NULL, `avatar_initial` TEXT NOT NULL, `is_current` INTEGER NOT NULL DEFAULT 0, `firebase_uid` TEXT, `avatar_url` TEXT, `last_used` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`);

CREATE TABLE IF NOT EXISTS `task_groups` (`uuid` TEXT NOT NULL, `user_id` INTEGER NOT NULL, `name` TEXT NOT NULL, `collapsed` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, `is_deleted` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `sync_state` TEXT NOT NULL DEFAULT 'PENDING', `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`uuid`), FOREIGN KEY(`user_id`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE );
CREATE INDEX IF NOT EXISTS `index_task_groups_user_id` ON `task_groups` (`user_id`);

PRAGMA user_version = 7;
