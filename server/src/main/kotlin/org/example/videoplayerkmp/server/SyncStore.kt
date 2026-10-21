package org.example.videoplayerkmp.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.videoplayerkmp.sync.Change
import org.example.videoplayerkmp.sync.EntityKind
import org.example.videoplayerkmp.sync.PullResponse
import org.example.videoplayerkmp.sync.PushResponse

/**
 * Библиотеки пользователей. Каждое применённое изменение получает следующий номер ревизии
 * пользователя — клиент запоминает последнюю ревизию и при синхронизации забирает всё, что новее.
 */
class SyncStore(private val db: Database) {

    fun push(userId: Long, changes: List<Change>): PushResponse {
        if (changes.size > MAX_PUSH) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "too_many_changes", "Не больше $MAX_PUSH изменений за раз")
        }
        changes.forEach(::validate)
        return db.transaction {
            var revision = queryOne("SELECT revision FROM users WHERE id = ?", userId) { it.getLong(1) }
                ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Аккаунт удалён")
            var applied = 0
            for (change in changes) {
                val existing = queryOne(
                    "SELECT updated_at FROM entities WHERE user_id = ? AND kind = ? AND entity_id = ?",
                    userId, change.kind, change.id,
                ) { it.getLong(1) }
                // Last-write-wins: устаревшее изменение с другого устройства не затирает свежее
                if (existing != null && existing >= change.updatedAt) continue
                revision++
                update(
                    """INSERT INTO entities (user_id, kind, entity_id, data, updated_at, deleted, revision)
                       VALUES (?, ?, ?, ?, ?, ?, ?)
                       ON CONFLICT (user_id, kind, entity_id) DO UPDATE SET
                         data = excluded.data, updated_at = excluded.updated_at,
                         deleted = excluded.deleted, revision = excluded.revision""",
                    userId, change.kind, change.id,
                    if (change.deleted) null else change.data?.toString(),
                    change.updatedAt, change.deleted, revision,
                )
                applied++
            }
            update("UPDATE users SET revision = ? WHERE id = ?", revision, userId)
            PushResponse(revision, applied)
        }
    }

    fun pull(userId: Long, since: Long, limit: Int): PullResponse = db.transaction {
        val size = limit.coerceIn(1, MAX_PULL)
        val rows = query(
            """SELECT kind, entity_id, data, updated_at, deleted, revision FROM entities
               WHERE user_id = ? AND revision > ? ORDER BY revision LIMIT ?""",
            userId, since, size + 1,
        ) {
            Change(
                kind = it.getString(1),
                id = it.getString(2),
                data = it.getString(3)?.let { text -> Json.parseToJsonElement(text) },
                updatedAt = it.getLong(4),
                deleted = it.getInt(5) == 1,
                revision = it.getLong(6),
            )
        }
        val page = rows.take(size)
        val hasMore = rows.size > size
        val revision = if (hasMore) page.last().revision
        else queryOne("SELECT revision FROM users WHERE id = ?", userId) { it.getLong(1) } ?: since
        PullResponse(page, revision, hasMore)
    }

    private fun validate(change: Change) {
        fun bad(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_change", message)
        if (change.kind !in EntityKind.all) bad("Неизвестный вид записи: ${change.kind}")
        if (change.id.isBlank() || change.id.length > 200) bad("Некорректный id записи")
        if (!change.deleted && change.data == null) bad("У записи ${change.kind}/${change.id} нет данных")
        val size = change.data?.let(JsonElement::toString)?.length ?: 0
        if (size > MAX_ENTITY_BYTES) bad("Запись ${change.kind}/${change.id} слишком большая")
    }

    private companion object {
        const val MAX_PUSH = 1000
        const val MAX_PULL = 1000
        const val MAX_ENTITY_BYTES = 16 * 1024
    }
}
