package org.example.videoplayerkmp.server

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * SQLite в одном файле: для личного сервера этого с запасом, а резервная копия — копия файла.
 * Одно соединение, доступ к нему последовательный — SQLite всё равно пишет по одному.
 */
class Database(file: File) : AutoCloseable {
    private val connection: Connection

    init {
        file.parentFile?.mkdirs()
        connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode = WAL")
            it.execute("PRAGMA foreign_keys = ON")
            it.execute("PRAGMA busy_timeout = 5000")
        }
        migrate()
    }

    /** Выполняет [block] в транзакции; при исключении всё откатывается. */
    @Synchronized
    fun <T> transaction(block: Connection.() -> T): T {
        connection.autoCommit = false
        try {
            val result = connection.block()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    override fun close() = connection.close()

    /** Схема версионируется через PRAGMA user_version: каждая миграция применяется один раз. */
    private fun migrate() {
        val migrations = listOf(
            """
            CREATE TABLE users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                is_admin INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                revision INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE invites (
                code TEXT PRIMARY KEY,
                created_by INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                used_by INTEGER REFERENCES users(id) ON DELETE SET NULL
            );
            CREATE TABLE refresh_tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                token_hash TEXT NOT NULL UNIQUE,
                device_name TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            );
            CREATE TABLE entities (
                user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                kind TEXT NOT NULL,
                entity_id TEXT NOT NULL,
                data TEXT,
                updated_at INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                revision INTEGER NOT NULL,
                PRIMARY KEY (user_id, kind, entity_id)
            );
            CREATE INDEX entities_by_revision ON entities(user_id, revision);
            """,
        )
        val current = connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { it.getInt(1) }
        }
        migrations.drop(current).forEachIndexed { i, sql ->
            transaction {
                createStatement().use { st ->
                    sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach(st::execute)
                    st.execute("PRAGMA user_version = ${current + i + 1}")
                }
            }
        }
    }
}

// ---------- Мелкие помощники JDBC ----------

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepare(sql, args).use { it.executeUpdate() }

fun Connection.insert(sql: String, vararg args: Any?): Long =
    prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS).use { st ->
        bind(st, args)
        st.executeUpdate()
        st.generatedKeys.use { if (it.next()) it.getLong(1) else 0L }
    }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepare(sql, args).use { st ->
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

private fun Connection.prepare(sql: String, args: Array<out Any?>): PreparedStatement =
    prepareStatement(sql).also { bind(it, args) }

private fun bind(st: PreparedStatement, args: Array<out Any?>) {
    args.forEachIndexed { i, arg ->
        when (arg) {
            null -> st.setObject(i + 1, null)
            is Boolean -> st.setInt(i + 1, if (arg) 1 else 0)
            is Int -> st.setInt(i + 1, arg)
            is Long -> st.setLong(i + 1, arg)
            is String -> st.setString(i + 1, arg)
            else -> error("Неподдерживаемый тип параметра: ${arg::class}")
        }
    }
}
