package org.example.videoplayerkmp.data

import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryTest {
    private fun track(n: Int) = Track("video${n.toString().padStart(6, '0')}", "Песня $n", "Исполнитель $n", 180L + n)

    @Test
    fun positionsStayOrdered() {
        val random = Random(42)
        val list = Positions.sequence(5).toMutableList()
        assertEquals(list.sorted(), list)
        repeat(2_000) {
            val at = random.nextInt(list.size + 1)
            val position = Positions.between(list.getOrNull(at - 1), list.getOrNull(at))
            list.add(at, position)
        }
        assertEquals(list.sorted(), list)
        assertEquals(list.size, list.toSet().size)
        assertTrue(list.none { it.endsWith('0') })
        // Добавление в конец не раздувает позиции
        var last = list.last()
        repeat(300) { last = Positions.between(last, null) }
        assertTrue(last.length <= 12, last)
    }

    @Test
    fun migratesLegacyFile() {
        val dir = Files.createTempDirectory("kmp-library").toFile()
        val legacy = LibraryData(
            favorites = listOf(track(1), track(2)),
            playlists = listOf(Playlist("p1", "Первый", listOf(track(3), track(1), track(3), track(4)), TrackSort.ARTIST)),
            history = listOf(track(5), track(6)),
            volume = 0.4f,
        )
        dir.resolve("library.json").writeText(kotlinx.serialization.json.Json.encodeToString(LibraryData.serializer(), legacy))

        val library = Library(dir)
        // Повтор трека в плейлисте схлопывается: один трек — одна запись
        assertEquals(legacy.copy(playlists = listOf(legacy.playlists[0].copy(tracks = listOf(track(3), track(1), track(4))))), library.data.value)
        assertTrue(library.pendingChanges(1000).size == 2 + 1 + 3 + 2, "всё старое уйдёт на сервер при первом входе")
        dir.deleteRecursively()
    }

    @Test
    fun editsKeepOrderAndUndo() {
        val dir = Files.createTempDirectory("kmp-library").toFile()
        val library = Library(dir)
        val p = library.createPlaylist("П", (1..5).map(::track))
        library.moveInPlaylist(p.id, 0, 4)
        library.moveInPlaylist(p.id, 3, 1)
        val expected = listOf(2, 5, 3, 4, 1).map(::track)
        assertEquals(expected, library.data.value.playlists.single().tracks)

        val index = library.removeFromPlaylist(p.id, track(3).id)
        library.insertIntoPlaylist(p.id, index, track(3))
        assertEquals(expected, library.data.value.playlists.single().tracks)

        library.addToHistory(track(1))
        library.addToHistory(track(2))
        library.addToHistory(track(1))
        val history = library.data.value.history
        assertEquals(listOf(track(1), track(2)), history)
        library.clearHistory()
        library.addToHistory(track(9))
        library.restoreHistory(history)
        assertEquals(listOf(track(9), track(1), track(2)), library.data.value.history)
        dir.deleteRecursively()
    }
}
