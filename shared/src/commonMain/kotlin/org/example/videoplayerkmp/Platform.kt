package org.example.videoplayerkmp

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform