package org.example.videoplayerkmp.android

import android.app.Application
import kotlinx.coroutines.MainScope
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.player.PlayerController
import org.example.videoplayerkmp.ui.Navigator
import org.example.videoplayerkmp.ui.SearchState
import java.io.File

/**
 * Объекты, общие для экрана и фонового сервиса: плеер живёт, пока жив процесс,
 * поэтому музыка не прерывается при закрытии экрана.
 */
class KmpMusicApp : Application() {
    lateinit var library: Library
        private set
    lateinit var engine: ExoPlayerEngine
        private set
    lateinit var player: PlayerController
        private set
    lateinit var searchState: SearchState
        private set
    val navigator = Navigator()

    override fun onCreate() {
        super.onCreate()
        library = Library(File(filesDir, "library.json"))
        engine = ExoPlayerEngine(this)
        player = PlayerController(engine, library)
        player.warmUp()
        searchState = SearchState(MainScope(), player)
    }
}
