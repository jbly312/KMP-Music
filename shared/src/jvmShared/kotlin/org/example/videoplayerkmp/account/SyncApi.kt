package org.example.videoplayerkmp.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.example.videoplayerkmp.sync.ApiError
import org.example.videoplayerkmp.sync.AuthResponse
import org.example.videoplayerkmp.sync.InviteDto
import org.example.videoplayerkmp.sync.LoginRequest
import org.example.videoplayerkmp.sync.PullResponse
import org.example.videoplayerkmp.sync.PushRequest
import org.example.videoplayerkmp.sync.PushResponse
import org.example.videoplayerkmp.sync.RefreshRequest
import org.example.videoplayerkmp.sync.RegisterRequest
import org.example.videoplayerkmp.sync.ServerInfo
import org.example.videoplayerkmp.sync.SetupRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/** Ответ сервера с ошибкой: [code] — машинный код из [ApiError], message — текст для человека. */
class ServerException(val status: Int, val code: String, message: String) : Exception(message)

/** Запросы к серверу синхронизации KMP Music (протокол — в модуле sync-protocol). */
class SyncApi(serverUrl: String) {
    val baseUrl: String = normalizeServerUrl(serverUrl)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun info(): ServerInfo = call("GET", "/api/info", ServerInfo.serializer())

    suspend fun setup(request: SetupRequest): AuthResponse =
        call("POST", "/api/auth/setup", AuthResponse.serializer(), json.encodeToString(SetupRequest.serializer(), request))

    suspend fun register(request: RegisterRequest): AuthResponse =
        call("POST", "/api/auth/register", AuthResponse.serializer(), json.encodeToString(RegisterRequest.serializer(), request))

    suspend fun login(request: LoginRequest): AuthResponse =
        call("POST", "/api/auth/login", AuthResponse.serializer(), json.encodeToString(LoginRequest.serializer(), request))

    suspend fun refresh(refreshToken: String): AuthResponse =
        call("POST", "/api/auth/refresh", AuthResponse.serializer(), json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)))

    suspend fun logout(refreshToken: String) {
        call<Unit?>("POST", "/api/auth/logout", null, json.encodeToString(RefreshRequest.serializer(), RefreshRequest(refreshToken)))
    }

    suspend fun push(access: String, request: PushRequest): PushResponse =
        call("POST", "/api/sync/push", PushResponse.serializer(), json.encodeToString(PushRequest.serializer(), request), access)

    suspend fun pull(access: String, since: Long, limit: Int = 500): PullResponse =
        call("GET", "/api/sync/changes?since=$since&limit=$limit", PullResponse.serializer(), access = access)

    suspend fun invites(access: String): List<InviteDto> =
        call("GET", "/api/invites", ListSerializer(InviteDto.serializer()), access = access)

    suspend fun createInvite(access: String, days: Int = 7): InviteDto =
        call("POST", "/api/invites?days=$days", InviteDto.serializer(), access = access)

    /**
     * Обрыв соединения повторяем пару раз: через Funnel первое TLS-рукопожатие после простоя
     * иногда срывается (это быстрые обрывы, а не тайм-ауты). Ошибки сервера (4xx/5xx) не повторяем — их разбирает вызывающий.
     */
    private suspend fun <T> call(
        method: String,
        path: String,
        serializer: KSerializer<T>?,
        body: String? = null,
        access: String? = null,
    ): T = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                @Suppress("UNCHECKED_CAST")
                return@withContext request(method, path, serializer, body, access) as T
            } catch (e: IOException) {
                // Сервер молчит — повтор только заставит ждать втрое дольше
                if (e is SocketTimeoutException || ++attempt >= 3) throw e
                delay(500L * attempt)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun <T> request(method: String, path: String, serializer: KSerializer<T>?, body: String?, access: String?): T? {
        val connection = URI(baseUrl + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            if (access != null) connection.setRequestProperty("Authorization", "Bearer $access")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.encodeToByteArray()) }
            }
            val status = connection.responseCode
            val text = (if (status < 400) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes().decodeToString() }.orEmpty()
            if (status >= 400) {
                val error = runCatching { json.decodeFromString(ApiError.serializer(), text) }.getOrNull()
                throw ServerException(status, error?.code ?: "http_$status", error?.message ?: "Сервер ответил ошибкой $status")
            }
            if (serializer == null || text.isEmpty()) return null
            return json.decodeFromString(serializer, text)
        } finally {
            connection.disconnect()
        }
    }
}

/** «music.example.com» → «https://music.example.com», без «/» в конце. */
fun normalizeServerUrl(url: String): String {
    val trimmed = url.trim().trimEnd('/')
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}
