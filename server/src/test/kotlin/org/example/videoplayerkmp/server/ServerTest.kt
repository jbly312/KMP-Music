package org.example.videoplayerkmp.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.example.videoplayerkmp.sync.*
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ServerTest {
    private fun serverTest(attemptsPerMinute: Int = 1000, block: suspend ApplicationTestBuilder.(HttpClient) -> Unit) {
        val dir = Files.createTempDirectory("kmp-server-test").toFile()
        val db = Database(File(dir, "test.db"))
        try {
            testApplication {
                application {
                    module(ServerConfig("Test", "x".repeat(48), authAttemptsPerMinute = attemptsPerMinute), db)
                }
                val client = createClient { install(ContentNegotiation) { json() } }
                block(client)
            }
        } finally {
            db.close()
            dir.deleteRecursively()
        }
    }

    private suspend inline fun <reified T> HttpClient.postJson(path: String, body: Any, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            token?.let(::bearerAuth)
            setBody(body)
        }

    private suspend fun HttpClient.setupAdmin(): AuthResponse =
        postJson<AuthResponse>("/api/auth/setup", SetupRequest("admin", "admin-password", "PC")).body()

    @Test
    fun `first account becomes admin and setup closes`() = serverTest { client ->
        val info = client.get("/api/info")
        assertTrue(info.body<ServerInfo>().needsSetup)
        // Версия API должна приходить явно — по ней клиент проверяет совместимость
        assertTrue("\"apiVersion\":$API_VERSION" in info.body<String>())

        val admin = client.setupAdmin()
        assertTrue(admin.user.isAdmin)
        assertFalse(client.get("/api/info").body<ServerInfo>().needsSetup)

        val second = client.postJson<Unit>("/api/auth/setup", SetupRequest("intruder", "password123", "PC"))
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals("already_set_up", second.body<ApiError>().code)
    }

    @Test
    fun `registration requires a valid single-use invite`() = serverTest { client ->
        val admin = client.setupAdmin()

        val noInvite = client.postJson<Unit>("/api/auth/register", RegisterRequest("friend", "password123", "NOPE1234", "Phone"))
        assertEquals("invalid_invite", noInvite.body<ApiError>().code)

        val invite = client.post("/api/invites") { bearerAuth(admin.accessToken) }.body<InviteDto>()
        val friend = client.postJson<Unit>("/api/auth/register", RegisterRequest("friend", "password123", invite.code.lowercase(), "Phone"))
        assertEquals(HttpStatusCode.Created, friend.status)
        assertFalse(friend.body<AuthResponse>().user.isAdmin)

        val reuse = client.postJson<Unit>("/api/auth/register", RegisterRequest("friend2", "password123", invite.code, "Phone"))
        assertEquals("invalid_invite", reuse.body<ApiError>().code)

        val invites = client.get("/api/invites") { bearerAuth(admin.accessToken) }.body<List<InviteDto>>()
        assertEquals("friend", invites.single().usedBy)
    }

    @Test
    fun `non-admin cannot manage invites or users`() = serverTest { client ->
        val admin = client.setupAdmin()
        val invite = client.post("/api/invites") { bearerAuth(admin.accessToken) }.body<InviteDto>()
        val friend = client.postJson<Unit>("/api/auth/register", RegisterRequest("friend", "password123", invite.code, "Phone"))
            .body<AuthResponse>()

        assertEquals(HttpStatusCode.Forbidden, client.post("/api/invites") { bearerAuth(friend.accessToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/users") { bearerAuth(friend.accessToken) }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/me").status)
    }

    @Test
    fun `login, refresh rotation and logout`() = serverTest { client ->
        client.setupAdmin()

        val wrong = client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "wrong-password", "Phone"))
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        val unknown = client.postJson<Unit>("/api/auth/login", LoginRequest("ghost", "whatever-pass", "Phone"))
        assertEquals("invalid_credentials", unknown.body<ApiError>().code)

        // Имя без учёта регистра
        val login = client.postJson<Unit>("/api/auth/login", LoginRequest("ADMIN", "admin-password", "Phone")).body<AuthResponse>()
        assertEquals("admin", client.get("/api/me") { bearerAuth(login.accessToken) }.body<UserDto>().username)

        val refreshed = client.postJson<Unit>("/api/auth/refresh", RefreshRequest(login.refreshToken)).body<AuthResponse>()
        assertNotEquals(login.refreshToken, refreshed.refreshToken)
        // Старый токен обновления после ротации не работает
        assertEquals(HttpStatusCode.Unauthorized, client.postJson<Unit>("/api/auth/refresh", RefreshRequest(login.refreshToken)).status)

        client.postJson<Unit>("/api/auth/logout", RefreshRequest(refreshed.refreshToken))
        assertEquals(HttpStatusCode.Unauthorized, client.postJson<Unit>("/api/auth/refresh", RefreshRequest(refreshed.refreshToken)).status)
    }

    @Test
    fun `password change ends other sessions`() = serverTest { client ->
        val admin = client.setupAdmin()
        val phone = client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "admin-password", "Phone")).body<AuthResponse>()

        val change = client.postJson<Unit>("/api/me/password", ChangePasswordRequest("admin-password", "new-password-1"), admin.accessToken)
        assertEquals(HttpStatusCode.NoContent, change.status)

        assertEquals(HttpStatusCode.Unauthorized, client.postJson<Unit>("/api/auth/refresh", RefreshRequest(phone.refreshToken)).status)
        assertEquals(HttpStatusCode.OK, client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "new-password-1", "PC")).status)
    }

    @Test
    fun `weak passwords and bad usernames are rejected`() = serverTest { client ->
        assertEquals("weak_password", client.postJson<Unit>("/api/auth/setup", SetupRequest("admin", "short", "PC")).body<ApiError>().code)
        assertEquals("invalid_username", client.postJson<Unit>("/api/auth/setup", SetupRequest("a b", "password123", "PC")).body<ApiError>().code)
    }

    @Test
    fun `auth endpoints are rate limited`() = serverTest(attemptsPerMinute = 3) { client ->
        client.setupAdmin()
        repeat(2) { client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "wrong-password", "PC")) }
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "wrong-password", "PC")).status)
    }

    // ---------- Синхронизация ----------

    private val track = SyncTrack("dQw4w9WgXcQ", "Song", "Artist", 213)

    private fun favorite(updatedAt: Long, deleted: Boolean = false) = Change(
        kind = EntityKind.FAVORITE,
        id = track.id,
        data = if (deleted) null else Json.encodeToJsonElement(FavoriteData(track, updatedAt)),
        updatedAt = updatedAt,
        deleted = deleted,
    )

    @Test
    fun `changes pushed from one device are pulled by another`() = serverTest { client ->
        val pc = client.setupAdmin()
        val phone = client.postJson<Unit>("/api/auth/login", LoginRequest("admin", "admin-password", "Phone")).body<AuthResponse>()

        val playlist = Change(EntityKind.PLAYLIST, "p1", Json.encodeToJsonElement(PlaylistData("Вечер", 1)), updatedAt = 1)
        val push = client.postJson<Unit>("/api/sync/push", PushRequest(listOf(favorite(100), playlist)), pc.accessToken).body<PushResponse>()
        assertEquals(2, push.applied)

        val pulled = client.get("/api/sync/changes?since=0") { bearerAuth(phone.accessToken) }.body<PullResponse>()
        assertEquals(setOf("favorite", "playlist"), pulled.changes.map { it.kind }.toSet())
        assertEquals(push.revision, pulled.revision)
        assertFalse(pulled.hasMore)

        // Повторный запрос с последней ревизией — ничего нового
        val again = client.get("/api/sync/changes?since=${pulled.revision}") { bearerAuth(phone.accessToken) }.body<PullResponse>()
        assertTrue(again.changes.isEmpty())
    }

    @Test
    fun `last write wins and deletions propagate`() = serverTest { client ->
        val admin = client.setupAdmin()
        client.postJson<Unit>("/api/sync/push", PushRequest(listOf(favorite(200))), admin.accessToken)

        // Более старое изменение с другого устройства не затирает свежее
        val stale = client.postJson<Unit>("/api/sync/push", PushRequest(listOf(favorite(100, deleted = true))), admin.accessToken)
        assertEquals(0, stale.body<PushResponse>().applied)

        val delete = client.postJson<Unit>("/api/sync/push", PushRequest(listOf(favorite(300, deleted = true))), admin.accessToken)
        assertEquals(1, delete.body<PushResponse>().applied)

        val state = client.get("/api/sync/changes?since=0") { bearerAuth(admin.accessToken) }.body<PullResponse>()
        val fav = state.changes.single()
        assertTrue(fav.deleted)
        assertEquals(null, fav.data)
    }

    @Test
    fun `pull is paginated`() = serverTest { client ->
        val admin = client.setupAdmin()
        val changes = (1..25).map { i ->
            Change(EntityKind.HISTORY, "h$i", Json.encodeToJsonElement(HistoryData(track, i.toLong())), updatedAt = i.toLong())
        }
        client.postJson<Unit>("/api/sync/push", PushRequest(changes), admin.accessToken)

        var since = 0L
        val received = mutableListOf<String>()
        do {
            val page = client.get("/api/sync/changes?since=$since&limit=10") { bearerAuth(admin.accessToken) }.body<PullResponse>()
            received += page.changes.map { it.id }
            since = page.revision
        } while (page.hasMore)
        assertEquals(changes.map { it.id }, received)
    }

    @Test
    fun `libraries are isolated between users`() = serverTest { client ->
        val admin = client.setupAdmin()
        val invite = client.post("/api/invites") { bearerAuth(admin.accessToken) }.body<InviteDto>()
        val friend = client.postJson<Unit>("/api/auth/register", RegisterRequest("friend", "password123", invite.code, "Phone"))
            .body<AuthResponse>()

        client.postJson<Unit>("/api/sync/push", PushRequest(listOf(favorite(1))), admin.accessToken)
        val friendView = client.get("/api/sync/changes?since=0") { bearerAuth(friend.accessToken) }.body<PullResponse>()
        assertTrue(friendView.changes.isEmpty())
    }

    @Test
    fun `invalid changes are rejected`() = serverTest { client ->
        val admin = client.setupAdmin()
        val unknownKind = Change("song_lyrics", "x", Json.encodeToJsonElement("text"), updatedAt = 1)
        assertEquals("invalid_change", client.postJson<Unit>("/api/sync/push", PushRequest(listOf(unknownKind)), admin.accessToken).body<ApiError>().code)
        val noData = Change(EntityKind.FAVORITE, "x", null, updatedAt = 1)
        assertEquals("invalid_change", client.postJson<Unit>("/api/sync/push", PushRequest(listOf(noData)), admin.accessToken).body<ApiError>().code)
    }
}
