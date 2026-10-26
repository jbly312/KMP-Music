package org.example.videoplayerkmp.account

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.TrackSort
import org.example.videoplayerkmp.server.Database
import org.example.videoplayerkmp.server.ServerConfig
import org.example.videoplayerkmp.server.module
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Два «устройства» с одним аккаунтом синхронизируются через настоящий сервер. */
class SyncTest {
    private val root = Files.createTempDirectory("kmp-sync-test").toFile()
    private val port = ServerSocket(0).use { it.localPort }
    private val url = "http://127.0.0.1:$port"
    private lateinit var db: Database
    private lateinit var server: EmbeddedServer<*, *>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Device(val library: Library, val sync: SyncManager)

    private fun device(name: String): Device {
        val dir = File(root, name).apply { mkdirs() }
        val library = Library(dir)
        return Device(library, SyncManager(library, dir, scope))
    }

    @BeforeTest
    fun start() {
        db = Database(File(root, "server.db"))
        val config = ServerConfig(name = "Тестовый", jwtSecret = "x".repeat(48))
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") { module(config, db) }.start(wait = false)
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        server.stop(100, 500)
        db.close()
        root.deleteRecursively()
    }

    /** Ждём, пока устройство отправит всё своё и заберёт чужое. */
    private suspend fun Device.synced() = withTimeout(15_000) {
        val since = System.currentTimeMillis()
        while (true) {
            sync.syncNow()
            delay(150)
            val status = sync.status.value
            if (!status.syncing && sync.pending.value == 0 && (status.lastSyncAt ?: 0) >= since) break
        }
    }

    private fun track(n: Int) = Track("video${n.toString().padStart(6, '0')}", "Песня $n", "Исполнитель $n", 180L + n)

    @Test
    fun twoDevicesConverge() = runBlocking {
        val pc = device("pc")
        val phone = device("phone")

        // На ПК библиотека была ещё до входа — она должна уехать на сервер
        pc.library.toggleFavorite(track(1))
        val mix = pc.library.createPlaylist("Микс", listOf(track(1), track(2), track(3)))
        pc.sync.setup(url, "sasha", "password123")
        assertEquals("Тестовый", pc.sync.account.value?.serverName)
        pc.synced()

        phone.sync.login(url, "sasha", "password123")
        phone.synced()
        assertEquals(pc.library.data.value.copy(volume = 0f), phone.library.data.value.copy(volume = 0f))

        // Правки на телефоне: перенос трека, удаление из избранного, история, сортировка
        phone.library.moveInPlaylist(mix.id, 2, 0)
        phone.library.toggleFavorite(track(1))
        phone.library.addToHistory(track(7))
        phone.library.setPlaylistSort(mix.id, TrackSort.TITLE)
        phone.synced()
        pc.synced()
        val onPc = pc.library.data.value
        assertEquals(listOf(track(3), track(1), track(2)), onPc.playlists.single().tracks)
        assertEquals(TrackSort.TITLE, onPc.playlists.single().sort)
        assertTrue(onPc.favorites.isEmpty())
        assertEquals(listOf(track(7)), onPc.history)

        // Оба устройства правят один плейлист, не видя друг друга: побеждают обе правки
        pc.library.addToPlaylist(mix.id, track(4))
        phone.library.addToPlaylist(mix.id, track(5))
        phone.library.renamePlaylist(mix.id, "Микс 2")
        pc.synced()
        phone.synced()
        pc.synced()
        assertEquals(pc.library.data.value.playlists, phone.library.data.value.playlists)
        val merged = pc.library.data.value.playlists.single()
        assertEquals("Микс 2", merged.name)
        assertEquals(setOf(1, 2, 3, 4, 5).map(::track).toSet(), merged.tracks.toSet())

        // Удаление плейлиста доходит до второго устройства и не воскресает
        phone.library.deletePlaylist(mix.id)
        phone.synced()
        pc.synced()
        assertTrue(pc.library.data.value.playlists.isEmpty())

        // Библиотека переживает перезапуск: всё отправлено, ничего не теряется
        pc.library.flush()
        val restarted = Library(File(root, "pc"))
        assertEquals(pc.library.data.value, restarted.data.value)
    }

    @Test
    fun inviteRegistrationAndLogout() = runBlocking {
        val admin = device("admin")
        admin.sync.setup(url, "admin", "password123")
        val invite = admin.sync.createInvite()

        val friend = device("friend")
        friend.library.toggleFavorite(track(9))
        friend.sync.register(url, "friend", "password456", invite.code)
        friend.synced()
        admin.synced()
        // У каждого своя библиотека
        assertTrue(admin.library.data.value.favorites.isEmpty())
        assertEquals(listOf(track(9)), friend.library.data.value.favorites)

        friend.sync.logout()
        withTimeout(5_000) { while (friend.sync.account.value != null) delay(50) }
        assertNull(friend.sync.account.value)
        // Библиотека на устройстве остаётся
        assertEquals(listOf(track(9)), friend.library.data.value.favorites)
    }
}
