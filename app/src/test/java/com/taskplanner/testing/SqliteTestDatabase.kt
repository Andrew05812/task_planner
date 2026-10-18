package com.taskplanner.testing

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** Column metadata that Room's runtime schema validation compares after a migration. */
data class ColumnMeta(val affinity: String, val notNull: Boolean, val defaultValue: String?)

/**
 * A real SQLite database (org.xerial sqlite-jdbc, the same engine family Android ships) that can
 * execute the production [androidx.room.migration.Migration] objects on the JVM.
 *
 * Migrations only use a handful of [SupportSQLiteDatabase] methods, so the interface is served by a
 * dynamic proxy: any method a migration is not expected to call fails loudly instead of quietly
 * returning a wrong value.
 */
class SqliteTestDatabase private constructor(
    val connection: Connection,
    private val file: File
) : AutoCloseable {

    init {
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException("sqlite-jdbc must be on the test classpath", e)
        }
    }

    fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    fun rows(sql: String): List<List<String?>> {
        return connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                val columns = result.metaData.columnCount
                val out = ArrayList<List<String?>>()
                while (result.next()) {
                    out += (1..columns).map { index -> result.getString(index) }
                }
                out
            }
        }
    }

    fun firstCell(sql: String): String? = rows(sql).firstOrNull()?.firstOrNull()

    fun firstLong(sql: String): Long = firstCell(sql)?.toLongOrNull() ?: 0L

    fun tableNames(): Set<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table'").mapNotNull { it[0] }.toSet()

    fun columnInfo(table: String): Map<String, ColumnMeta> =
        rows("PRAGMA table_info(`$table`)").associate { row ->
            row[1]!! to ColumnMeta(
                affinity = row[2]!!.uppercase(),
                notNull = row[3]!!.toInt() != 0,
                defaultValue = row[4]
            )
        }

    fun primaryKeys(table: String): Set<String> =
        rows("PRAGMA table_info(`$table`)").filter { (it[5]?.toInt() ?: 0) > 0 }.map { it[1]!! }.toSet()

    fun indexInfo(table: String): Set<String> =
        rows("PRAGMA index_list(`$table`)").map { indexRow ->
            val indexName = indexRow[1]!!
            val unique = indexRow[2]!!.toInt() != 0
            val columns = rows("PRAGMA index_info(`$indexName`)").map { it[2] }
            "$indexName|unique=$unique|columns=$columns"
        }.toSet()

    fun foreignKeyInfo(table: String): Set<String> =
        rows("PRAGMA foreign_key_list(`$table`)").map { row ->
            // id, seq, table, from, to, on_update, on_delete, match
            "${row[2]}(${row[3]}->${row[4]}) onUpdate=${row[5]} onDelete=${row[6]}"
        }.toSet()

    fun userVersion(): Long = firstLong("PRAGMA user_version")

    fun migrate(migration: Migration) = migration.migrate(asSupportDatabase())

    override fun close() {
        connection.close()
        file.delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    /** Closes the handle but keeps the file: used to leave a "legacy" database on disk. */
    fun detach() {
        connection.close()
    }

    private fun asSupportDatabase(): SupportSQLiteDatabase {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args!![0] as String
                    if (args.size == 1) exec(sql) else bindAndExecute(sql, args[1] as Array<*>)
                    null
                }

                "getVersion" -> userVersion().toInt()
                "setVersion" -> {
                    exec("PRAGMA user_version = ${(args!![0] as Int)}")
                    null
                }

                "isOpen" -> !connection.isClosed
                "isReadOnly" -> false
                "inTransaction" -> !connection.autoCommit
                "isDbLockedByCurrentThread" -> false
                "isDatabaseIntegrityOk" -> firstCell("PRAGMA quick_check") == "ok"
                "setForeignKeyConstraintsEnabled" -> {
                    exec("PRAGMA foreign_keys = ${if (args!![0] as Boolean) 1 else 0}")
                    null
                }

                "close" -> {
                    close()
                    null
                }

                "toString" -> "SqliteTestDatabase(${file.name})"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args!![0] === this
                else -> throw UnsupportedOperationException(
                    "Migration tests do not expect SupportSQLiteDatabase.${method.name}()"
                )
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
            handler
        ) as SupportSQLiteDatabase
    }

    private fun bindAndExecute(sql: String, args: Array<*>) {
        connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    companion object {
        fun open(name: String = "test-${System.nanoTime()}"): SqliteTestDatabase {
            return openAt(File(System.getProperty("java.io.tmpdir"), "$name.db"))
        }

        /** Opens (creating if needed) a database at an exact path, e.g. a legacy app location. */
        fun openAt(target: File): SqliteTestDatabase {
            target.parentFile?.mkdirs()
            target.delete()
            return SqliteTestDatabase(connect(target), target)
        }

        /** Inspects an existing file; call [detach] afterwards to leave it untouched. */
        fun openExisting(target: File): SqliteTestDatabase =
            SqliteTestDatabase(connect(target), target)

        private fun connect(target: File): Connection {
            val url = "jdbc:sqlite:${target.absolutePath}"
            return try {
                DriverManager.getConnection(url)
            } catch (e: java.sql.SQLException) {
                // Robolectric loads test classes in its own classloader, and DriverManager only
                // accepts a driver that the caller's loader resolves to the registered instance.
                // Building the driver from this class' own loader works in both environments.
                val driver = Class.forName(
                    "org.sqlite.JDBC",
                    true,
                    SqliteTestDatabase::class.java.classLoader
                ).getDeclaredConstructor().newInstance() as java.sql.Driver
                driver.connect(url, java.util.Properties()) ?: throw e
            }
        }
    }
}

/**
 * Reads the schema JSON files Room exported into `app/schemas`; they are the authoritative
 * description of what every historical database version must look like.
 */
object TestSchemas {

    private const val DATABASE_CLASS = "com.taskplanner.data.local.AppDatabase"

    data class Entity(val tableName: String, val createSql: String, val indexSql: List<String>)

    /**
     * Creates the database exactly as Room would on a fresh install of [version].
     * user_version is stamped here because in production Room (not the migration) owns that
     * bookkeeping, so the migrations themselves are not expected to touch it.
     */
    fun createVersion(db: SqliteTestDatabase, version: Int) {
        entities(version).forEach { entity ->
            db.exec(entity.createSql)
            entity.indexSql.forEach(db::exec)
        }
        setupQueries(version).forEach(db::exec)
        db.exec("PRAGMA user_version = $version")
    }

    private fun setupQueries(version: Int): List<String> =
        root(version).child("database").array("setupQueries").map { it.asString }

    private fun entities(version: Int): List<Entity> =
        root(version).child("database").array("entities").map { element ->
            val entity = element.asJsonObject
            val table = entity.string("tableName")
            Entity(
                tableName = table,
                createSql = entity.string("createSql").substitute(table),
                indexSql = entity.optArray("indices").map { index ->
                    index.asJsonObject.string("createSql").substitute(table)
                }
            )
        }

    private fun JsonObject.string(key: String): String = get(key).asString

    private fun JsonObject.child(key: String): JsonObject = get(key).asJsonObject

    private fun JsonObject.array(key: String): List<JsonElement> = getAsJsonArray(key).map { it }

    private fun String.substitute(table: String) = replace("\${TABLE_NAME}", table)

    private fun JsonObject.optArray(key: String): List<JsonElement> =
        if (has(key)) array(key) else emptyList()

    private fun root(version: Int): JsonObject {
        val file = schemaFile(version)
        return JsonParser.parseReader(file.reader(Charsets.UTF_8)).asJsonObject
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/$DATABASE_CLASS/$version.json"
        val root = File(System.getProperty("taskplanner.project.dir") ?: ".")
        val candidates = listOf(
            File(root, relative),
            File(relative),
            File(root, "app/$relative"),
            File(root, "app/src/main/$relative")
        )
        return candidates.firstOrNull(File::exists)
            ?: throw IllegalStateException(
                "Room schema $version.json not found. Tried: " +
                    candidates.joinToString { it.absolutePath }
            )
    }
}
