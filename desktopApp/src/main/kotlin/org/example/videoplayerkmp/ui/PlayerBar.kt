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
                Thumbnail(sizedThumbnail(track.thumbnailUrl, 226), Modifier.size(58.dp), RoundedCornerShape(8.dp))
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
            ToggleIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Очередь", queueVisible, onToggleQueue)
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

// ---------- Очередь ----------

@Composable
fun QueuePanel(onClose: () -> Unit) {
    val player = LocalPlayer.current
    val state = LocalPlayerState.current
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    Column(Modifier.width(340.dp).fillMaxHeight().background(colors.surfaceContainerLow)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Очередь", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (state.upcoming.isNotEmpty()) TextButton(onClick = player::clearUpcoming) { Text("Очистить") }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть") }
        }
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            val current = state.current
            if (current == null) {
                item {
                    Text(
                        "Выберите трек, чтобы начать",
                        Modifier.padding(12.dp),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                return@LazyColumn
            }
            item { QueueLabel("Сейчас играет") }
            item(key = "current") { QueueRow(current, isCurrent = true, onClick = {}, onRemove = null) }
            if (state.upcoming.isNotEmpty()) {
                item {
                    QueueLabel("Далее")
                    state.radioSeed?.let { seed ->
                        Text(
                            "В очереди радио по треку «${seed.title}»",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
                        )
                    }
                }
            }
            itemsIndexed(state.upcoming, key = { i, t -> "${t.id}#${state.index + 1 + i}" }) { i, track ->
                val queueIndex = state.index + 1 + i
                QueueRow(
                    track,
                    isCurrent = false,
                    onClick = { player.skipTo(queueIndex) },
                    onRemove = { player.removeFromQueue(queueIndex) },
                )
            }
            if (state.upcoming.isEmpty()) {
                item {
                    Text(
                        if (state.autoplay) "Когда трек закончится, автоплей добавит похожие треки"
                        else "Дальше ничего нет — добавьте треки через меню «⋯»",
                        Modifier.padding(12.dp),
                        color = colors.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun QueueRow(track: Track, isCurrent: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(56.dp)
            .hoverable(interaction)
            .background(if (hovered) colors.surfaceContainerHigh else Color.Transparent, RoundedCornerShape(8.dp))
            .clickable(enabled = !isCurrent, onClick = onClick)
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
                color = if (isCurrent) colors.primary else colors.onSurface,
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
        if (onRemove != null && hovered) {
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
