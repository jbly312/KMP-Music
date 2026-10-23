package org.example.videoplayerkmp.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.core.RepeatMode as AnimationRepeatMode
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collect
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.formatDuration
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString

// ---------- Мелкие элементы ----------

@Composable
fun ToggleIcon(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    Tip(label) {
        IconButton(onClick = onClick) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (active) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).offset(y = 8.dp).size(4.dp)
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

/** Тонкий слайдер для прогресса и громкости. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.onSurface,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledThumbColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledActiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        disabledInactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    Slider(
        value = value.coerceIn(valueRange),
        onValueChange = onValueChange,
        modifier = modifier.height(20.dp),
        enabled = enabled,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        colors = colors,
        interactionSource = interaction,
        thumb = {
            SliderDefaults.Thumb(
                interactionSource = interaction,
                colors = colors,
                enabled = enabled,
                thumbSize = DpSize(12.dp, 12.dp),
            )
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.height(4.dp),
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp,
            )
        },
    )
}

@Composable
fun FavoriteButton(track: Track, alwaysVisible: Boolean = true, modifier: Modifier = Modifier) {
    val library = LocalLibrary.current
    val notifier = LocalNotifier.current
    val isFavorite = track.id in LocalFavoriteIds.current
    val label = if (isFavorite) "Убрать из избранного" else "Добавить в избранное"
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (alwaysVisible || isFavorite) {
            Tip(label) {
                IconButton(onClick = {
                    library.toggleFavorite(track)
                    notifier.notify(if (isFavorite) "Удалено из избранного" else "Добавлено в избранное")
                }) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = label,
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 64.dp, horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

// ---------- Список треков ----------

@Composable
fun TrackList(
    tracks: List<Track>,
    modifier: Modifier = Modifier,
    playlistId: String? = null,
    state: LazyListState = rememberLazyListState(),
    onEndReached: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    /** По умолчанию весь список становится очередью; поиск вместо этого запускает радио. */
    onPlay: ((index: Int) -> Unit)? = null,
    /** Если задан — у строк появляется ручка, за которую их можно перетаскивать. */
    onMove: ((from: Int, to: Int) -> Unit)? = null,
) {
    val player = LocalPlayer.current
    val playerState = LocalPlayerState.current
    val scope = rememberCoroutineScope()
    val headerCount = if (header != null) 1 else 0
    val currentHeaderCount by rememberUpdatedState(headerCount)
    val currentSize by rememberUpdatedState(tracks.size)
    val currentOnMove by rememberUpdatedState(onMove)
    val dragState = remember(state) {
        DragDropState(
            list = state,
            scope = scope,
            canMoveTo = { it - currentHeaderCount in 0 until currentSize },
            onMove = { from, to -> currentOnMove?.invoke(from - currentHeaderCount, to - currentHeaderCount) },
        )
    }
    // Ключи не зависят от позиции: иначе при перетаскивании строка пересоздаётся и «отпускается»
    val keys = remember(tracks) {
        val seen = HashMap<String, Int>()
        tracks.map { t ->
            val n = (seen[t.id] ?: 0) + 1
            seen[t.id] = n
            if (n == 1) t.id else "${t.id}#$n"
        }
    }
    Box(modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = if (LocalCompact.current) PaddingValues(start = 8.dp, end = 4.dp, bottom = 16.dp)
            else PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        ) {
            if (header != null) item(key = "header") { header() }
            itemsIndexed(tracks, key = { i, _ -> keys[i] }) { i, track ->
                val lazyIndex by rememberUpdatedState(i + headerCount)
                val dragging = dragState.draggingIndex == i + headerCount
                TrackRow(
                    track = track,
                    index = i,
                    isCurrent = playerState.current?.id == track.id,
                    isPlaying = playerState.isPlaying,
                    playlistId = playlistId,
                    onPlay = { onPlay?.invoke(i) ?: player.playTracks(tracks, i) },
                    dragHandle = if (onMove != null) Modifier.dragHandle(dragState) { lazyIndex } else null,
                    dragging = dragging,
                    modifier = if (dragging) {
                        Modifier.zIndex(1f).graphicsLayer { translationY = dragState.draggingOffset }
                    } else {
                        Modifier.animateItem()
                    },
                )
            }
            if (footer != null) item(key = "footer") { footer() }
        }
        ListScrollbar(
            state,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 4.dp, horizontal = 2.dp),
        )
    }

    if (onEndReached != null) {
        val currentOnEndReached by rememberUpdatedState(onEndReached)
        LaunchedEffect(state) {
            snapshotFlow {
                val info = state.layoutInfo
                (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 6
            }.collect { nearEnd -> if (nearEnd) currentOnEndReached() }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    index: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    playlistId: String?,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** Модификатор ручки перетаскивания; null — строку перетаскивать нельзя */
    dragHandle: Modifier? = null,
    dragging: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val compact = LocalCompact.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background = when {
        dragging -> colors.surfaceContainerHighest
        hovered -> colors.surfaceContainerHigh
        isCurrent -> colors.primary.copy(alpha = 0.10f)
        else -> Color.Transparent
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(if (compact) 64.dp else 56.dp)
            .then(if (dragging) Modifier.shadow(10.dp, RoundedCornerShape(10.dp)) else Modifier)
            .hoverable(interaction)
            .background(background, RoundedCornerShape(10.dp))
            .clickable(onClick = onPlay)
            .padding(start = if (dragHandle != null) 0.dp else 6.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            Box(
                Modifier.width(22.dp).fillMaxHeight().pointerHoverIcon(PointerIcon.Hand).then(dragHandle),
                contentAlignment = Alignment.Center,
            ) {
                // На телефоне наводить нечем — ручка видна всегда
                if (hovered || dragging || compact) {
                    Icon(Icons.Rounded.DragIndicator, "Перетащить", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
            }
        }
        if (compact) {
            // Номер на узком экране отнимает место у названия — текущий трек отмечаем эквалайзером
            if (isCurrent) {
                EqualizerBars(playing = isPlaying, color = colors.primary, modifier = Modifier.padding(end = 10.dp).size(14.dp))
            }
        } else Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            when {
                hovered -> Icon(Icons.Rounded.PlayArrow, "Слушать", Modifier.size(20.dp))
                // Форма (эквалайзер) дублирует цвет: текущий трек виден и без различения цветов
                isCurrent -> EqualizerBars(playing = isPlaying, color = colors.primary, modifier = Modifier.size(14.dp))
                else -> Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (!compact) Spacer(Modifier.width(6.dp))
        Thumbnail(track.thumbnailUrl, Modifier.size(if (compact) 48.dp else 40.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(if (compact) 12.dp else 14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // На телефоне избранное — в меню «⋯» и на экране «Сейчас играет»
        if (!compact) FavoriteButton(track, alwaysVisible = hovered)
        Text(
            formatDuration(track.durationSec),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
            textAlign = TextAlign.End,
        )
        TrackMenuButton(track, playlistId)
    }
}

/** Три полоски: пляшут, пока трек играет, и замирают на паузе. */
@Composable
fun EqualizerBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val resting = listOf(0.45f, 0.85f, 0.6f)
    val heights = List(3) { i ->
        if (playing) {
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(durationMillis = 380 + i * 140, easing = FastOutSlowInEasing),
                    AnimationRepeatMode.Reverse,
                ),
                label = "bar$i",
            ).value
        } else {
            resting[i]
        }
    }
    Canvas(modifier) {
        val barWidth = size.width / 5
        heights.forEachIndexed { i, h ->
            val barHeight = size.height * h
            drawRoundRect(
                color = color,
                topLeft = Offset(i * 2 * barWidth, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
fun TrackMenuButton(track: Track, playlistId: String? = null) {
    val player = LocalPlayer.current
    val library = LocalLibrary.current
    val dialogs = LocalDialogs.current
    val notifier = LocalNotifier.current
    val playlists = LocalLibraryData.current.playlists
    val isFavorite = track.id in LocalFavoriteIds.current
    @Suppress("DEPRECATION") // LocalClipboard требует платформенный ClipEntry; для строки хватает старого API
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    var expanded by remember { mutableStateOf(false) }
    var choosingPlaylist by remember { mutableStateOf(false) }

    fun close() {
        expanded = false
        choosingPlaylist = false
    }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreHoriz, "Ещё", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = ::close) {
            if (!choosingPlaylist) {
                MenuItem(Icons.AutoMirrored.Rounded.PlaylistPlay, "Играть следующим") {
                    player.playNext(track); notifier.notify("Будет играть следующим"); close()
                }
                MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, "Добавить в очередь") {
                    player.addToQueue(track); notifier.notify("Добавлено в очередь"); close()
                }
                MenuItem(Icons.AutoMirrored.Rounded.PlaylistAdd, "Добавить в плейлист…") { choosingPlaylist = true }
                MenuItem(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isFavorite) "Убрать из избранного" else "В избранное",
                ) {
                    library.toggleFavorite(track); close()
                }
                if (playlistId != null) {
                    MenuItem(Icons.Rounded.RemoveCircleOutline, "Удалить из плейлиста") {
                        val index = library.removeFromPlaylist(playlistId, track.id)
                        if (index >= 0) {
                            notifier.notify("Трек удалён из плейлиста", "Отменить") {
                                library.insertIntoPlaylist(playlistId, index, track)
                            }
                        }
                        close()
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuItem(Icons.Rounded.Link, "Копировать ссылку") {
                    clipboard.setText(AnnotatedString(track.url))
                    notifier.notify("Ссылка скопирована"); close()
                }
                MenuItem(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть на YouTube") {
                    runCatching { uriHandler.openUri(track.url) }; close()
                }
            } else {
                MenuItem(Icons.Rounded.Add, "Новый плейлист…") {
                    dialogs.show(DialogRequest.NewPlaylist(track)); close()
                }
                if (playlists.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                playlists.forEach { playlist ->
                    MenuItem(Icons.AutoMirrored.Rounded.QueueMusic, playlist.name) {
                        library.addToPlaylist(playlist.id, track)
                        notifier.notify("Добавлено в «${playlist.name}»"); close()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
    )
}

// ---------- Диалоги ----------

@Composable
fun DialogHost() {
    val dialogs = LocalDialogs.current
    val library = LocalLibrary.current
    val navigator = LocalNavigator.current
    val notifier = LocalNotifier.current

    when (val request = dialogs.current) {
        null -> Unit
        DialogRequest.ImportSpotify -> SpotifyImportDialog(onDismiss = dialogs::dismiss)
        is DialogRequest.NewPlaylist -> NameDialog(
            title = "Новый плейлист",
            initial = "",
            confirmText = "Создать",
            onDismiss = dialogs::dismiss,
        ) { name ->
            val playlist = library.createPlaylist(name, listOfNotNull(request.track))
            dialogs.dismiss()
            if (request.track != null) notifier.notify("Добавлено в «${playlist.name}»")
            else navigator.go(Screen.PlaylistView(playlist.id))
        }
        is DialogRequest.RenamePlaylist -> NameDialog(
            title = "Переименовать плейлист",
            initial = request.playlist.name,
            confirmText = "Сохранить",
            onDismiss = dialogs::dismiss,
        ) { name ->
            library.renamePlaylist(request.playlist.id, name)
            dialogs.dismiss()
        }
        is DialogRequest.DeletePlaylist -> AlertDialog(
            onDismissRequest = dialogs::dismiss,
            title = { Text("Удалить плейлист?") },
            text = { Text("«${request.playlist.name}» будет удалён без возможности восстановления.") },
            confirmButton = {
                TextButton(onClick = {
                    library.deletePlaylist(request.playlist.id)
                    dialogs.dismiss()
                    navigator.go(Screen.Home)
                    notifier.notify("Плейлист удалён")
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = dialogs::dismiss) { Text("Отмена") } },
        )
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    val valid = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                placeholder = { Text("Название") },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && valid) {
                        onConfirm(name); true
                    } else false
                },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = valid) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}
