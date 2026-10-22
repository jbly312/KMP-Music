package org.example.videoplayerkmp.android

import android.app.Application
import kotlinx.coroutines.MainScope
import org.example.videoplayerkmp.account.SyncManager
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.player.PlayerController
import org.example.videoplayerkmp.ui.Navigator
import org.example.videoplayerkmp.ui.SearchState

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
    lateinit var sync: SyncManager
        private set
    val navigator = Navigator()

    override fun onCreate() {
        super.onCreate()
        library = Library(filesDir)
        engine = ExoPlayerEngine(this)
        player = PlayerController(engine, library)
        player.warmUp()
        val scope = MainScope()
        searchState = SearchState(scope, player)
        sync = SyncManager(library, filesDir, scope)
    }
}
