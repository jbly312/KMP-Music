package org.example.videoplayerkmp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.videoplayerkmp.data.tracksCount
import org.example.videoplayerkmp.player.RepeatMode

/**
 * Раскладка для телефона: экран, над нижней навигацией — мини-плеер;
 * нажатие на мини-плеер открывает полноэкранный «Сейчас играет».
 */
@Composable
internal fun PhoneLayout(navigator: Navigator, searchState: SearchState, snackbar: SnackbarHostState) {
    val state = LocalPlayerState.current
    var nowPlayingOpen by remember { mutableStateOf(false) }
    if (state.current == null) nowPlayingOpen = false

    // Системная «Назад»: сначала закрывает «Сейчас играет», потом возвращает по истории экранов
    PlatformBackHandler(enabled = nowPlayingOpen || navigator.canGoBack) {
        if (nowPlayingOpen) nowPlayingOpen = false else navigator.back()
    }

    // Пока открыта клавиатура, нижние панели только мешают — прячем их
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.current != null) AmbientGlow()
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                    ScreenContent(navigator, searchState)
                }
                AppSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
            if (!keyboardOpen) {
                if (state.current != null) MiniPlayer(onOpen = { nowPlayingOpen = true })
                PhoneNavigationBar(navigator)
            }
        }
        AnimatedVisibility(
            visible = nowPlayingOpen,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(onClose = { nowPlayingOpen = false })
        }
    }
}

@Composable
private fun PhoneNavigationBar(navigator: Navigator) {
    val screen = navigator.screen
    val librarySelected = screen == Screen.Library || screen == Screen.Favorites ||
        screen == Screen.History || screen is Screen.PlaylistView
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        NavigationBarItem(
            selected = screen == Screen.Home,
            onClick = { navigator.goTab(Screen.Home) },
            icon = { Icon(Icons.Rounded.Home, null) },
            label = { Text("Главная") },
        )
        NavigationBarItem(
            selected = screen == Screen.Search,
            onClick = { navigator.goTab(Screen.Search) },
            icon = { Icon(Icons.Rounded.Search, null) },
            label = { Text("Поиск") },
        )
        NavigationBarItem(
            selected = librarySelected,
            onClick = { navigator.goTab(Screen.Library) },
            icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, null) },
            label = { Text("Библиотека") },
        )
    }
}

/** Полоска над навигацией: обложка, название, пауза и следующий трек. */
@Composable
private fun MiniPlayer(onOpen: () -> Unit) {
    val player = LocalPlayer.current
    val state = LocalPlayerState.current
    val track = state.current ?: return
    val position by player.position.collectAsState()
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(colors.surfaceContainerHigh)) {
        LinearProgressIndicator(
            progress = { if (state.duration > 0) (position / state.duration).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.primary,
            trackColor = colors.surfaceContainerHighest,
            drawStopIndicator = {},
            gapSize = 0.dp,
        )
        Row(
            Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(sizedThumbnail(track.thumbnailUrl, 226), Modifier.size(44.dp), RoundedCornerShape(6.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.error ?: track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.error != null) colors.error else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = player::togglePlay) {
                if (state.isBuffering) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Пауза" else "Слушать")
                }
            }
            IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Следующий") }
        }
    }
}

/** Полноэкранный плеер: крупная обложка, перемотка, управление и очередь — всё в одной прокрутке. */
@Composable
private fun NowPlayingScreen(onClose: () -> Unit) {
    val player = LocalPlayer.current
    val state = LocalPlayerState.current
    val colors = MaterialTheme.colorScheme
    val track = state.current ?: return
    val dark = LocalIsDark.current

    Box(Modifier.fillMaxSize().background(colors.background)) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.7f).background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = if (dark) 0.35f else 0.22f), Color.Transparent))
            )
        )
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose, modifier = Modifier.offset(x = (-12).dp)) {
                        Icon(Icons.Rounded.KeyboardArrowDown, "Свернуть", Modifier.size(30.dp))
                    }
                    Text(
                        "Сейчас играет",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Box(Modifier.offset(x = 12.dp)) { TrackMenuButton(track) }
                }
            }
            item(key = "artwork") {
                Thumbnail(
                    sizedThumbnail(track.thumbnailUrl, 544),
                    Modifier.padding(top = 16.dp).fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(16.dp)),
                    RoundedCornerShape(16.dp),
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            track.artist,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Normal,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    FavoriteButton(track)
                }
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(top = 8.dp))
                }
            }
            item { Box(Modifier.padding(top = 16.dp)) { ProgressRow(enabled = true, duration = state.duration) } }
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToggleIcon(Icons.Rounded.Shuffle, "Перемешать", state.shuffle, player::toggleShuffle)
                    IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Предыдущий", Modifier.size(36.dp))
                    }
                    PlayPauseButton(state.isPlaying, state.isBuffering, enabled = true, size = 72.dp, onClick = player::togglePlay)
                    IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Следующий", Modifier.size(36.dp))
                    }
                    ToggleIcon(
                        if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Повтор",
                        state.repeat != RepeatMode.OFF,
                        player::cycleRepeat,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 32.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Далее", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilterChip(
                        selected = state.autoplay,
                        onClick = player::toggleAutoplay,
                        label = { Text("Автоплей") },
                        leadingIcon = { Icon(Icons.Rounded.AllInclusive, null, Modifier.size(18.dp)) },
                    )
                }
                state.radioSeed?.takeIf { state.upcoming.isNotEmpty() }?.let { seed ->
                    Text(
                        "Радио по треку «${seed.title}»",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(state.upcoming, key = { i, t -> "${t.id}#${state.index + 1 + i}" }) { i, upcoming ->
                val queueIndex = state.index + 1 + i
                QueueRow(upcoming, onClick = { player.skipTo(queueIndex) }, onRemove = { player.removeFromQueue(queueIndex) })
            }
            if (state.upcoming.isEmpty()) {
                item {
                    Text(
                        if (state.autoplay) "Когда трек закончится, автоплей добавит похожие" else "Дальше ничего нет",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** «Библиотека» на телефоне: избранное, история и плейлисты одним списком. */
@Composable
internal fun LibraryScreen() {
    val navigator = LocalNavigator.current
    val dialogs = LocalDialogs.current
    val data = LocalLibraryData.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp)) {
        item {
            Text("Библиотека", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
        }
        item {
            LibraryRow("Избранное", tracksCount(data.favorites.size), { navigator.go(Screen.Favorites) }) {
                GradientCover(Icons.Rounded.Favorite, it)
            }
        }
        item {
            LibraryRow("История", tracksCount(data.history.size), { navigator.go(Screen.History) }) {
                GradientCover(Icons.Rounded.History, it)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Плейлисты", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = { dialogs.show(DialogRequest.ImportSpotify) }) { Icon(Icons.Rounded.Download, "Импорт из Spotify") }
                IconButton(onClick = { dialogs.show(DialogRequest.NewPlaylist()) }) { Icon(Icons.Rounded.Add, "Новый плейлист") }
            }
        }
        if (data.playlists.isEmpty()) {
            item {
                Text(
                    "Создайте плейлист кнопкой «+» или перенесите свой из Spotify",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(data.playlists, key = { it.id }) { playlist ->
            LibraryRow(playlist.name, tracksCount(playlist.tracks.size), { navigator.go(Screen.PlaylistView(playlist.id)) }) {
                PlaylistCover(playlist, it)
            }
        }
    }
}

@Composable
private fun LibraryRow(title: String, subtitle: String, onClick: () -> Unit, cover: @Composable (Modifier) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) { cover(Modifier.fillMaxSize()) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
