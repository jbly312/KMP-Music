package org.example.videoplayerkmp.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.sync.AuthResponse
import org.example.videoplayerkmp.sync.InviteDto
import org.example.videoplayerkmp.sync.LoginRequest
import org.example.videoplayerkmp.sync.PushRequest
import org.example.videoplayerkmp.sync.RegisterRequest
import org.example.videoplayerkmp.sync.SetupRequest
import org.example.videoplayerkmp.sync.UserDto
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Вход на сервер синхронизации; хранится на устройстве, пока не нажмут «Выйти». */
@Serializable
data class Account(
    val serverUrl: String,
    val serverName: String,
    val user: UserDto,
    /** Одноразовый: сервер меняет его при каждом обновлении сессии */
    val refreshToken: String,
)

data class SyncStatus(
    val syncing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
)

/**
 * Синхронизация библиотеки с сервером KMP Music.
 *
 * Работает в фоне, пока открыт аккаунт: после правок в библиотеке, раз в минуту и по [syncNow].
 * Сначала отправляет свои изменения, потом забирает чужие. Без связи библиотека работает как
 * обычно — изменения копятся и уйдут, когда сервер снова станет доступен.
 */
class SyncManager(private val library: Library, directory: File, private val scope: CoroutineScope) {
    private val file = File(directory, "account.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _account = MutableStateFlow(loadAccount())
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Сколько изменений ещё не дошло до сервера. */
    val pending: StateFlow<Int> = library.store.map { it.dirty.size }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, library.store.value.dirty.size)

    private val mutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var accessToken: String? = null
    private var accessExpiresAt = 0L

    init {
        scope.launch(Dispatchers.IO) { loop() }
        // Правка в библиотеке — повод синхронизироваться, но не на каждое нажатие
        @OptIn(FlowPreview::class)
        scope.launch { pending.debounce(1_500).collect { if (it > 0) wake.trySend(Unit) } }
        wake.trySend(Unit)
    }

    fun syncNow() {
        wake.trySend(Unit)
    }

    /** Проверка адреса: сервер отвечает и говорит, нужен ли первый (администраторский) аккаунт. */
    suspend fun serverInfo(url: String) = SyncApi(url).info()

    suspend fun login(url: String, username: String, password: String) {
        val api = SyncApi(url)
        startSession(api, api.login(LoginRequest(username.trim(), password, deviceName())))
    }

    suspend fun register(url: String, username: String, password: String, inviteCode: String) {
        val api = SyncApi(url)
        startSession(api, api.register(RegisterRequest(username.trim(), password, inviteCode.trim(), deviceName())))
    }

    /** Первый вход на новый сервер: этот аккаунт станет администратором. */
    suspend fun setup(url: String, username: String, password: String) {
        val api = SyncApi(url)
        startSession(api, api.setup(SetupRequest(username.trim(), password, deviceName())))
    }

    /** Выход: библиотека на устройстве остаётся, сессия на сервере закрывается. */
    fun logout() {
        scope.launch(Dispatchers.IO) {
            val account = mutex.withLock {
                val account = _account.value ?: return@launch
                setAccount(null)
                _status.value = SyncStatus()
                account
            }
            runCatching { SyncApi(account.serverUrl).logout(account.refreshToken) }
        }
    }

    suspend fun invites(): List<InviteDto> = withSession { api, token -> api.invites(token) }

    suspend fun createInvite(): InviteDto = withSession { api, token -> api.createInvite(token) }

    // ---------- Внутреннее ----------

    private suspend fun startSession(api: SyncApi, auth: AuthResponse) {
        val name = runCatching { api.info().name }.getOrDefault(api.baseUrl)
        mutex.withLock {
            setAccount(Account(api.baseUrl, name, auth.user, auth.refreshToken))
            rememberAccess(auth)
            // Новый аккаунт: забираем его библиотеку целиком и отправляем всё своё
            library.resetSync()
            _status.value = SyncStatus()
        }
        wake.trySend(Unit)
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            // После ошибки пробуем снова через 5, 10, 20, 40 с, дальше — раз в минуту
            val wait = if (failures == 0) PERIOD_MS else minOf(PERIOD_MS, 5_000L shl (failures - 1).coerceAtMost(4))
            withTimeoutOrNull(wait) { wake.receive() }
            if (_account.value == null) {
                failures = 0
                continue
            }
            failures = if (syncOnce()) 0 else failures + 1
        }
    }

    /** true — синхронизация прошла (или повторять бессмысленно), false — попробовать позже. */
    private suspend fun syncOnce(): Boolean = mutex.withLock {
        val account = _account.value ?: return true
        val api = SyncApi(account.serverUrl)
        _status.update { it.copy(syncing = true) }
        try {
            while (true) {
                val batch = library.pendingChanges(BATCH)
                if (batch.isEmpty()) break
                authorized(api) { api.push(it, PushRequest(batch)) }
                library.markPushed(batch)
                if (batch.size < BATCH) break
            }
            do {
                val page = authorized(api) { api.pull(it, library.store.value.revision, BATCH) }
                library.applyRemote(page.changes, page.revision)
            } while (page.hasMore)
            _status.value = SyncStatus(lastSyncAt = System.currentTimeMillis())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // Вход отозвали на сервере или аккаунт удалён — без нового входа пробовать нет смысла
            setAccount(null)
            _status.value = SyncStatus(error = e.message)
            true
        } catch (e: Exception) {
            _status.value = SyncStatus(lastSyncAt = _status.value.lastSyncAt, error = describeError(e))
            false
        }
    }

    private suspend fun <T> withSession(block: suspend (SyncApi, String) -> T): T = mutex.withLock {
        val account = _account.value ?: throw IllegalStateException("Сначала войдите в аккаунт")
        val api = SyncApi(account.serverUrl)
        authorized(api) { block(api, it) }
    }

    /** Запрос с токеном доступа; истёкший токен обновляем и повторяем запрос один раз. */
    private suspend fun <T> authorized(api: SyncApi, block: suspend (String) -> T): T {
        val token = accessToken?.takeIf { System.currentTimeMillis() < accessExpiresAt } ?: refreshAccess(api)
        return try {
            block(token)
        } catch (e: ServerException) {
            if (e.status != 401) throw e
            block(refreshAccess(api))
        }
    }

    private suspend fun refreshAccess(api: SyncApi): String {
        val account = _account.value ?: throw SessionExpiredException("Сначала войдите в аккаунт")
        val auth = try {
            api.refresh(account.refreshToken)
        } catch (e: ServerException) {
            if (e.status == 401) throw SessionExpiredException(e.message ?: "Сессия завершена — войдите заново")
            throw e
        }
        // Старый токен обновления больше не действует — новый сохраняем сразу
        setAccount(account.copy(user = auth.user, refreshToken = auth.refreshToken))
        rememberAccess(auth)
        return auth.accessToken
    }

    private fun rememberAccess(auth: AuthResponse) {
        accessToken = auth.accessToken
        // Обновляем чуть заранее, чтобы токен не истёк посреди запроса
        accessExpiresAt = System.currentTimeMillis() + (auth.expiresIn - 60).coerceAtLeast(30) * 1000
    }

    private fun setAccount(account: Account?) {
        _account.value = account
        if (account == null) {
            accessToken = null
            file.delete()
        } else {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Account.serializer(), account))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    private fun loadAccount(): Account? =
        runCatching { json.decodeFromString(Account.serializer(), file.readText()) }.getOrNull()

    private class SessionExpiredException(message: String) : Exception(message)

    private companion object {
        const val PERIOD_MS = 60_000L
        const val BATCH = 500
    }
}

/** Понятное описание ошибки сети или сервера — для экрана аккаунта. */
fun describeError(e: Throwable): String = when (e) {
    is ServerException -> e.message ?: "Ошибка сервера"
    is UnknownHostException -> "Сервер не найден — проверьте адрес и интернет"
    is SocketTimeoutException -> "Сервер не отвечает"
    is SSLException -> "Не удалось установить защищённое соединение"
    is IOException -> "Нет связи с сервером"
    is kotlinx.serialization.SerializationException -> "По этому адресу не сервер KMP Music"
    else -> e.message ?: e.toString()
}
