package org.example.videoplayerkmp.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Протокол сервера синхронизации KMP Music. Все пути — под /api.
 *
 * Библиотека хранится как набор записей (Change): каждая запись — избранный трек, плейлист,
 * трек в плейлисте или прослушивание. При конфликте побеждает запись с бо́льшим updatedAt
 * (last-write-wins). Удаление — это запись с deleted = true, иначе удалённое на одном
 * устройстве «воскресало» бы при синхронизации с другим.
 */

const val API_VERSION = 1

/** Виды записей библиотеки. */
object EntityKind {
    /** id = id трека, data = [FavoriteData] */
    const val FAVORITE = "favorite"

    /** id = id плейлиста, data = [PlaylistData] */
    const val PLAYLIST = "playlist"

    /** id = "<id плейлиста>:<id трека>" — один трек в плейлисте один раз, data = [PlaylistItemData] */
    const val PLAYLIST_ITEM = "playlist_item"

    /** id = id трека: в истории хранится последнее прослушивание каждого трека, data = [HistoryData] */
    const val HISTORY = "history"

    /** id = имя настройки (например, "volume"), data = любое JSON-значение */
    const val SETTING = "setting"

    val all = setOf(FAVORITE, PLAYLIST, PLAYLIST_ITEM, HISTORY, SETTING)
}

// ---------- Данные записей ----------

@Serializable
data class SyncTrack(
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String? = null,
)

@Serializable
data class FavoriteData(val track: SyncTrack, val addedAt: Long)

/** [sort] — имя порядка показа (CUSTOM, TITLE, ARTIST, DURATION); свой порядок задают позиции треков. */
@Serializable
data class PlaylistData(val name: String, val createdAt: Long, val sort: String = "CUSTOM")

/**
 * [position] — дробный индекс: строка, которая сортируется между соседями.
 * Так два устройства могут одновременно добавлять треки, не перенумеровывая весь плейлист.
 */
@Serializable
data class PlaylistItemData(val playlistId: String, val track: SyncTrack, val position: String)

@Serializable
data class HistoryData(val track: SyncTrack, val playedAt: Long)

// ---------- Синхронизация ----------

@Serializable
data class Change(
    val kind: String,
    val id: String,
    /** null у удалённых записей */
    val data: JsonElement? = null,
    /** Время изменения на устройстве, мс с эпохи — по нему решаются конфликты */
    val updatedAt: Long,
    val deleted: Boolean = false,
    /** Заполняет сервер: номер ревизии, в которой запись изменилась последний раз */
    val revision: Long = 0,
)

/** POST /api/sync/push */
@Serializable
data class PushRequest(val changes: List<Change>)

@Serializable
data class PushResponse(
    /** Текущая ревизия библиотеки после применения */
    val revision: Long,
    /** Сколько записей применено (остальные оказались старее серверных) */
    val applied: Int,
)

/** GET /api/sync/changes?since=<ревизия>&limit=<n> */
@Serializable
data class PullResponse(
    val changes: List<Change>,
    /** Ревизия, с которой продолжать: передайте её как since в следующий раз */
    val revision: Long,
    val hasMore: Boolean,
)

// ---------- Аккаунты ----------

/** GET /api/info — без авторизации */
@Serializable
data class ServerInfo(
    val name: String,
    val apiVersion: Int = API_VERSION,
    /** true — на сервере ещё нет пользователей: первый аккаунт станет администратором */
    val needsSetup: Boolean,
)

/** POST /api/auth/setup — только пока needsSetup */
@Serializable
data class SetupRequest(val username: String, val password: String, val deviceName: String)

/** POST /api/auth/register */
@Serializable
data class RegisterRequest(val username: String, val password: String, val inviteCode: String, val deviceName: String)

/** POST /api/auth/login */
@Serializable
data class LoginRequest(val username: String, val password: String, val deviceName: String)

/** POST /api/auth/refresh и /api/auth/logout */
@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AuthResponse(
    val accessToken: String,
    /** Одноразовый: после обновления выдаётся новый, старый перестаёт работать */
    val refreshToken: String,
    /** Через сколько секунд истечёт accessToken */
    val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class UserDto(val id: Long, val username: String, val isAdmin: Boolean, val createdAt: Long)

/** POST /api/me/password */
@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

/** POST /api/invites — только администратор */
@Serializable
data class InviteDto(val code: String, val createdAt: Long, val expiresAt: Long, val usedBy: String? = null)

/** Тело любой ошибки */
@Serializable
data class ApiError(val code: String, val message: String)
