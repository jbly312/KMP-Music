package org.example.videoplayerkmp.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Настройки через переменные окружения:
 * - PORT — порт (по умолчанию 8080)
 * - DATA_DIR — папка с базой и секретом (по умолчанию ./data, в Docker — /data)
 * - SERVER_NAME — название, которое видят приложения
 * - JWT_SECRET — секрет подписи токенов; если не задан, создаётся при первом запуске и хранится в DATA_DIR
 */
fun main() {
    val dataDir = File(System.getenv("DATA_DIR") ?: "data").apply { mkdirs() }
    val config = ServerConfig(
        name = System.getenv("SERVER_NAME")?.takeIf { it.isNotBlank() } ?: "KMP Music",
        jwtSecret = System.getenv("JWT_SECRET")?.takeIf { it.length >= 32 } ?: loadOrCreateSecret(File(dataDir, "jwt-secret")),
    )
    val db = Database(File(dataDir, "kmp-music.db"))
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080

    embeddedServer(CIO, port = port, host = "0.0.0.0") { module(config, db) }
        .apply { Runtime.getRuntime().addShutdownHook(Thread { db.close() }) }
        .start(wait = true)
}

private fun loadOrCreateSecret(file: File): String {
    if (file.exists()) return file.readText().trim()
    val secret = Base64.getEncoder().encodeToString(ByteArray(48).also(SecureRandom()::nextBytes))
    file.writeText(secret)
    file.setReadable(false, false)
    file.setReadable(true, true)
    return secret
}
