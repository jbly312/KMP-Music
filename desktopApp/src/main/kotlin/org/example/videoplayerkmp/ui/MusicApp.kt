package org.example.videoplayerkmp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.player.PlayerController

@Composable
fun MusicApp(player: PlayerController, library: Library, navigator: Navigator, searchState: SearchState) {
    val playerState by player.state.collectAsState()
    val libraryData by library.data.collectAsState()
    val favoriteIds = remember(libraryData.favorites) { libraryData.favorites.mapTo(HashSet()) { it.id } }
    val dialogs = remember { Dialogs() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifier = remember {
        Notifier { text ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                snackbar.showSnackbar(text)
            }
        }
    }
    var queueVisible by remember { mutableStateOf(false) }

    AppTheme {
        CompositionLocalProvider(
            LocalPlayer provides player,
            LocalLibrary provides library,
            LocalNavigator provides navigator,
            LocalDialogs provides dialogs,
            LocalNotifier provides notifier,
            LocalPlayerState provides playerState,
            LocalLibraryData provides libraryData,
            LocalFavoriteIds provides favoriteIds,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        Sidebar()
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            when (val screen = navigator.screen) {
                                Screen.Home -> HomeScreen()
                                Screen.Search -> SearchScreen(searchState)
                                Screen.Favorites -> FavoritesScreen()
                                Screen.History -> HistoryScreen()
                                is Screen.PlaylistView -> PlaylistScreen(screen.id)
                            }
                            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
                                Snackbar(
                                    data,
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = queueVisible,
                            enter = expandHorizontally(expandFrom = Alignment.Start),
                            exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
                        ) {
                            QueuePanel(onClose = { queueVisible = false })
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PlayerBar(queueVisible, onToggleQueue = { queueVisible = !queueVisible })
                }
            }
            DialogHost()
        }
    }
}
