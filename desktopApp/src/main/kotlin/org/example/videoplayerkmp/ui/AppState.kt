package org.example.videoplayerkmp.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.data.LibraryData
import org.example.videoplayerkmp.data.Playlist
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.player.PlayerController
import org.example.videoplayerkmp.player.PlayerState

sealed interface Screen {
    data object Home : Screen
    data object Search : Screen
    data object Favorites : Screen
    data object History : Screen
    data class PlaylistView(val id: String) : Screen
}

class Navigator {
    var screen: Screen by mutableStateOf(Screen.Home)
        private set

    fun go(screen: Screen) {
        this.screen = screen
    }
}

sealed interface DialogRequest {
    /** Новый плейлист; если передан трек — он сразу добавится в плейлист. */
    data class NewPlaylist(val track: Track? = null) : DialogRequest
    data class RenamePlaylist(val playlist: Playlist) : DialogRequest
    data class DeletePlaylist(val playlist: Playlist) : DialogRequest
}

class Dialogs {
    var current: DialogRequest? by mutableStateOf(null)
        private set

    fun show(request: DialogRequest) {
        current = request
    }

    fun dismiss() {
        current = null
    }
}

/** Короткое уведомление внизу экрана; с [actionLabel] — например, «Отменить». */
class Notifier(private val show: (text: String, actionLabel: String?, onAction: (() -> Unit)?) -> Unit) {
    fun notify(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
        show(text, actionLabel, onAction)
}

val LocalPlayer = staticCompositionLocalOf<PlayerController> { error("PlayerController не передан") }
val LocalLibrary = staticCompositionLocalOf<Library> { error("Library не передана") }
val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator не передан") }
val LocalDialogs = staticCompositionLocalOf<Dialogs> { error("Dialogs не переданы") }
val LocalNotifier = staticCompositionLocalOf { Notifier { _, _, _ -> } }

val LocalPlayerState = compositionLocalOf { PlayerState() }
val LocalLibraryData = compositionLocalOf { LibraryData() }
val LocalFavoriteIds = compositionLocalOf { emptySet<String>() }
