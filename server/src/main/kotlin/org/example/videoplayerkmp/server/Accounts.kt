package org.example.videoplayerkmp.server

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.HttpStatusCode
import org.example.videoplayerkmp.sync.InviteDto
import org.example.videoplayerkmp.sync.UserDto
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.util.Base64

/** Ошибка, которую сервер отдаёт клиенту как есть: статус + машинный код + текст для человека. */
class ApiException(val status: HttpStatusCode, val code: String, message: String) : Exception(message)

data class User(val id: Long, val username: String, val passwordHash: String, val isAdmin: Boolean, val createdAt: Long) {
    fun toDto() = UserDto(id, username, isAdmin, createdAt)
}

/** Пользователи, приглашения и токены обновления. */
class Accounts(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val random = SecureRandom()

    fun needsSetup(): Boolean = db.transaction { userCount() == 0L }

    /** Первый аккаунт на пустом сервере — администратор. */
    fun setup(username: String, password: String): User = db.transaction {
        if (userCount() > 0) throw ApiException(HttpStatusCode.Conflict, "already_set_up", "Сервер уже настроен — войдите или попросите приглашение")
        createUser(username, password, isAdmin = true)
    }

    /** Регистрация по приглашению: код одноразовый и с ограниченным сроком. */
    fun register(username: String, password: String, inviteCode: String): User = db.transaction {
        val code = inviteCode.trim().uppercase()
        val invite = queryOne("SELECT expires_at, used_by FROM invites WHERE code = ?", code) {
            it.getLong(1) to it.getObject(2)
        } ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_invite", "Код приглашения не найден")
        if (invite.second != null) throw ApiException(HttpStatusCode.BadRequest, "invalid_invite", "Это приглашение уже использовано")
        if (invite.first < clock()) throw ApiException(HttpStatusCode.BadRequest, "invalid_invite", "Срок приглашения истёк")
        val user = createUser(username, password, isAdmin = false)
        update("UPDATE invites SET used_by = ? WHERE code = ?", user.id, code)
        user
    }

    fun authenticate(username: String, password: String): User {
        val user = db.transaction { findByName(username) }
        // Проверяем пароль и для несуществующего пользователя — чтобы по времени ответа нельзя было подбирать логины
        val hash = user?.passwordHash ?: DUMMY_HASH
        val ok = BCrypt.verifyer().verify(password.toCharArray(), hash).verified
        if (user == null || !ok) throw ApiException(HttpStatusCode.Unauthorized, "invalid_credentials", "Неверное имя пользователя или пароль")
        return user
    }

    fun findById(id: Long): User? = db.transaction {
        queryOne("SELECT * FROM users WHERE id = ?", id, map = ::toUser)
    }

    fun listUsers(): List<User> = db.transaction { query("SELECT * FROM users ORDER BY id", map = ::toUser) }

    fun deleteUser(id: Long) = db.transaction {
        val user = queryOne("SELECT * FROM users WHERE id = ?", id, map = ::toUser)
            ?: throw ApiException(HttpStatusCode.NotFound, "not_found", "Пользователь не найден")
        if (user.isAdmin) throw ApiException(HttpStatusCode.BadRequest, "cannot_delete_admin", "Администратора удалить нельзя")
        update("DELETE FROM users WHERE id = ?", id)
    }

    /** Смена пароля завершает сессии на всех остальных устройствах. */
    fun changePassword(userId: Long, current: String, new: String) {
        val user = findById(userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Сессия недействительна")
        authenticate(user.username, current)
        validatePassword(new)
        db.transaction {
            update("UPDATE users SET password_hash = ? WHERE id = ?", hash(new), userId)
            update("DELETE FROM refresh_tokens WHERE user_id = ?", userId)
        }
    }

    // ---------- Приглашения ----------

    fun createInvite(adminId: Long, validDays: Int = 7): InviteDto = db.transaction {
        val now = clock()
        val code = (1..INVITE_LENGTH).map { INVITE_ALPHABET[random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")
        val expires = now + validDays * DAY_MS
        update("INSERT INTO invites (code, created_by, created_at, expires_at) VALUES (?, ?, ?, ?)", code, adminId, now, expires)
        InviteDto(code, now, expires)
    }

    fun listInvites(): List<InviteDto> = db.transaction {
        query(
            """SELECT i.code, i.created_at, i.expires_at, u.username FROM invites i
               LEFT JOIN users u ON u.id = i.used_by ORDER BY i.created_at DESC""",
        ) { InviteDto(it.getString(1), it.getLong(2), it.getLong(3), it.getString(4)) }
    }

    fun deleteInvite(code: String) = db.transaction {
        if (update("DELETE FROM invites WHERE code = ?", code.uppercase()) == 0) {
            throw ApiException(HttpStatusCode.NotFound, "not_found", "Приглашение не найдено")
        }
    }

    // ---------- Токены обновления ----------

    /** Выдаёт новый токен обновления; в базе хранится только его хеш. */
    fun issueRefreshToken(userId: Long, deviceName: String): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val now = clock()
        db.transaction {
            insert(
                "INSERT INTO refresh_tokens (user_id, token_hash, device_name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                userId, sha256(token), deviceName.take(100), now, now + REFRESH_TTL_MS,
            )
        }
        return token
    }

    /**
     * Обмен токена обновления на новый (ротация): старый удаляется,
     * поэтому украденный и уже использованный токен бесполезен.
     */
    fun rotateRefreshToken(token: String): Pair<User, String> {
        val (userId, device) = db.transaction {
            val row = queryOne("SELECT id, user_id, device_name, expires_at FROM refresh_tokens WHERE token_hash = ?", sha256(token)) {
                listOf(it.getLong(1), it.getLong(2), it.getString(3), it.getLong(4))
            } ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Сессия завершена — войдите заново")
            update("DELETE FROM refresh_tokens WHERE id = ?", row[0])
            if ((row[3] as Long) < clock()) {
                throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Сессия истекла — войдите заново")
            }
            row[1] as Long to row[2] as String
        }
        val user = findById(userId) ?: throw ApiException(HttpStatusCode.Unauthorized, "invalid_refresh_token", "Аккаунт удалён")
        return user to issueRefreshToken(userId, device)
    }

    fun revokeRefreshToken(token: String) = db.transaction {
        update("DELETE FROM refresh_tokens WHERE token_hash = ?", sha256(token))
    }

    // ---------- Внутреннее ----------

    private fun Connection.createUser(username: String, password: String, isAdmin: Boolean): User {
        val name = username.trim()
        if (!USERNAME_REGEX.matches(name)) {
            throw ApiException(HttpStatusCode.BadRequest, "invalid_username", "Имя: 3–32 символа — буквы, цифры, «.», «_» или «-»")
        }
        validatePassword(password)
        if (findByName(name) != null) throw ApiException(HttpStatusCode.Conflict, "username_taken", "Это имя уже занято")
        val now = clock()
        val id = insert(
            "INSERT INTO users (username, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?)",
            name, hash(password), isAdmin, now,
        )
        return User(id, name, "", isAdmin, now)
    }

    private fun validatePassword(password: String) {
        if (password.length < MIN_PASSWORD) {
            throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Пароль должен быть не короче $MIN_PASSWORD символов")
        }
        if (password.length > 72) {
            // bcrypt учитывает только первые 72 байта
            throw ApiException(HttpStatusCode.BadRequest, "weak_password", "Пароль слишком длинный (максимум 72 символа)")
        }
    }

    private fun Connection.findByName(name: String): User? =
        queryOne("SELECT * FROM users WHERE username = ?", name.trim(), map = ::toUser)

    private fun Connection.userCount(): Long = queryOne("SELECT COUNT(*) FROM users") { it.getLong(1) } ?: 0

    private fun toUser(rs: ResultSet) = User(
        id = rs.getLong("id"),
        username = rs.getString("username"),
        passwordHash = rs.getString("password_hash"),
        isAdmin = rs.getInt("is_admin") == 1,
        createdAt = rs.getLong("created_at"),
    )

    private fun hash(password: String) = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val MIN_PASSWORD = 8
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val REFRESH_TTL_MS = 60 * DAY_MS
        private const val BCRYPT_COST = 12
        private const val INVITE_LENGTH = 8
        // Без похожих символов (0/O, 1/I/L), чтобы код можно было продиктовать
        private const val INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val USERNAME_REGEX = Regex("""[\p{L}\p{N}._-]{3,32}""")
        private val DUMMY_HASH = BCrypt.withDefaults().hashToString(BCRYPT_COST, "dummy-password".toCharArray())
    }
}
