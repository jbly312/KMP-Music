package org.example.videoplayerkmp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.videoplayerkmp.account.SyncManager
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.player.PlayerController

/** Ниже этой ширины окна боковая панель сама сворачивается до иконок. */
private val CompactWidth = 1100.dp

/** Уже этого — телефон: нижняя навигация и мини-плеер вместо боковой панели и панели плеера. */
private val PhoneWidth = 600.dp

@Composable
fun MusicApp(player: PlayerController, library: Library, sync: SyncManager, navigator: Navigator, searchState: SearchState) {
    val playerState by player.state.collectAsState()
    val libraryData by library.data.collectAsState()
    val favoriteIds = remember(libraryData.favorites) { libraryData.favorites.mapTo(HashSet()) { it.id } }
    val dialogs = remember { Dialogs() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notifier = remember {
        Notifier { text, actionLabel, onAction ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = text,
                    actionLabel = actionLabel,
                    // С кнопкой «Отменить» даём время дочитать и нажать
                    duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
            }
        }
    }
    var panelVisible by remember { mutableStateOf(false) }
    var sidebarPreference by remember { mutableStateOf<Boolean?>(null) }

    // Цвет обложки текущего трека становится акцентом всего интерфейса
    val artworkUrl = sizedThumbnail(playerState.current?.thumbnailUrl, 226)
    var artworkSeed by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(artworkUrl) {
        artworkSeed = artworkUrl?.let { ArtworkPalette.seed(it) }
    }

    AppTheme(artworkSeed) {
        CompositionLocalProvider(
            LocalPlayer provides player,
            LocalLibrary provides library,
            LocalSync provides sync,
            LocalNavigator provides navigator,
            LocalDialogs provides dialogs,
            LocalNotifier provides notifier,
            LocalPlayerState provides playerState,
            LocalLibraryData provides libraryData,
            LocalFavoriteIds provides favoriteIds,
        ) {
            Surface(color = MaterialTheme.colorScheme.background) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    if (maxWidth < PhoneWidth) {
                        CompositionLocalProvider(LocalCompact provides true) {
                            PhoneLayout(navigator, searchState, snackbar)
                        }
                        return@BoxWithConstraints
                    }
                    val compact = maxWidth < CompactWidth
                    // При пересечении порога снова решает ширина окна, а не прошлый выбор
                    LaunchedEffect(compact) { sidebarPreference = null }
                    val sidebarExpanded = sidebarPreference ?: !compact

                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f)) {
                            Sidebar(sidebarExpanded, onToggle = { sidebarPreference = !sidebarExpanded })
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (playerState.current != null) AmbientGlow()
                                ScreenContent(navigator, searchState)
                                AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                            }
                            AnimatedVisibility(
                                visible = panelVisible,
                                enter = expandHorizontally(expandFrom = Alignment.Start),
                                exit = shrinkHorizontally(shrinkTowards = Alignment.Start),
                            ) {
                                NowPlayingPanel(onClose = { panelVisible = false })
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PlayerBar(panelVisible, onToggleQueue = { panelVisible = !panelVisible })
                    }
                }
            }
            DialogHost()
        }
    }
}

/** Текущий экран — общий для раскладок ПК и телефона. */
@Composable
internal fun ScreenContent(navigator: Navigator, searchState: SearchState) {
    when (val screen = navigator.screen) {
        Screen.Home -> HomeScreen()
        Screen.Search -> SearchScreen(searchState)
        Screen.Library -> LibraryScreen()
        Screen.Favorites -> FavoritesScreen()
        Screen.History -> HistoryScreen()
        is Screen.PlaylistView -> PlaylistScreen(screen.id)
        Screen.Account -> AccountScreen()
    }
}

@Composable
internal fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier) {
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data,
            shape = MaterialTheme.shapes.medium,
            containerColor = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            actionColor = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** Мягкое свечение цвета обложки под заголовком страницы. */
@Composable
internal fun AmbientGlow() {
    val alpha = if (LocalIsDark.current) 0.20f else 0.12f
    Box(
        Modifier.fillMaxWidth().height(340.dp).background(
            Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = alpha), Color.Transparent))
        )
    )
}
