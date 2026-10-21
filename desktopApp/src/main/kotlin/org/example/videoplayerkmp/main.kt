package org.example.videoplayerkmp

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.example.videoplayerkmp.data.AppDirs
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.player.AudioCache
import org.example.videoplayerkmp.player.PlayerController
import org.example.videoplayerkmp.player.StreamServer
import org.example.videoplayerkmp.ui.MusicApp
import org.example.videoplayerkmp.ui.Navigator
import org.example.videoplayerkmp.ui.Screen
import org.example.videoplayerkmp.ui.SearchState
import java.awt.Dimension
import java.io.File

fun main() {
    val library = Library(File(AppDirs.data, "library.json"))
    val cache = AudioCache(AppDirs.cache)
    val server = StreamServer(cache)
    val player = PlayerController(cache, server, library)
    player.warmUp()
    val searchState = SearchState(CoroutineScope(SupervisorJob() + Dispatchers.Main), player)

    application {
        val navigator = remember { Navigator() }
        Window(
            onCloseRequest = {
                player.shutdown()
                library.flush()
                server.stop()
                exitApplication()
            },
            state = rememberWindowState(size = DpSize(1240.dp, 800.dp)),
            title = "KMP Music",
            icon = remember { appIcon() },
            onPreviewKeyEvent = { event ->
                // Ctrl+Пробел — пауза, Ctrl+←/→ — треки, Ctrl+F — поиск
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@Window false
                when (event.key) {
                    Key.Spacebar -> player.togglePlay()
                    Key.DirectionRight -> player.next()
                    Key.DirectionLeft -> player.previous()
                    Key.F -> navigator.go(Screen.Search)
                    else -> return@Window false
                }
                true
            },
        ) {
            window.minimumSize = Dimension(980, 640)
            MusicApp(player, library, navigator, searchState)
        }
    }
}

private fun appIcon(): BitmapPainter {
    val bytes = object {}.javaClass.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
    return BitmapPainter(org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
