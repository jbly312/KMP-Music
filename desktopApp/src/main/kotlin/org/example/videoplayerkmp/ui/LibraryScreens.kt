package org.example.videoplayerkmp.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.videoplayerkmp.data.Playlist
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.TrackSort
import org.example.videoplayerkmp.data.formatTotalDuration
import org.example.videoplayerkmp.data.tracksCount
import java.time.LocalTime

// ---------- Главная ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    val data = LocalLibraryData.current
    val navigator = LocalNavigator.current
    val player = LocalPlayer.current
    val scroll = rememberScrollState()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 32.dp, vertical = 28.dp)) {
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            if (data.history.isEmpty() && data.favorites.isEmpty() && data.playlists.isEmpty()) {
                EmptyState(
                    Icons.Rounded.Headphones,
                    "Найдите музыку, которую любите",
                    "Ищите песни и исполнителей или вставьте ссылку на YouTube.\n" +
                        "Когда трек закончится, автоплей подберёт похожие.",
                ) {
                    val dialogs = LocalDialogs.current
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { navigator.go(Screen.Search) }) {
                            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Перейти к поиску")
                        }
                        OutlinedButton(onClick = { dialogs.show(DialogRequest.ImportSpotify) }) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Импорт из Spotify")
                        }
                    }
                }
                return@Column
            }

            if (data.history.isNotEmpty()) {
                SectionTitle("Недавно прослушанные") { navigator.go(Screen.History) }
                TrackCards(data.history.take(12))
            }
            if (data.favorites.isNotEmpty()) {
                SectionTitle("Избранное") { navigator.go(Screen.Favorites) }
                TrackCards(data.favorites.take(12))
            }
            if (data.playlists.isNotEmpty()) {
                SectionTitle("Ваши плейлисты", onShowAll = null)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    data.playlists.forEach { playlist ->
                        Card(
                            title = playlist.name,
                            subtitle = tracksCount(playlist.tracks.size),
                            onClick = { navigator.go(Screen.PlaylistView(playlist.id)) },
                            onPlay = if (playlist.tracks.isEmpty()) null else ({ player.playTracks(playlist.tracks) }),
                        ) { PlaylistCover(playlist, Modifier.fillMaxSize()) }
                    }
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(scroll),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Доброе утро"
    in 12..17 -> "Добрый день"
    in 18..22 -> "Добрый вечер"
    else -> "Доброй ночи"
}

@Composable
private fun SectionTitle(title: String, onShowAll: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (onShowAll != null) TextButton(onClick = onShowAll) { Text("Показать все") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackCards(tracks: List<Track>) {
    val player = LocalPlayer.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        tracks.forEachIndexed { i, track ->
            Card(
                title = track.title,
                subtitle = track.artist,
                onClick = { player.playTracks(tracks, i) },
                onPlay = { player.playTracks(tracks, i) },
            ) {
                Thumbnail(sizedThumbnail(track.thumbnailUrl, 320), Modifier.fillMaxSize(), RectangleShape)
            }
        }
    }
}

@Composable
private fun Card(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onPlay: (() -> Unit)?,
    cover: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier.width(172.dp)
            .hoverable(interaction)
            .background(if (hovered) colors.surfaceContainerHigh else colors.surfaceContainerLow, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        Box(Modifier.size(152.dp).clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest)) {
            cover()
            if (hovered && onPlay != null) {
                FilledIconButton(
                    onClick = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp),
                    shape = CircleShape,
                ) { Icon(Icons.Rounded.PlayArrow, "Слушать") }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Обложка плейлиста: мозаика из первых четырёх треков. */
@Composable
fun PlaylistCover(playlist: Playlist, modifier: Modifier) {
    val covers = playlist.tracks.mapNotNull { it.thumbnailUrl }.distinct()
    when {
        covers.size >= 4 -> Column(modifier) {
            for (row in 0..1) Row(Modifier.weight(1f)) {
                for (col in 0..1) {
                    Thumbnail(sizedThumbnail(covers[row * 2 + col], 160), Modifier.weight(1f).fillMaxHeight(), RectangleShape)
                }
            }
        }
        covers.isNotEmpty() -> Thumbnail(sizedThumbnail(covers.first(), 320), modifier, RectangleShape)
        else -> GradientCover(Icons.AutoMirrored.Rounded.QueueMusic, modifier)
    }
}

@Composable
private fun GradientCover(icon: ImageVector, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier.background(Brush.linearGradient(listOf(colors.primaryContainer, colors.primary.copy(alpha = 0.55f)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.fillMaxSize(0.42f), tint = colors.onPrimaryContainer)
    }
}

// ---------- Коллекции ----------

@Composable
private fun CollectionHeader(
    label: String,
    title: String,
    tracks: List<Track>,
    cover: @Composable (Modifier) -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val player = LocalPlayer.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 28.dp, bottom = 20.dp), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.size(168.dp).clip(RoundedCornerShape(8.dp))) { cover(Modifier.fillMaxSize()) }
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (tracks.isEmpty()) "Пока пусто" else "${tracksCount(tracks.size)} · ${formatTotalDuration(tracks)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { player.playTracks(tracks) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Слушать")
                }
                OutlinedButton(onClick = { player.playTracks(tracks, shuffle = true) }, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Перемешать")
                }
                actions()
            }
        }
    }
}

@Composable
fun FavoritesScreen() {
    val favorites = LocalLibraryData.current.favorites
    TrackList(
        tracks = favorites,
        modifier = Modifier.fillMaxSize(),
        header = {
            CollectionHeader("Коллекция", "Избранное", favorites, { GradientCover(Icons.Rounded.Favorite, it) })
        },
        footer = {
            if (favorites.isEmpty()) {
                EmptyState(Icons.Rounded.FavoriteBorder, "Здесь будут любимые треки", "Нажмите ♡ рядом с треком, чтобы добавить его сюда")
            }
        },
    )
}

@Composable
fun HistoryScreen() {
    val library = LocalLibrary.current
    val notifier = LocalNotifier.current
    val history = LocalLibraryData.current.history
    TrackList(
        tracks = history,
        modifier = Modifier.fillMaxSize(),
        header = {
            CollectionHeader("Коллекция", "История", history, { GradientCover(Icons.Rounded.History, it) }) {
                if (history.isNotEmpty()) {
                    Tip("Очистить историю") {
                        IconButton(onClick = {
                            val cleared = history
                            library.clearHistory()
                            notifier.notify("История очищена", "Отменить") { library.restoreHistory(cleared) }
                        }) { Icon(Icons.Rounded.DeleteSweep, "Очистить историю") }
                    }
                }
            }
        },
        footer = {
            if (history.isEmpty()) {
                EmptyState(Icons.Rounded.History, "История пуста", "Здесь появятся треки, которые вы слушали")
            }
        },
    )
}

@Composable
fun PlaylistScreen(id: String) {
    val navigator = LocalNavigator.current
    val dialogs = LocalDialogs.current
    val library = LocalLibrary.current
    val playlist = LocalLibraryData.current.playlists.find { it.id == id }
    if (playlist == null) {
        LaunchedEffect(id) { navigator.go(Screen.Home) }
        return
    }
    // Порядок на экране — он же порядок воспроизведения
    val tracks = playlist.displayedTracks
    TrackList(
        tracks = tracks,
        modifier = Modifier.fillMaxSize(),
        playlistId = playlist.id,
        // Перетаскивать можно только в своём порядке: в отсортированном виде место трека задаёт сортировка
        onMove = if (playlist.sort == TrackSort.CUSTOM && tracks.size > 1) {
            { from, to -> library.moveInPlaylist(playlist.id, from, to) }
        } else null,
        header = {
            CollectionHeader("Плейлист", playlist.name, tracks, { PlaylistCover(playlist, it) }) {
                SortButton(playlist.sort, enabled = playlist.tracks.size > 1) { library.setPlaylistSort(playlist.id, it) }
                Tip("Переименовать") {
                    IconButton(onClick = { dialogs.show(DialogRequest.RenamePlaylist(playlist)) }) { Icon(Icons.Rounded.Edit, "Переименовать") }
                }
                Tip("Удалить плейлист") {
                    IconButton(onClick = { dialogs.show(DialogRequest.DeletePlaylist(playlist)) }) { Icon(Icons.Rounded.Delete, "Удалить") }
                }
            }
        },
        footer = {
            if (playlist.tracks.isEmpty()) {
                EmptyState(
                    Icons.AutoMirrored.Rounded.QueueMusic,
                    "В плейлисте пока нет треков",
                    "Найдите трек и выберите «Добавить в плейлист» в меню «⋯»",
                ) { Button(onClick = { navigator.go(Screen.Search) }) { Text("Перейти к поиску") } }
            }
        },
    )
}

/** Выбор сортировки треков; текущий режим подписан на самой кнопке. */
@Composable
private fun SortButton(current: TrackSort, enabled: Boolean, onSelect: (TrackSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Tip("Сортировка треков") {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(current.title)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            TrackSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sort.title) },
                    onClick = {
                        onSelect(sort)
                        expanded = false
                    },
                    leadingIcon = {
                        if (sort == current) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        else Spacer(Modifier.size(18.dp))
                    },
                    trailingIcon = if (sort == TrackSort.CUSTOM) {
                        { Text("перетаскивание", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                )
            }
        }
    }
}
