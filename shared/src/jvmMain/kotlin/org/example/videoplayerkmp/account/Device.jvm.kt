package org.example.videoplayerkmp.account

actual fun deviceName(): String {
    val host = System.getenv("COMPUTERNAME") ?: System.getenv("HOSTNAME")
    return if (host.isNullOrBlank()) "Компьютер" else "Компьютер $host"
}
