package org.example.videoplayerkmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.formatDuration
import org.example.videoplayerkmp.player.RepeatMode

@Composable
fun PlayerBar(queueVisible: Boolean, onToggleQueue: () -> Unit) {
    val player = LocalPlayer.current
    val state = LocalPlayerState.current
    val colors = MaterialTheme.colorScheme
    val track = state.current

    Row(
        Modifier.fillMaxWidth().height(92.dp).background(colors.surfaceContainerLow).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Текущий трек
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            if (track != null) {
                Tip("Сейчас играет") {
                    Thumbnail(
                        sizedThumbnail(track.thumbnailUrl, 226),
                        Modifier.size(52.dp).clickable(onClick = onToggleQueue),
                        RoundedCornerShape(8.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.error ?: track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.error != null) colors.error else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(4.dp))
                FavoriteButton(track)
            }
        }

        // Управление и прогресс
        Column(Modifier.weight(1.3f).widthIn(max = 680.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToggleIcon(Icons.Rounded.Shuffle, "Перемешать", state.shuffle, player::toggleShuffle)
                Tip("Предыдущий") {
                    IconButton(onClick = player::previous, enabled = track != null) { Icon(Icons.Rounded.SkipPrevious, "Предыдущий") }
                }
                PlayPauseButton(isPlaying = state.isPlaying, isBuffering = state.isBuffering, enabled = track != null) {
                    player.togglePlay()
                }
                Tip("Следующий") {
                    IconButton(onClick = player::next, enabled = track != null) { Icon(Icons.Rounded.SkipNext, "Следующий") }
                }
                ToggleIcon(
                    if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (state.repeat) {
                        RepeatMode.OFF -> "Повтор выключен"
                        RepeatMode.ALL -> "Повтор очереди"
                        RepeatMode.ONE -> "Повтор трека"
                    },
                    state.repeat != RepeatMode.OFF,
                    player::cycleRepeat,
                )
            }
            ProgressRow(enabled = track != null, duration = state.duration)
        }

        // Очередь, автоплей и громкость
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            ToggleIcon(
                Icons.Rounded.AllInclusive,
                if (state.autoplay) "Автоплей: похожие треки после очереди" else "Автоплей выключен",
                state.autoplay,
                player::toggleAutoplay,
            )
            ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Сейчас играет и очередь", queueVisible, onToggleQueue)
            VolumeControl(state.volume, player::setVolume)
        }
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, isBuffering: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(42.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colors.onSurface,
            contentColor = colors.surface,
            disabledContainerColor = colors.surfaceContainerHighest,
        ),
    ) {
        if (isBuffering) {
            CircularProgressIndicator(Modifier.size(18.dp), color = colors.surface, strokeWidth = 2.dp)
        } else {
            Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (isPlaying) "Пауза" else "Слушать")
        }
    }
}

@Composable
private fun ProgressRow(enabled: Boolean, duration: Double) {
    val player = LocalPlayer.current
    val position by player.position.collectAsState()
    // Пока пользователь тянет ползунок, показываем его значение, а не реальную позицию
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val max = duration.toFloat().coerceAtLeast(1f)
    val shown = dragValue ?: position.toFloat()

    Row(verticalAlignment = Alignment.CenterVertically) {
        TimeLabel(formatDuration(shown.toLong()), TextAlign.End)
        CompactSlider(
            value = shown,
            onValueChange = { dragValue = it },
            onValueChangeFinished = {
                dragValue?.let { player.seek(it.toDouble()) }
                dragValue = null
            },
            valueRange = 0f..max,
            enabled = enabled && duration > 0,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        TimeLabel(if (duration > 0) formatDuration(duration.toLong()) else "--:--", TextAlign.Start)
    }
}

@Composable
private fun TimeLabel(text: String, align: TextAlign) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.width(44.dp),
    )
}

@Composable
private fun VolumeControl(volume: Float, onChange: (Float) -> Unit) {
    var beforeMute by remember { mutableStateOf(0.7f) }
    val icon = when {
        volume == 0f -> Icons.AutoMirrored.Rounded.VolumeOff
        volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
        else -> Icons.AutoMirrored.Rounded.VolumeUp
    }
    Tip(if (volume == 0f) "Включить звук" else "Выключить звук") {
        IconButton(onClick = {
            if (volume > 0f) {
                beforeMute = volume
                onChange(0f)
            } else {
                onChange(beforeMute.takeIf { it > 0f } ?: 0.7f)
            }
        }) {
            Icon(icon, "Громкость", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    CompactSlider(volume, onChange, Modifier.width(110.dp))
}

// ---------- Сейчас играет и очередь ----------

/**
 * Главный экран момента: крупная обложка и её цвет, разлитый по фону панели.
 * Ниже — очередь, по которой можно перейти к любому треку.
 */
@Composable
fun NowPlayingPanel(onClose: () -> Unit) {
    val player = LocalPlayer.current
    val state = LocalPlayerState.current
    val colors = MaterialTheme.colorScheme
    val dark = LocalIsDark.current
    val current = state.current

    Box(Modifier.width(340.dp).fillMaxHeight().background(colors.surfaceContainerLow)) {
        Box(
            Modifier.fillMaxWidth().height(460.dp).background(
                Brush.verticalGradient(listOf(colors.primary.copy(alpha = if (dark) 0.30f else 0.18f), Color.Transparent))
            )
        )
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 16.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Сейчас играет", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Tip("Закрыть панель") {
                        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть панель") }
                    }
                }
            }
            if (current == null) {
                item {
                    Text(
                        "Выберите трек, и здесь появится его обложка и очередь",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                return@LazyColumn
            }
            item(key = "artwork") {
                Column(Modifier.padding(end = 8.dp)) {
                    Thumbnail(
                        sizedThumbnail(current.thumbnailUrl, 544),
                        Modifier.fillMaxWidth().aspectRatio(1f).shadow(24.dp, RoundedCornerShape(14.dp)),
                        RoundedCornerShape(14.dp),
                    )
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                current.artist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FavoriteButton(current)
                        TrackMenuButton(current)
                    }
                    state.error?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.error, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 28.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Далее", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (state.upcoming.isNotEmpty()) TextButton(onClick = player::clearUpcoming) { Text("Очистить") }
                }
                state.radioSeed?.takeIf { state.upcoming.isNotEmpty() }?.let { seed ->
                    Text(
                        "Радио по треку «${seed.title}»",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            itemsIndexed(state.upcoming, key = { i, t -> "${t.id}#${state.index + 1 + i}" }) { i, track ->
                val queueIndex = state.index + 1 + i
                QueueRow(
                    track,
                    onClick = { player.skipTo(queueIndex) },
                    onRemove = { player.removeFromQueue(queueIndex) },
                )
            }
            if (state.upcoming.isEmpty()) {
                item {
                    Text(
                        if (state.autoplay) "Когда трек закончится, автоплей добавит похожие"
                        else "Дальше ничего нет — добавьте треки через меню «⋯»",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(track: Track, onClick: () -> Unit, onRemove: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .hoverable(interaction)
            .background(if (hovered) colors.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(track.thumbnailUrl, Modifier.size(40.dp), RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hovered) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Close, "Убрать из очереди", Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        } else {
            Text(
                formatDuration(track.durationSec),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}
