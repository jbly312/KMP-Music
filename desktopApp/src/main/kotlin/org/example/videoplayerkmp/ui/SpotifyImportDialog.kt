package org.example.videoplayerkmp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.data.Spotify
import org.example.videoplayerkmp.data.SpotifySource
import org.example.videoplayerkmp.data.SpotifyTrack
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.TrackMatcher
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private sealed interface ImportStep {
    data object Input : ImportStep
    data object Loading : ImportStep
    data class Matching(val name: String, val total: Int, val processed: Int, val found: Int) : ImportStep
    data class Done(
        val name: String,
        val playlistId: String,
        val found: Int,
        val total: Int,
        val missing: List<SpotifyTrack>,
        val truncated: Boolean,
    ) : ImportStep
    data class Failed(val message: String) : ImportStep
}

/** Загружает плейлист Spotify и ищет каждый трек на YouTube (по 4 параллельно). */
private class SpotifyImporter(private val scope: CoroutineScope, private val library: Library) {
    var step: ImportStep by mutableStateOf(ImportStep.Input)
        private set
    private var job: Job? = null

    val isBusy get() = step is ImportStep.Loading || step is ImportStep.Matching

    fun importLink(link: String) = start { Spotify.fetch(link) }

    fun importCsv(file: File) = start { withContext(Dispatchers.IO) { Spotify.parseCsv(file) } }

    fun reset() {
        job?.cancel()
        step = ImportStep.Input
    }

    private fun start(load: suspend () -> SpotifySource) {
        job?.cancel()
        step = ImportStep.Loading
        job = scope.launch {
            try {
                val source = load()
                val total = source.tracks.size
                step = ImportStep.Matching(source.name, total, 0, 0)
                var processed = 0
                var found = 0
                val parallel = Semaphore(4)
                // Порядок треков сохраняется: результаты собираются по индексам
                val matches: List<Track?> = coroutineScope {
                    source.tracks.map { track ->
                        async {
                            parallel.withPermit {
                                TrackMatcher.match(track).also { match ->
                                    processed++
                                    if (match != null) found++
                                    step = ImportStep.Matching(source.name, total, processed, found)
                                }
                            }
                        }
                    }.awaitAll()
                }
                val tracks = matches.filterNotNull().distinctBy { it.id }
                if (tracks.isEmpty()) {
                    step = ImportStep.Failed("Не удалось найти на YouTube ни одного трека из «${source.name}»")
                    return@launch
                }
                val playlist = library.createPlaylist(source.name, tracks)
                step = ImportStep.Done(
                    name = playlist.name,
                    playlistId = playlist.id,
                    found = tracks.size,
                    total = total,
                    missing = source.tracks.filterIndexed { i, _ -> matches[i] == null },
                    truncated = source.maybeTruncated,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                step = ImportStep.Failed(e.message ?: "Не удалось загрузить плейлист")
            }
        }
    }
}

@Composable
fun SpotifyImportDialog(onDismiss: () -> Unit) {
    val library = LocalLibrary.current
    val navigator = LocalNavigator.current
    // Импорт живёт, пока открыт диалог: закрытие отменяет поиск
    val scope = rememberCoroutineScope()
    val importer = remember { SpotifyImporter(scope, library) }
    var link by remember { mutableStateOf("") }
    val linkValid = Spotify.isLink(link)

    AlertDialog(
        // Во время импорта случайный клик мимо окна не должен его прерывать
        onDismissRequest = { if (!importer.isBusy) onDismiss() },
        title = { Text("Импорт из Spotify") },
        text = {
            Box(Modifier.widthIn(min = 420.dp, max = 460.dp)) {
                when (val step = importer.step) {
                    ImportStep.Input -> InputStep(link, { link = it }, linkValid, onSubmit = { importer.importLink(link) }) { file ->
                        importer.importCsv(file)
                    }
                    ImportStep.Loading -> ProgressStep("Загружаем плейлист…", null, null)
                    is ImportStep.Matching -> ProgressStep(
                        "Ищем треки на YouTube",
                        step.name,
                        step,
                    )
                    is ImportStep.Done -> DoneStep(step)
                    is ImportStep.Failed -> Row(verticalAlignment = Alignment.Top) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(12.dp))
                        Text(step.message, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            when (val step = importer.step) {
                ImportStep.Input -> Button(onClick = { importer.importLink(link) }, enabled = linkValid) { Text("Импортировать") }
                ImportStep.Loading, is ImportStep.Matching -> Unit
                is ImportStep.Done -> Button(onClick = {
                    navigator.go(Screen.PlaylistView(step.playlistId))
                    onDismiss()
                }) { Text("Открыть плейлист") }
                is ImportStep.Failed -> Button(onClick = importer::reset) { Text("Попробовать снова") }
            }
        },
        dismissButton = {
            when (importer.step) {
                ImportStep.Loading, is ImportStep.Matching -> TextButton(onClick = onDismiss) { Text("Отменить импорт") }
                is ImportStep.Done -> TextButton(onClick = onDismiss) { Text("Готово") }
                else -> TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

@Composable
private fun InputStep(
    link: String,
    onLinkChange: (String) -> Unit,
    linkValid: Boolean,
    onSubmit: () -> Unit,
    onCsv: (File) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    Column {
        OutlinedTextField(
            value = link,
            onValueChange = onLinkChange,
            label = { Text("Ссылка на плейлист или альбом") },
            placeholder = { Text("https://open.spotify.com/playlist/…") },
            singleLine = true,
            isError = link.isNotBlank() && !linkValid,
            supportingText = {
                Text(
                    if (link.isNotBlank() && !linkValid) "Это не похоже на ссылку на плейлист или альбом Spotify"
                    else "Плейлист должен быть публичным. По ссылке загружается до 100 треков",
                )
            },
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Enter && linkValid) {
                    onSubmit(); true
                } else false
            },
        )
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
            Text("или", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = colors.outlineVariant)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { chooseCsv()?.let(onCsv) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.Description, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Выбрать CSV-файл…")
        }
        Text(
            "Для плейлистов больше 100 треков и закрытых плейлистов: выгрузите CSV на exportify.app",
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun ProgressStep(title: String, playlistName: String?, matching: ImportStep.Matching?) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        if (playlistName != null) {
            Text(
                "«$playlistName»",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (matching == null || matching.total == 0) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { matching.processed.toFloat() / matching.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Проверено ${matching.processed} из ${matching.total} · найдено ${matching.found}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DoneStep(step: ImportStep.Done) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = colors.primary)
            Spacer(Modifier.width(10.dp))
            Text("Плейлист «${step.name}» создан", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Найдено ${step.found} из ${step.total} треков",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
        )
        if (step.truncated) {
            Text(
                "По ссылке Spotify отдаёт только первые 100 треков. Чтобы перенести весь плейлист, импортируйте CSV с exportify.app",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (step.missing.isNotEmpty()) {
            Text(
                "Не нашлись на YouTube (${step.missing.size}):",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
            )
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                step.missing.forEach { track ->
                    Text(
                        "${track.artists.joinToString(", ")} — ${track.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

/** Системный диалог выбора файла. */
private fun chooseCsv(): File? {
    val dialog = FileDialog(null as Frame?, "Выберите CSV-файл плейлиста", FileDialog.LOAD).apply {
        file = "*.csv"
        setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
        isVisible = true
    }
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
