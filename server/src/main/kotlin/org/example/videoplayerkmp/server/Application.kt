package org.example.videoplayerkmp.server

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.example.videoplayerkmp.sync.ApiError
import org.example.videoplayerkmp.sync.AuthResponse
import org.example.videoplayerkmp.sync.ChangePasswordRequest
import org.example.videoplayerkmp.sync.LoginRequest
import org.example.videoplayerkmp.sync.PushRequest
import org.example.videoplayerkmp.sync.RefreshRequest
import org.example.videoplayerkmp.sync.RegisterRequest
import org.example.videoplayerkmp.sync.ServerInfo
import org.example.videoplayerkmp.sync.SetupRequest
import java.util.Date
import kotlin.time.Duration.Companion.minutes

class ServerConfig(
    val name: String,
    val jwtSecret: String,
    val accessTtlSeconds: Long = 15 * 60,
    /** Попыток входа и регистрации в минуту с одного адреса */
    val authAttemptsPerMinute: Int = 10,
)

/** Короткоживущие токены доступа (JWT). Долгую сессию держит токен обновления. */
class AccessTokens(private val config: ServerConfig) {
    private val algorithm = Algorithm.HMAC256(config.jwtSecret)
    val verifier: JWTVerifier = JWT.require(algorithm).withIssuer(ISSUER).build()

    fun issue(userId: Long): String = JWT.create()
        .withIssuer(ISSUER)
        .withClaim(USER_ID, userId)
        .withExpiresAt(Date(System.currentTimeMillis() + config.accessTtlSeconds * 1000))
        .sign(algorithm)

    companion object {
        const val ISSUER = "kmp-music"
        const val USER_ID = "uid"
    }
}

private val AuthLimit = RateLimitName("auth")

fun Application.module(config: ServerConfig, db: Database) {
    val accounts = Accounts(db)
    val sync = SyncStore(db)
    val tokens = AccessTokens(config)

    install(CallLogging)
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true })
    }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ApiError(e.code, e.message.orEmpty())) }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiError("bad_request", "Некорректный запрос"))
        }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Необработанная ошибка", e)
            call.respond(HttpStatusCode.InternalServerError, ApiError("internal", "Внутренняя ошибка сервера"))
        }
    }
    install(RateLimit) {
        // Защита от подбора паролей и кодов приглашений
        register(AuthLimit) {
            rateLimiter(limit = config.authAttemptsPerMinute, refillPeriod = 1.minutes)
            requestKey { call -> call.clientAddress() }
        }
    }
    install(Authentication) {
        jwt {
            verifier(tokens.verifier)
            validate { credential -> credential.payload.getClaim(AccessTokens.USER_ID).asLong()?.let { JWTPrincipal(credential.payload) } }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, ApiError("unauthorized", "Нужно войти заново"))
            }
        }
    }

    suspend fun session(userId: Long, deviceName: String): AuthResponse {
        val user = accounts.findById(userId)!!
        return AuthResponse(
            accessToken = tokens.issue(user.id),
            refreshToken = io { accounts.issueRefreshToken(user.id, deviceName) },
            expiresIn = config.accessTtlSeconds,
            user = user.toDto(),
        )
    }

    routing {
        get("/health") { call.respondText("ok") }

        route("/api") {
            get("/info") {
                call.respond(ServerInfo(name = config.name, needsSetup = io { accounts.needsSetup() }))
            }

            route("/auth") {
                rateLimit(AuthLimit) {
                    post("/setup") {
                        val request = call.receive<SetupRequest>()
                        val user = io { accounts.setup(request.username, request.password) }
                        call.respond(HttpStatusCode.Created, session(user.id, request.deviceName))
                    }
                    post("/register") {
                        val request = call.receive<RegisterRequest>()
                        val user = io { accounts.register(request.username, request.password, request.inviteCode) }
                        call.respond(HttpStatusCode.Created, session(user.id, request.deviceName))
                    }
                    post("/login") {
                        val request = call.receive<LoginRequest>()
                        val user = io { accounts.authenticate(request.username, request.password) }
                        call.respond(session(user.id, request.deviceName))
                    }
                    post("/refresh") {
                        val request = call.receive<RefreshRequest>()
                        val (user, refresh) = io { accounts.rotateRefreshToken(request.refreshToken) }
                        call.respond(AuthResponse(tokens.issue(user.id), refresh, config.accessTtlSeconds, user.toDto()))
                    }
                }
                post("/logout") {
                    val request = call.receive<RefreshRequest>()
                    io { accounts.revokeRefreshToken(request.refreshToken) }
                    call.respond(HttpStatusCode.NoContent)
                }
            }

            authenticate {
                get("/me") { call.respond(call.currentUser(accounts).toDto()) }

                post("/me/password") {
                    val request = call.receive<ChangePasswordRequest>()
                    io { accounts.changePassword(call.userId(), request.currentPassword, request.newPassword) }
                    call.respond(HttpStatusCode.NoContent)
                }

                route("/sync") {
                    get("/changes") {
                        val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
                        call.respond(io { sync.pull(call.userId(), since, limit) })
                    }
                    post("/push") {
                        val request = call.receive<PushRequest>()
                        call.respond(io { sync.push(call.userId(), request.changes) })
                    }
                }

                adminRoutes(accounts)
            }
        }
    }
}

/** Управление приглашениями и пользователями — только для администратора. */
private fun Route.adminRoutes(accounts: Accounts) {
    route("/invites") {
        get {
            call.requireAdmin(accounts)
            call.respond(io { accounts.listInvites() })
        }
        post {
            val admin = call.requireAdmin(accounts)
            val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 90) ?: 7
            call.respond(HttpStatusCode.Created, io { accounts.createInvite(admin.id, days) })
        }
        delete("/{code}") {
            call.requireAdmin(accounts)
            io { accounts.deleteInvite(call.parameters["code"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/users") {
        get {
            call.requireAdmin(accounts)
            call.respond(io { accounts.listUsers().map { it.toDto() } })
        }
        delete("/{id}") {
            call.requireAdmin(accounts)
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ApiException(HttpStatusCode.BadRequest, "bad_request", "Некорректный id")
            io { accounts.deleteUser(id) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun ApplicationCall.userId(): Long =
    principal<JWTPrincipal>()!!.payload.getClaim(AccessTokens.USER_ID).asLong()

/** Аккаунт берём из базы, а не из токена: удалённый пользователь сразу теряет доступ. */
private suspend fun ApplicationCall.currentUser(accounts: Accounts): User =
    io { accounts.findById(userId()) } ?: throw ApiException(HttpStatusCode.Unauthorized, "unauthorized", "Аккаунт удалён")

private suspend fun ApplicationCall.requireAdmin(accounts: Accounts): User =
    currentUser(accounts).also {
        if (!it.isAdmin) throw ApiException(HttpStatusCode.Forbidden, "forbidden", "Доступно только администратору")
    }

/** За Cloudflare Tunnel все запросы приходят с адреса туннеля — настоящий адрес в заголовке. */
private fun ApplicationCall.clientAddress(): String =
    request.headers["CF-Connecting-IP"] ?: request.origin.remoteHost

/** Работа с базой и bcrypt — блокирующая, уводим её с потоков обработки запросов. */
private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
