package org.example.videoplayerkmp.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.videoplayerkmp.sync.Change
import org.example.videoplayerkmp.sync.EntityKind
import org.example.videoplayerkmp.sync.FavoriteData
import org.example.videoplayerkmp.sync.HistoryData
import org.example.videoplayerkmp.sync.PlaylistData
import org.example.videoplayerkmp.sync.PlaylistItemData
import org.example.videoplayerkmp.sync.SyncTrack
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    /** Свой порядок треков; сортировка [sort] его не меняет, а только показывает иначе */
    val tracks: List<Track> = emptyList(),
    val sort: TrackSort = TrackSort.CUSTOM,
) {
    /** Треки в том порядке, в каком их видно и в каком они играют. */
    val displayedTracks: List<Track> get() = sort.apply(tracks)
}

@Serializable
enum class TrackSort(val title: String) {
    CUSTOM("Свой порядок"),
    TITLE("Название"),
    ARTIST("Исполнитель"),
    DURATION("Длительность");

    fun apply(tracks: List<Track>): List<Track> = when (this) {
        CUSTOM -> tracks
        TITLE -> tracks.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        ARTIST -> tracks.sortedWith(
            compareBy<Track, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
        DURATION -> tracks.sortedBy { it.durationSec }
    }
}

/** Библиотека в том виде, в каком её показывает интерфейс. */
@Serializable
data class LibraryData(
    val favorites: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<Track> = emptyList(),
    val volume: Float = 0.7f,
)

/** Запись библиотеки с временем последнего изменения; [value] = null — запись удалена. */
@Serializable
internal data class Entry<T>(val value: T? = null, val updatedAt: Long)

/**
 * Что хранится на диске: записи библиотеки по видам (как на сервере синхронизации),
 * ключи ещё не отправленных изменений и ревизия, до которой забраны изменения с сервера.
 */
@Serializable
internal data class LibraryStore(
    val favorites: Map<String, Entry<FavoriteData>> = emptyMap(),
    val playlists: Map<String, Entry<PlaylistData>> = emptyMap(),
    val items: Map<String, Entry<PlaylistItemData>> = emptyMap(),
    val history: Map<String, Entry<HistoryData>> = emptyMap(),
    /** «вид/id» изменённых записей, которые сервер ещё не получил */
    val dirty: Set<String> = emptySet(),
    val revision: Long = 0,
    /** Громкость своя у каждого устройства и не синхронизируется */
    val volume: Float = 0.7f,
)

/**
 * Пользовательская библиотека.
 *
 * Хранится набором записей с временем изменения — так её можно синхронизировать между
 * устройствами по правилу «побеждает последнее изменение». Удаление тоже запись (без данных),
 * иначе удалённое на одном устройстве вернулось бы с другого.
 */
class Library(directory: File) {
    private val file = File(directory, "library.v2.json")
    private val legacyFile = File(directory, "library.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val lock = Any()
    private val _store = MutableStateFlow(load())
    private val _data = MutableStateFlow(_store.value.toLibraryData())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    /** Всё состояние вместе с очередью на отправку — для синхронизации. */
    internal val store: StateFlow<LibraryStore> = _store.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // Библиотеку, перенесённую из файла прежней версии, сохраняем сразу
            if (!file.exists()) save(_store.value)
            _store.drop(1).debounce(500).collect { save(it) }
        }
    }

    fun flush() = save(_store.value)

    fun toggleFavorite(track: Track) = edit { s ->
        val now = System.currentTimeMillis()
        if (s.favorites[track.id]?.value != null) s.copy(favorites = s.favorites.removed(track.id, now))
        else s.copy(favorites = s.favorites.put(track.id, FavoriteData(track.toSync(), now), now))
    }

    fun addToHistory(track: Track) = edit { s ->
        val now = System.currentTimeMillis()
        var history = s.history.put(track.id, HistoryData(track.toSync(), now), now)
        // Старые прослушивания сверх лимита удаляем — и на других устройствах тоже
        history.live().sortedByDescending { it.second.playedAt }.drop(HISTORY_LIMIT).forEach { (id, _) ->
            history = history.removed(id, now)
        }
        s.copy(history = history)
    }

    fun clearHistory() = edit { s ->
        val now = System.currentTimeMillis()
        s.copy(history = s.history.live().fold(s.history) { acc, (id, _) -> acc.removed(id, now) })
    }

    /** Отмена очистки истории: возвращаем старые записи после тех, что появились с тех пор. */
    fun restoreHistory(tracks: List<Track>) = edit { s ->
        val now = System.currentTimeMillis()
        val oldest = s.history.live().minOfOrNull { it.second.playedAt } ?: now
        var history = s.history
        tracks.filter { history[it.id]?.value == null }.take(HISTORY_LIMIT).forEachIndexed { i, track ->
            history = history.put(track.id, HistoryData(track.toSync(), oldest - 1 - i), now)
        }
        s.copy(history = history)
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val id = UUID.randomUUID().toString()
        edit { s ->
            val now = System.currentTimeMillis()
            val unique = tracks.distinctBy { it.id }
            var items = s.items
            unique.zip(Positions.sequence(unique.size)).forEach { (track, position) ->
                items = items.put(itemId(id, track.id), PlaylistItemData(id, track.toSync(), position), now)
            }
            s.copy(playlists = s.playlists.put(id, PlaylistData(name.trim(), now), now), items = items)
        }
        return data.value.playlists.first { it.id == id }
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name.trim()) }

    fun setPlaylistSort(id: String, sort: TrackSort) = updatePlaylist(id) { it.copy(sort = sort.name) }

    fun deletePlaylist(id: String) = edit { s ->
        val now = System.currentTimeMillis()
        val items = s.items.live().filter { it.second.playlistId == id }.fold(s.items) { acc, (itemId, _) -> acc.removed(itemId, now) }
        s.copy(playlists = s.playlists.removed(id, now), items = items)
    }

    fun addToPlaylist(id: String, track: Track) = edit { s ->
        val order = s.playlistItems(id)
        if (order.any { it.track.id == track.id }) return@edit s
        s.withItem(id, track, Positions.between(order.lastOrNull()?.position, null))
    }

    fun insertIntoPlaylist(id: String, index: Int, track: Track) = edit { s ->
        val order = s.playlistItems(id).filterNot { it.track.id == track.id }
        val at = index.coerceIn(0, order.size)
        val position = positionBetween(order.getOrNull(at - 1)?.position, order.getOrNull(at)?.position)
            ?: return@edit s.renumbered(id, order.map { it.track.toTrack() }.toMutableList().apply { add(at, track) })
        s.withItem(id, track, position)
    }

    /**
     * Удаляет трек по id, а не по номеру строки: при сортировке номер на экране
     * не совпадает с позицией в плейлисте. Возвращает прежнюю позицию — для «Отменить».
     */
    fun removeFromPlaylist(id: String, trackId: String): Int {
        val index = data.value.playlists.find { it.id == id }?.tracks?.indexOfFirst { it.id == trackId } ?: -1
        if (index >= 0) edit { s -> s.copy(items = s.items.removed(itemId(id, trackId), System.currentTimeMillis())) }
        return index
    }

    /** Перенос меняет позицию только одного трека — между его новыми соседями. */
    fun moveInPlaylist(id: String, from: Int, to: Int) = edit { s ->
        val order = s.playlistItems(id)
        if (from !in order.indices || to !in order.indices || from == to) return@edit s
        val moved = order[from]
        val rest = order.toMutableList().apply { removeAt(from) }
        val position = positionBetween(rest.getOrNull(to - 1)?.position, rest.getOrNull(to)?.position)
            ?: return@edit s.renumbered(id, rest.map { it.track.toTrack() }.toMutableList().apply { add(to, moved.track.toTrack()) })
        s.withItem(id, moved.track.toTrack(), position)
    }

    fun setVolume(volume: Float) = synchronized(lock) {
        // Громкость не синхронизируется: меняем без отметки об изменении
        _store.value = _store.value.copy(volume = volume)
        _data.value = _data.value.copy(volume = volume)
    }

    // ---------- Синхронизация ----------

    /** Изменения, которые ещё не ушли на сервер. */
    internal fun pendingChanges(limit: Int): List<Change> {
        val s = _store.value
        return s.dirty.asSequence().mapNotNull { key -> s.change(key) }.take(limit).toList()
    }

    /** Сервер принял изменения: отметку снимаем, если запись с тех пор не менялась ещё раз. */
    internal fun markPushed(changes: List<Change>) = synchronized(lock) {
        val s = _store.value
        val sent = changes.filter { s.change(keyOf(it.kind, it.id))?.updatedAt == it.updatedAt }
            .map { keyOf(it.kind, it.id) }.toSet()
        if (sent.isNotEmpty()) _store.value = s.copy(dirty = s.dirty - sent)
    }

    /**
     * Изменения с сервера. Сервер уже выбрал победителя, поэтому его запись заменяет нашу,
     * если только у нас нет более свежей правки — она уйдёт на сервер при следующей отправке.
     */
    internal fun applyRemote(changes: List<Change>, revision: Long) = synchronized(lock) {
        var s = _store.value
        val accepted = mutableSetOf<String>()
        for (change in changes) {
            val key = keyOf(change.kind, change.id)
            val local = s.change(key)
            if (local != null && local.updatedAt > change.updatedAt) continue
            s = when (change.kind) {
                EntityKind.FAVORITE -> s.copy(favorites = s.favorites.applied(change, FavoriteData.serializer()))
                EntityKind.PLAYLIST -> s.copy(playlists = s.playlists.applied(change, PlaylistData.serializer()))
                EntityKind.PLAYLIST_ITEM -> s.copy(items = s.items.applied(change, PlaylistItemData.serializer()))
                EntityKind.HISTORY -> s.copy(history = s.history.applied(change, HistoryData.serializer()))
                else -> continue
            }
            accepted += key
        }
        publish(s.copy(dirty = s.dirty - accepted, revision = revision))
    }

    /** Вход в аккаунт: забираем всё с начала и отправляем всё своё — сервер сам отбросит устаревшее. */
    internal fun resetSync() = synchronized(lock) {
        val s = _store.value
        val all = s.favorites.keys.map { keyOf(EntityKind.FAVORITE, it) } +
            s.playlists.keys.map { keyOf(EntityKind.PLAYLIST, it) } +
            s.items.keys.map { keyOf(EntityKind.PLAYLIST_ITEM, it) } +
            s.history.keys.map { keyOf(EntityKind.HISTORY, it) }
        _store.value = s.copy(dirty = all.toSet(), revision = 0)
    }

    // ---------- Внутреннее ----------

    /** Правка пользователя: все изменённые записи попадают в очередь на отправку. */
    private fun edit(transform: (LibraryStore) -> LibraryStore) {
        synchronized(lock) {
            val before = _store.value
            val after = transform(before)
            if (after === before) return
            publish(after.copy(dirty = after.dirty + changedKeys(before, after)))
        }
    }

    private fun publish(store: LibraryStore) {
        _store.value = store
        _data.value = store.toLibraryData()
    }

    private fun updatePlaylist(id: String, transform: (PlaylistData) -> PlaylistData) = edit { s ->
        val current = s.playlists[id]?.value ?: return@edit s
        s.copy(playlists = s.playlists.put(id, transform(current), System.currentTimeMillis()))
    }

    private fun load(): LibraryStore {
        if (file.exists()) {
            try {
                return json.decodeFromString(LibraryStore.serializer(), file.readText())
            } catch (e: Exception) {
                // Не теряем данные пользователя: откладываем повреждённый файл в сторону
                file.copyTo(File(file.parentFile, "library.v2.broken.json"), overwrite = true)
            }
        }
        // Файл прежней версии остаётся на месте как резервная копия
        if (legacyFile.exists()) {
            runCatching { return migrate(json.decodeFromString(LibraryData.serializer(), legacyFile.readText())) }
        }
        return LibraryStore()
    }

    @Synchronized
    private fun save(store: LibraryStore) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(LibraryStore.serializer(), store))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

private const val HISTORY_LIMIT = 200

// ---------- Преобразования ----------

private fun keyOf(kind: String, id: String) = "$kind/$id"

private fun itemId(playlistId: String, trackId: String) = "$playlistId:$trackId"

internal fun Track.toSync() = SyncTrack(id, title, artist, durationSec, thumbnailUrl)

internal fun SyncTrack.toTrack() = Track(id, title, artist, durationSec, thumbnailUrl)

private fun <T> Map<String, Entry<T>>.live(): List<Pair<String, T>> = mapNotNull { (id, e) -> e.value?.let { id to it } }

/** Время изменения всегда растёт: новая правка побеждает всё, что это устройство уже видело. */
private fun <T> Map<String, Entry<T>>.put(id: String, value: T, now: Long): Map<String, Entry<T>> =
    this + (id to Entry(value, maxOf(now, (this[id]?.updatedAt ?: 0) + 1)))

private fun <T> Map<String, Entry<T>>.removed(id: String, now: Long): Map<String, Entry<T>> {
    val existing = this[id] ?: return this
    if (existing.value == null) return this
    return this + (id to Entry<T>(null, maxOf(now, existing.updatedAt + 1)))
}

private val syncJson = Json { ignoreUnknownKeys = true }

private fun <T> Map<String, Entry<T>>.applied(change: Change, serializer: KSerializer<T>): Map<String, Entry<T>> {
    val value = if (change.deleted) null else {
        // Запись, которую не удалось прочитать (например, от более новой версии), пропускаем
        runCatching { syncJson.decodeFromJsonElement(serializer, change.data!!) }.getOrNull() ?: return this
    }
    return this + (change.id to Entry(value, change.updatedAt))
}

private fun <T> Entry<T>.toChange(kind: String, id: String, serializer: KSerializer<T>) = Change(
    kind = kind,
    id = id,
    data = value?.let { syncJson.encodeToJsonElement(serializer, it) },
    updatedAt = updatedAt,
    deleted = value == null,
)

private fun LibraryStore.change(key: String): Change? {
    val kind = key.substringBefore('/')
    val id = key.substringAfter('/')
    return when (kind) {
        EntityKind.FAVORITE -> favorites[id]?.toChange(kind, id, FavoriteData.serializer())
        EntityKind.PLAYLIST -> playlists[id]?.toChange(kind, id, PlaylistData.serializer())
        EntityKind.PLAYLIST_ITEM -> items[id]?.toChange(kind, id, PlaylistItemData.serializer())
        EntityKind.HISTORY -> history[id]?.toChange(kind, id, HistoryData.serializer())
        else -> null
    }
}

private fun changedKeys(before: LibraryStore, after: LibraryStore): Set<String> {
    fun <T> diff(kind: String, old: Map<String, Entry<T>>, new: Map<String, Entry<T>>) =
        if (old === new) emptyList() else new.filter { (id, e) -> old[id] != e }.keys.map { keyOf(kind, it) }
    return (diff(EntityKind.FAVORITE, before.favorites, after.favorites) +
        diff(EntityKind.PLAYLIST, before.playlists, after.playlists) +
        diff(EntityKind.PLAYLIST_ITEM, before.items, after.items) +
        diff(EntityKind.HISTORY, before.history, after.history)).toSet()
}

/** Треки плейлиста в своём порядке. При одинаковых позициях (правки с двух устройств) — по id. */
private fun LibraryStore.playlistItems(playlistId: String): List<PlaylistItemData> =
    items.values.mapNotNull { it.value }.filter { it.playlistId == playlistId }.sortedWith(itemOrder)

private val itemOrder = compareBy<PlaylistItemData>({ it.position }, { it.track.id })

private fun LibraryStore.withItem(playlistId: String, track: Track, position: String) =
    copy(items = items.put(itemId(playlistId, track.id), PlaylistItemData(playlistId, track.toSync(), position), System.currentTimeMillis()))

/** null — соседи стоят на одной позиции и между ними ничего не вставить. */
private fun positionBetween(before: String?, after: String?): String? =
    if (before != null && after != null && before >= after) null else Positions.between(before, after)

/** Запасной путь: заново раздать позиции всему плейлисту в заданном порядке. */
private fun LibraryStore.renumbered(playlistId: String, order: List<Track>): LibraryStore {
    val now = System.currentTimeMillis()
    var result = items
    order.distinctBy { it.id }.let { tracks -> tracks.zip(Positions.sequence(tracks.size)) }.forEach { (track, position) ->
        result = result.put(itemId(playlistId, track.id), PlaylistItemData(playlistId, track.toSync(), position), now)
    }
    return copy(items = result)
}

private fun LibraryStore.toLibraryData(): LibraryData {
    val byPlaylist = items.values.mapNotNull { it.value }.groupBy { it.playlistId }
    return LibraryData(
        favorites = favorites.values.mapNotNull { it.value }.sortedByDescending { it.addedAt }.map { it.track.toTrack() },
        playlists = playlists.live().sortedBy { it.second.createdAt }.map { (id, p) ->
            Playlist(
                id = id,
                name = p.name,
                tracks = byPlaylist[id].orEmpty().sortedWith(itemOrder).map { it.track.toTrack() },
                sort = TrackSort.entries.find { it.name == p.sort } ?: TrackSort.CUSTOM,
            )
        },
        history = history.values.mapNotNull { it.value }.sortedByDescending { it.playedAt }.take(HISTORY_LIMIT).map { it.track.toTrack() },
        volume = volume,
    )
}

/** Библиотека из файла прежней версии: каждый трек, плейлист и прослушивание — отдельная запись. */
private fun migrate(old: LibraryData): LibraryStore {
    val now = System.currentTimeMillis()
    val favorites = old.favorites.distinctBy { it.id }.mapIndexed { i, track ->
        // Порядок сохраняем через время добавления: первый в списке — самый свежий
        track.id to Entry(FavoriteData(track.toSync(), now - i), now)
    }.toMap()
    val history = old.history.distinctBy { it.id }.mapIndexed { i, track ->
        track.id to Entry(HistoryData(track.toSync(), now - i), now)
    }.toMap()
    val playlists = old.playlists.mapIndexed { i, p -> p.id to Entry(PlaylistData(p.name, now - old.playlists.size + i, p.sort.name), now) }.toMap()
    val items = old.playlists.flatMap { p ->
        val tracks = p.tracks.distinctBy { it.id }
        tracks.zip(Positions.sequence(tracks.size)).map { (track, position) ->
            itemId(p.id, track.id) to Entry(PlaylistItemData(p.id, track.toSync(), position), now)
        }
    }.toMap()
    val store = LibraryStore(favorites, playlists, items, history, volume = old.volume)
    val dirty = favorites.keys.map { keyOf(EntityKind.FAVORITE, it) } + playlists.keys.map { keyOf(EntityKind.PLAYLIST, it) } +
        items.keys.map { keyOf(EntityKind.PLAYLIST_ITEM, it) } + history.keys.map { keyOf(EntityKind.HISTORY, it) }
    return store.copy(dirty = dirty.toSet())
}
