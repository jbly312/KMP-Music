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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

@Serializable
data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class LibraryData(
    val favorites: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<Track> = emptyList(),
    val volume: Float = 0.7f,
)

/** Пользовательская библиотека, сохраняется в JSON-файл. */
class Library(private val file: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = _data.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            _data.drop(1).debounce(500).collect { save(it) }
        }
    }

    fun flush() = save(_data.value)

    fun toggleFavorite(track: Track) = _data.update { d ->
        if (d.favorites.any { it.id == track.id }) d.copy(favorites = d.favorites.filterNot { it.id == track.id })
        else d.copy(favorites = listOf(track) + d.favorites)
    }

    fun addToHistory(track: Track) = _data.update { d ->
        d.copy(history = (listOf(track) + d.history.filterNot { it.id == track.id }).take(HISTORY_LIMIT))
    }

    fun clearHistory() = _data.update { it.copy(history = emptyList()) }

    /** Отмена очистки истории: возвращаем старые записи после тех, что появились с тех пор. */
    fun restoreHistory(tracks: List<Track>) = _data.update { d ->
        d.copy(history = (d.history + tracks).distinctBy { it.id }.take(HISTORY_LIMIT))
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList()): Playlist {
        val playlist = Playlist(name = name.trim(), tracks = tracks)
        _data.update { it.copy(playlists = it.playlists + playlist) }
        return playlist
    }

    fun renamePlaylist(id: String, name: String) = updatePlaylist(id) { it.copy(name = name.trim()) }

    fun deletePlaylist(id: String) = _data.update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }

    fun addToPlaylist(id: String, track: Track) = updatePlaylist(id) { p ->
        if (p.tracks.any { it.id == track.id }) p else p.copy(tracks = p.tracks + track)
    }

    fun insertIntoPlaylist(id: String, index: Int, track: Track) = updatePlaylist(id) { p ->
        p.copy(tracks = p.tracks.toMutableList().apply { add(index.coerceIn(0, size), track) })
    }

    fun removeFromPlaylist(id: String, index: Int) = updatePlaylist(id) { p ->
        p.copy(tracks = p.tracks.filterIndexed { i, _ -> i != index })
    }

    fun setVolume(volume: Float) = _data.update { it.copy(volume = volume) }

    private fun updatePlaylist(id: String, transform: (Playlist) -> Playlist) = _data.update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) transform(it) else it })
    }

    private fun load(): LibraryData {
        if (!file.exists()) return LibraryData()
        return try {
            json.decodeFromString<LibraryData>(file.readText())
        } catch (e: Exception) {
            // Не теряем данные пользователя: откладываем повреждённый файл в сторону
            file.copyTo(File(file.parentFile, "library.broken.json"), overwrite = true)
            LibraryData()
        }
    }

    @Synchronized
    private fun save(data: LibraryData) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(LibraryData.serializer(), data))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private companion object {
        const val HISTORY_LIMIT = 200
    }
}
