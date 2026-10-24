-- The evidence fixture: the data the before/after screenshot matrix is taken against.
--
-- Why this file exists. `app/build.gradle.kts:29` sets the `clearPackageData` runner argument, and AGP
-- additionally uninstalls the app after `connectedDebugAndroidTest`. Either one wipes the profile the
-- screenshots were taken with, so a matrix captured once cannot be re-captured after a test run — and a
-- baseline that cannot be reproduced is not evidence. This file makes it reproducible: run
-- `python scripts/apply-evidence-seed.py` after any connected run, then relaunch the app.
--
-- What it is safe to run against a device that has real data. Every row it writes carries a uuid that
-- starts with `ev-` and an id in the 9000 block, and the cleanup at the top deletes only rows whose uuid
-- starts with `ev-`. It never touches a row it did not create, never drops a table, never clears app
-- data. Explicit ids mean a collision with a real user's row fails loudly with a constraint error rather
-- than overwriting anything.
--
-- The profile: created only when the device has none, and never in place of one the user made. Every other
-- row binds to `users where is_current=1` by subquery, so the fixture works whatever id that profile ended
-- up with, and it fails loudly (NOT NULL) if the profile insert was refused.
--
-- Timestamps are relative to the moment the seed is applied, which keeps every state it exercises true
-- on the day it is captured: "due in three hours", "overdue yesterday", "measured 70 minutes". Re-apply
-- before a capture session rather than trusting a fixture that has aged.

PRAGMA foreign_keys = off;

CREATE TEMP VIEW ev AS
SELECT
    (SELECT id FROM users WHERE is_current = 1) AS uid,
    CAST(strftime('%s', 'now') AS INTEGER) * 1000 AS now,
    CAST(strftime('%s', 'now') AS INTEGER) / 86400 AS day;

-- ---------------------------------------------------------------- clean out the previous application
DELETE FROM reminders WHERE uuid LIKE 'ev-%';
DELETE FROM work_sessions WHERE uuid LIKE 'ev-%';
DELETE FROM recurrence_rules WHERE uuid LIKE 'ev-%';
DELETE FROM tasks WHERE uuid LIKE 'ev-%';
DELETE FROM task_groups WHERE uuid LIKE 'ev-%';
DELETE FROM categories WHERE uuid LIKE 'ev-%';
DELETE FROM users WHERE email = 'evidence@local.invalid';

-- ---------------------------------------------------------------- the profile the rows belong to
-- Created only when the device has no current profile at all, which is the normal state after a
-- connected run (`clearPackageData` wipes the app, AGP uninstalls it). A device that already has a
-- profile keeps it: this statement is a `WHERE NOT EXISTS`, and nothing here ever rewrites or deletes a
-- profile it did not create.
INSERT INTO users (email, name, avatar_color, avatar_initial, is_current, last_used, created_at, updated_at)
    SELECT 'evidence@local.invalid', 'Andrei', -8497214, 'A', 1,
           (SELECT now FROM ev), (SELECT now FROM ev), (SELECT now FROM ev)
    WHERE NOT EXISTS (SELECT 1 FROM users WHERE is_current = 1);


-- ---------------------------------------------------------------- categories
-- The five colours are deliberately light — the ones that fail AA as text on white. That is what audit
-- finding 3 measures, and the fix (the dot carries the colour, the name is ink) has to be provable
-- against exactly these values rather than against a palette that was already accessible.
INSERT INTO categories (id, uuid, name, color, icon, user_id, created_at, updated_at) VALUES
    (9001, 'ev-c1', 'Диссертация', -5908825, 'ic_category', (SELECT uid FROM ev), (SELECT now FROM ev), (SELECT now FROM ev)),
    (9002, 'ev-c2', 'Учёба',       -10929,   'ic_category', (SELECT uid FROM ev), (SELECT now FROM ev), (SELECT now FROM ev)),
    (9003, 'ev-c3', 'Работа',    -11549705, 'ic_category', (SELECT uid FROM ev), (SELECT now FROM ev), (SELECT now FROM ev)),
    (9004, 'ev-c4', 'Дом',         -749647,  'ic_category', (SELECT uid FROM ev), (SELECT now FROM ev), (SELECT now FROM ev)),
    (9005, 'ev-c5', 'Спорт',      -6381922,  'ic_category', (SELECT uid FROM ev), (SELECT now FROM ev), (SELECT now FROM ev));

-- ---------------------------------------------------------------- topics (task_groups)
INSERT INTO task_groups (uuid, user_id, name, collapsed, sort_order, created_at, updated_at) VALUES
    ('ev-g1', (SELECT uid FROM ev), 'Диссертация', 0, 0, (SELECT now FROM ev), (SELECT now FROM ev)),
    ('ev-g2', (SELECT uid FROM ev), 'Лабораторные', 0, 1, (SELECT now FROM ev), (SELECT now FROM ev));

-- ---------------------------------------------------------------- the series two tasks belong to
INSERT INTO recurrence_rules (uuid, user_id, unit, interval, week_days, day_of_month, anchor_at,
                              zone_id, cursor_at, created_at, updated_at) VALUES
    ('ev-r1', (SELECT uid FROM ev), 'WEEK', 1, '', 0,
        (SELECT now - 3*86400000 FROM ev), '', (SELECT now + 2*86400000 FROM ev),
        (SELECT now FROM ev), (SELECT now FROM ev)),
    ('ev-r2', (SELECT uid FROM ev), 'DAY', 1, '', 0,
        (SELECT now - 86400000 FROM ev), '', (SELECT now + 21600000 FROM ev),
        (SELECT now FROM ev), (SELECT now FROM ev));

-- ---------------------------------------------------------------- tasks
-- One row per state the redesigned card has to render, and two rows that must stay invisible.
INSERT INTO tasks (id, uuid, title, description, due_date, start_time, end_time, estimated_minutes,
                   priority, status, category_id, group_id, series_uuid, occurrence_at, archived,
                   started_at, completed_at, user_id, `order`, created_at, updated_at) VALUES
    -- measured time, an open stretch of reminders and a deadline: the fullest card on the screen
    (9101, 'ev-t1', 'Глава 2: обзор методов', 'Три подхода, сравнительная таблица.',
        (SELECT now + 5*3600000 FROM ev), NULL, NULL, 120, 'HIGH', 'IN_PROGRESS', 9001, 'ev-g1',
        NULL, NULL, 0, (SELECT now - 2*86400000 FROM ev), NULL, (SELECT uid FROM ev), 0,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- overdue: the only card that has to be readable without relying on colour alone
    (9102, 'ev-t2', 'Собрать библиографию', '',
        (SELECT now - 26*3600000 FROM ev), NULL, NULL, NULL, 'MEDIUM', 'TODO', 9001, 'ev-g1',
        NULL, NULL, 0, NULL, NULL, (SELECT uid FROM ev), 1,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- paused after a started stretch
    (9103, 'ev-t3', 'Правки научного руководителя', '',
        (SELECT now + 26*3600000 FROM ev), NULL, NULL, 60, 'LOW', 'PAUSED', 9001, 'ev-g1',
        NULL, NULL, 0, (SELECT now - 86400000 FROM ev), NULL, (SELECT uid FROM ev), 2,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- a weekly series: the repeat chip
    (9104, 'ev-t4', 'Отчёт по лабораторной 7', '',
        (SELECT now + 2*3600000 FROM ev), NULL, NULL, 90, 'HIGH', 'TODO', 9002, 'ev-g2',
        'ev-r1', (SELECT now + 2*3600000 FROM ev), 0, NULL, NULL, (SELECT uid FROM ev), 3,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- completed: the struck-through card, and the deadline it met
    (9105, 'ev-t5', 'Допуск к экзамену', '',
        (SELECT now - 3*86400000 FROM ev), NULL, NULL, NULL, 'MEDIUM', 'COMPLETED', 9002, 'ev-g2',
        NULL, NULL, 0, NULL, (SELECT now - 4*86400000 FROM ev), (SELECT uid FROM ev), 4,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- time-blocked rather than deadline-only
    (9106, 'ev-t6', 'Созвон с командой', '',
        NULL, (SELECT now + 7*3600000 FROM ev), (SELECT now + 8*3600000 FROM ev), 45,
        'MEDIUM', 'TODO', 9003, NULL, NULL, NULL, 0, NULL, NULL, (SELECT uid FROM ev), 5,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- no category at all: what the "No category" filter is for
    (9107, 'ev-t7', 'Оплатить хостинг', '',
        (SELECT now + 3*86400000 FROM ev), NULL, NULL, NULL, 'LOW', 'TODO',
        NULL, NULL, NULL, NULL, 0, NULL, NULL, (SELECT uid FROM ev), 6,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- a daily series with one reminder still armed
    (9108, 'ev-t8', 'Тренировка', '',
        (SELECT now + 6*3600000 FROM ev), NULL, NULL, NULL, 'LOW', 'TODO', 9005, NULL,
        'ev-r2', (SELECT now + 6*3600000 FROM ev), 0, NULL, NULL, (SELECT uid FROM ev), 7,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- a title long enough to wrap at 320dp: the row that proves the card does not clip
    (9109, 'ev-t9',
        'Подготовить и отправить документы в деканат — заявление, справку и копию зачётной книжки',
        '', NULL, NULL, NULL, NULL, 'MEDIUM', 'TODO', 9004, NULL, NULL, NULL, 0, NULL, NULL,
        (SELECT uid FROM ev), 8, (SELECT now FROM ev), (SELECT now FROM ev)),
    -- the second-heaviest measured card, no deadline
    (9110, 'ev-t10', 'Рефакторинг модуля отчётов', '',
        NULL, NULL, NULL, 240, 'MEDIUM', 'IN_PROGRESS', 9003, NULL, NULL, NULL, 0,
        (SELECT now - 4*86400000 FROM ev), NULL, (SELECT uid FROM ev), 9,
        (SELECT now FROM ev), (SELECT now FROM ev)),
    -- overdue by hours, on the same day: a different overdue wording
    (9111, 'ev-t11', 'Разобрать почту', '',
        (SELECT now - 3*3600000 FROM ev), NULL, NULL, 30, 'LOW', 'TODO', 9003, NULL, NULL, NULL, 0,
        NULL, NULL, (SELECT uid FROM ev), 10, (SELECT now FROM ev), (SELECT now FROM ev)),
    -- a second long title inside a topic, so the group header is not the only wide text
    (9112, 'ev-t12', 'Приложения к главе 3: листинги и схемы', '',
        (SELECT now + 9*86400000 FROM ev), NULL, NULL, NULL, 'LOW', 'TODO', 9001, 'ev-g1',
        NULL, NULL, 0, NULL, NULL, (SELECT uid FROM ev), 11, (SELECT now FROM ev), (SELECT now FROM ev)),
    -- invisible on purpose: archived and soft-deleted rows must not appear, and their absence in the
    -- screenshot is what shows the list query is still filtering after the redesign
    (9113, 'ev-t13', 'ЭТА ЗАДАЧА НЕ ДОЛЖНА БЫТЬ ВИДНА (архив)', '',
        NULL, NULL, NULL, NULL, 'LOW', 'TODO', NULL, NULL, NULL, NULL, 1, NULL, NULL,
        (SELECT uid FROM ev), 12, (SELECT now FROM ev), (SELECT now FROM ev)),
    (9114, 'ev-t14', 'ЭТА ЗАДАЧА НЕ ДОЛЖНА БЫТЬ ВИДНА (удалена)', '',
        NULL, NULL, NULL, NULL, 'LOW', 'TODO', NULL, NULL, NULL, NULL, 0, NULL, NULL,
        (SELECT uid FROM ev), 13, (SELECT now FROM ev), (SELECT now FROM ev));

UPDATE tasks SET is_deleted = 1, deleted_at = (SELECT now FROM ev) WHERE uuid = 'ev-t14';

-- ---------------------------------------------------------------- measured time
-- Closed stretches only. An open one would make every screenshot in the matrix differ from the last by
-- however long the capture took, which is the opposite of a baseline.
INSERT INTO work_sessions (id, uuid, user_id, tracking_session_uuid, task_id, task_uuid, started_at,
                           ended_at, duration_seconds, local_day, utc_offset_minutes, source,
                           created_at, updated_at) VALUES
    (9201, 'ev-w1', (SELECT uid FROM ev), 'ev-ts1', 9101, 'ev-t1',
        (SELECT now - 2*86400000 FROM ev), (SELECT now - 2*86400000 + 2400000 FROM ev), 2400,
        (SELECT day FROM ev), 0, 'TRACKER', (SELECT now FROM ev), (SELECT now FROM ev)),
    (9202, 'ev-w2', (SELECT uid FROM ev), 'ev-ts1', 9101, 'ev-t1',
        (SELECT now - 86400000 FROM ev), (SELECT now - 86400000 + 1830000 FROM ev), 1830,
        (SELECT day FROM ev), 0, 'TRACKER', (SELECT now FROM ev), (SELECT now FROM ev)),
    (9203, 'ev-w3', (SELECT uid FROM ev), 'ev-ts2', 9110, 'ev-t10',
        (SELECT now - 3*86400000 FROM ev), (SELECT now - 3*86400000 + 12600000 FROM ev), 12600,
        (SELECT day FROM ev), 0, 'TRACKER', (SELECT now FROM ev), (SELECT now FROM ev));

-- ---------------------------------------------------------------- armed reminders
-- `Reminder.isPending` is "not deleted and not yet delivered", so these are what puts the bell on the
-- card: two leads on the dissertation chapter, one on the training session.
INSERT INTO reminders (uuid, task_uuid, user_id, offset_minutes, trigger_at, anchor_at,
                       created_at, updated_at) VALUES
    ('ev-m1', 'ev-t1', (SELECT uid FROM ev), 30,
        (SELECT now + 4*3600000 + 1800000 FROM ev), (SELECT now + 5*3600000 FROM ev),
        (SELECT now FROM ev), (SELECT now FROM ev)),
    ('ev-m2', 'ev-t1', (SELECT uid FROM ev), 1440,
        (SELECT now - 86400000 + 5*3600000 FROM ev), (SELECT now + 5*3600000 FROM ev),
        (SELECT now FROM ev), (SELECT now FROM ev)),
    ('ev-m3', 'ev-t8', (SELECT uid FROM ev), 60,
        (SELECT now + 5*3600000 FROM ev), (SELECT now + 6*3600000 FROM ev),
        (SELECT now FROM ev), (SELECT now FROM ev));

DROP VIEW ev;

-- What the fixture claims it planted, printed for the record.
SELECT 'categories', COUNT(*) FROM categories WHERE uuid LIKE 'ev-%';
SELECT 'task_groups', COUNT(*) FROM task_groups WHERE uuid LIKE 'ev-%';
SELECT 'recurrence_rules', COUNT(*) FROM recurrence_rules WHERE uuid LIKE 'ev-%';
SELECT 'tasks', COUNT(*) FROM tasks WHERE uuid LIKE 'ev-%';
SELECT 'work_sessions', COUNT(*) FROM work_sessions WHERE uuid LIKE 'ev-%';
SELECT 'reminders', COUNT(*) FROM reminders WHERE uuid LIKE 'ev-%';
