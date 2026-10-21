package org.example.videoplayerkmp.data

import java.io.File

/** Стандартные каталоги приложения для каждой ОС. */
object AppDirs {
    private const val APP = "KMPMusic"
    private val os = System.getProperty("os.name").lowercase()
    private val home = File(System.getProperty("user.home"))

    private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }?.let(::File)

    /** Библиотека пользователя: плейлисты, избранное, история. */
    val data: File by lazy {
        when {
            os.contains("win") -> (env("APPDATA") ?: home).resolve(APP)
            os.contains("mac") -> home.resolve("Library/Application Support/$APP")
            else -> (env("XDG_DATA_HOME") ?: home.resolve(".local/share")).resolve(APP)
        }.apply { mkdirs() }
    }

    /** Кэш скачанных треков — можно безопасно удалить. */
    val cache: File by lazy {
        when {
            os.contains("win") -> (env("LOCALAPPDATA") ?: home).resolve("$APP/cache")
            os.contains("mac") -> home.resolve("Library/Caches/$APP")
            else -> (env("XDG_CACHE_HOME") ?: home.resolve(".cache")).resolve(APP)
        }.apply { mkdirs() }
    }
}
