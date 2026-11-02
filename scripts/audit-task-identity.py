#!/usr/bin/env python3
"""Measure what Stage 16 §4 asks about a Task Planner database: is a task's identity unique, and does every
parent link resolve?

Read-only. The database is opened with ``uri=True`` and ``mode=ro``, so nothing here can write to it, and no row
text is ever printed — only counts and aggregate shapes. That is deliberate: the file being measured is somebody's
notebook, and a tool that dumps titles into a terminal log could not be committed to a public repository.

Usage::

    py scripts/audit-task-identity.py <path-to-taskplanner.db>

Point it at a *copy* of a device database, taken alongside its ``-wal`` and ``-shm`` files so the live state is
visible::

    adb -s emulator-5584 exec-out "run-as com.taskplanner cat databases/taskplanner.db"     > /tmp/audit/taskplanner.db
    adb -s emulator-5584 exec-out "run-as com.taskplanner cat databases/taskplanner.db-wal" > /tmp/audit/taskplanner.db-wal
    adb -s emulator-5584 exec-out "run-as com.taskplanner cat databases/taskplanner.db-shm" > /tmp/audit/taskplanner.db-shm
    py scripts/audit-task-identity.py /tmp/audit/taskplanner.db
"""

import sqlite3
import sys

# (table, task-key column) — every relation that names a task by uuid rather than by row id. A link that resolves
# to nothing is not a hierarchy problem only: `parent_uuid` is the same kind of column as these, one stage later.
DEPENDENTS = [
    ("reminders", "task_uuid"),
    ("goal_tasks", "task_uuid"),
    ("work_sessions", "task_uuid"),
    ("tracking_sessions", "task_uuid"),
]


def main(db_path):
    con = sqlite3.connect("file:%s?mode=ro" % db_path, uri=True)
    con.row_factory = sqlite3.Row
    cur = con.cursor()

    def q(sql, args=()):
        cur.execute(sql, args)
        return [dict(r) for r in cur.fetchall()]

    def one(sql, args=()):
        rows = q(sql, args)
        return rows[0][list(rows[0].keys())[0]] if rows else None

    tables = {r["name"] for r in q("SELECT name FROM sqlite_master WHERE type='table'")}
    print("schema version :", one("PRAGMA user_version"))
    if "tasks" not in tables:
        print("no tasks table — this is not a Task Planner database")
        return 1

    tasks = q(
        "SELECT id, uuid, user_id, group_id, parent_uuid, is_deleted, archived, \"order\" FROM tasks"
    )
    live = [t for t in tasks if t["is_deleted"] == 0]
    print("task rows      :", len(tasks), "| live", len(live), "| tombstones", len(tasks) - len(live))
    print("profiles with rows:", len({t["user_id"] for t in tasks}),
          "| users rows:", one("SELECT COUNT(*) FROM users") if "users" in tables else "n/a")

    print("EMPTY uuid     :", sum(1 for t in tasks if not t["uuid"]))

    dup_within = q(
        "SELECT user_id, uuid, COUNT(*) c FROM tasks WHERE uuid <> '' "
        "GROUP BY user_id, uuid HAVING c > 1"
    )
    print("duplicate uuid inside one profile:", len(dup_within), "groups,",
          sum(r["c"] for r in dup_within), "rows")
    print("one uuid held by two profiles    :", len(q(
        "SELECT uuid, COUNT(DISTINCT user_id) c FROM tasks WHERE uuid <> '' "
        "GROUP BY uuid HAVING c > 1"
    )))

    by_uuid = {}
    for t in tasks:
        if t["uuid"]:
            by_uuid.setdefault(t["uuid"], []).append(t)
    live_uuids = {t["uuid"] for t in live if t["uuid"]}
    kids = [t for t in live if t["parent_uuid"]]
    blank_parent = [t for t in live if t["parent_uuid"] == ""]
    print("live rows carrying a parent link :", len(kids),
          "| of which the link is blank:", len(blank_parent))
    print("  parent resolves to no row      :", sum(1 for t in kids if t["parent_uuid"] not in by_uuid))
    print("  parent resolves to no LIVE row :", sum(1 for t in kids if t["parent_uuid"] not in live_uuids))
    print("  parent is another profile      :", sum(
        1 for t in kids if any(p["user_id"] != t["user_id"] for p in by_uuid.get(t["parent_uuid"], []))))
    print("  parent is another topic        :", sum(
        1 for t in kids if t["parent_uuid"] in by_uuid and not any(
            p["user_id"] == t["user_id"] and p["group_id"] == t["group_id"]
            for p in by_uuid[t["parent_uuid"]])))

    cycles = 0
    for t in kids:
        seen, walker, depth = set(), t["parent_uuid"], 0
        while walker in by_uuid and depth < 64:
            if walker in seen:
                cycles += 1
                break
            seen.add(walker)
            walker = by_uuid[walker][0]["parent_uuid"]
            depth += 1
            if not walker:
                break
    print("  ancestor chain repeats a uuid  :", cycles)

    groups = {r["uuid"] for r in q("SELECT uuid FROM task_groups")} if "task_groups" in tables else set()
    print("live rows whose group_id names no topic:",
          sum(1 for t in live if t["group_id"] and t["group_id"] not in groups))

    collide = q(
        "SELECT user_id, \"order\", COUNT(*) c FROM tasks WHERE is_deleted = 0 "
        "GROUP BY user_id, \"order\" HAVING c > 1"
    )
    print("POSITIONS shared by two live rows:", len(collide), "groups,",
          sum(r["c"] for r in collide), "rows")
    for g in q(
        "SELECT user_id, COUNT(*) n, MIN(\"order\") lo, MAX(\"order\") hi FROM tasks "
        "WHERE is_deleted = 0 GROUP BY user_id ORDER BY n DESC, user_id"
    ):
        span = g["hi"] - g["lo"] + 1
        print("  profile %s: live=%s min=%s max=%s dense=%s"
              % (g["user_id"], g["n"], g["lo"], g["hi"], span == g["n"]))

    for table, column in DEPENDENTS:
        if table not in tables:
            continue
        total = one("SELECT COUNT(*) FROM %s" % table)
        dangling = one(
            "SELECT COUNT(*) FROM %s WHERE %s NOT IN (SELECT uuid FROM tasks WHERE uuid <> '')"
            % (table, column)
        )
        print("%-20s: rows=%s referencing an identity nothing holds=%s" % (table, total, dangling))

    print("indexes on tasks:", ", ".join(
        "%s(%s)" % (r["name"], "unique" if r["unique"] else "plain")
        for r in q("PRAGMA index_list(tasks)")))
    con.close()
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
