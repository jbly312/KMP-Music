package org.example.videoplayerkmp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yushosei.newpipe.extractor.Page
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.videoplayerkmp.data.SearchKind
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.YouTube
import org.example.videoplayerkmp.data.extractVideoId
import org.example.videoplayerkmp.player.PlayerController

/** Состояние поиска живёт выше экрана, чтобы результаты не терялись при переходах. */
class SearchState(private val scope: CoroutineScope, private val player: PlayerController) {
    var query by mutableStateOf("")
        private set
    var kind by mutableStateOf(SearchKind.SONGS)
        private set
    var results by mutableStateOf<List<Track>>(emptyList())
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set
    var isLoading by mutableStateOf(false)
        private set
    var isLoadingMore by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var submittedQuery by mutableStateOf("")
        private set

    private var nextPage: Page? = null
    private var searchToken = 0
    private var searchJob: Job? = null
    private var suggestJob: Job? = null
    private var moreJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank() || extractVideoId(text) != null) {
            suggestions = emptyList()
            return
        }
        suggestJob = scope.launch {
            delay(250)
            suggestions = runCatching { YouTube.suggestions(text) }.getOrDefault(emptyList()).take(8)
        }
    }

    fun hideSuggestions() {
        suggestJob?.cancel()
        suggestions = emptyList()
    }

    fun selectKind(value: SearchKind) {
        if (kind == value) return
        kind = value
        if (submittedQuery.isNotBlank() && extractVideoId(submittedQuery) == null) search(submittedQuery)
    }

    fun submit(text: String = query) {
        query = text
        hideSuggestions()
        val q = text.trim()
        if (q.isEmpty()) return
        if (extractVideoId(q) != null) openLink(q) else search(q)
    }

    fun retry() = submit(submittedQuery)

    fun loadMore() {
        val page = nextPage ?: return
        if (isLoading || isLoadingMore) return
        isLoadingMore = true
        val token = searchToken
        moreJob = scope.launch {
            try {
                val result = YouTube.searchMore(submittedQuery, kind, page)
                if (token != searchToken) return@launch
                results = results + result.tracks
                nextPage = result.next
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                nextPage = null
            } finally {
                if (token == searchToken) isLoadingMore = false
            }
        }
    }

    private fun search(q: String) = startLoading(q) { token ->
        val page = YouTube.search(q, kind)
        if (token == searchToken) {
            results = page.tracks
            nextPage = page.next
        }
    }

    /** Вставили ссылку на YouTube — сразу играем этот трек. */
    private fun openLink(url: String) = startLoading(url) { token ->
        val track = YouTube.track(url)
        if (token == searchToken) {
            results = listOf(track)
            player.playTracks(listOf(track))
        }
    }

    private fun startLoading(q: String, block: suspend (token: Int) -> Unit) {
        searchJob?.cancel()
        moreJob?.cancel()
        val token = ++searchToken
        submittedQuery = q
        results = emptyList()
        nextPage = null
        error = null
        isLoading = true
        isLoadingMore = false
        searchJob = scope.launch {
            try {
                block(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (token == searchToken) error = e.message ?: "Не удалось выполнить поиск"
            } finally {
                if (token == searchToken) isLoading = false
            }
        }
    }
}

@Composable
fun SearchScreen(state: SearchState) {
    val player = LocalPlayer.current
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    // Подсказка, выбранная стрелками ↑/↓; -1 — никакая
    var highlighted by remember(state.suggestions) { mutableStateOf(-1) }
    // Видимость не завязана на фокус поля: в настоящем окне нажатие на подсказку снимает фокус
    // ещё до отпускания кнопки, и список исчезал раньше, чем срабатывал клик.
    // Список закрывается выбором, Esc или кликом мимо (onDismissRequest)
    val showSuggestions = state.suggestions.isNotEmpty()

    Column(Modifier.fillMaxSize()) {
        val pad = if (LocalCompact.current) 16.dp else 32.dp
        Column(Modifier.padding(start = pad, end = pad, top = if (LocalCompact.current) 12.dp else 28.dp, bottom = 8.dp)) {
            Box {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = state::onQueryChange,
                    placeholder = { Text("Песня, исполнитель или ссылка на YouTube") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (state.query.isNotEmpty()) {
                            IconButton(onClick = { state.onQueryChange(""); focus.requestFocus() }) {
                                Icon(Icons.Rounded.Clear, "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                    // Кнопка «Найти» на экранной клавиатуре телефона
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        state.submit()
                        keyboard?.hide()
                        // Без снятия фокуса клавиатура вернётся, когда приложение снова откроют
                        focusManager.clearFocus()
                    }),
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = colors.surfaceContainerHigh,
                        unfocusedContainerColor = colors.surfaceContainerHigh,
                        unfocusedBorderColor = colors.surfaceContainerHigh,
                    ),
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxWidth()
                        .onSizeChanged { fieldSize = it }
                        .focusRequester(focus)
                        .onPreviewKeyEvent {
                            if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val count = state.suggestions.size
                            when (it.key) {
                                Key.DirectionDown -> {
                                    if (!showSuggestions) return@onPreviewKeyEvent false
                                    highlighted = (highlighted + 1).coerceAtMost(count - 1); true
                                }
                                Key.DirectionUp -> {
                                    if (!showSuggestions) return@onPreviewKeyEvent false
                                    highlighted = (highlighted - 1).coerceAtLeast(-1); true
                                }
                                Key.Enter, Key.NumPadEnter -> {
                                    val chosen = state.suggestions.getOrNull(highlighted)
                                    if (showSuggestions && chosen != null) state.submit(chosen) else state.submit()
                                    true
                                }
                                Key.Escape -> { state.hideSuggestions(); true }
                                else -> false
                            }
                        },
                )

                // Подсказки — отдельный слой поверх контента: он не забирает фокус у поля
                // (иначе список исчезал бы до клика) и не сдвигает вёрстку под курсором
                if (showSuggestions) {
                    Popup(
                        offset = IntOffset(0, fieldSize.height + with(density) { 6.dp.roundToPx() }),
                        onDismissRequest = state::hideSuggestions,
                        properties = PopupProperties(focusable = false),
                    ) {
                        Surface(
                            Modifier.width(with(density) { fieldSize.width.toDp() }),
                            shape = RoundedCornerShape(16.dp),
                            color = colors.surfaceContainerHigh,
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.padding(vertical = 6.dp)) {
                                state.suggestions.forEachIndexed { i, suggestion ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(if (i == highlighted) colors.surfaceContainerHighest else Color.Transparent)
                                            .clickable { state.submit(suggestion) }
                                            .padding(horizontal = 18.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                                        Spacer(Modifier.width(14.dp))
                                        Text(suggestion, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchKind.entries.forEach { kind ->
                    FilterChip(
                        selected = state.kind == kind,
                        onClick = { state.selectKind(kind) },
                        label = { Text(kind.title) },
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        }

        val results = state.results
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.error != null -> EmptyState(
                Icons.Rounded.ErrorOutline,
                "Что-то пошло не так",
                state.error.orEmpty(),
            ) { Button(onClick = state::retry) { Text("Повторить") } }
            results.isEmpty() && state.submittedQuery.isNotEmpty() -> EmptyState(
                Icons.Rounded.SearchOff,
                "Ничего не найдено",
                "Попробуйте изменить запрос или поискать среди видео",
            )
            results.isEmpty() -> EmptyState(
                Icons.Rounded.Search,
                "Что послушаем?",
                "Ищите песни и исполнителей или вставьте ссылку на видео YouTube",
            )
            else -> TrackList(
                tracks = results,
                modifier = Modifier.fillMaxSize(),
                onEndReached = state::loadMore,
                onPlay = { i -> player.playRadio(results[i]) },
                header = {
                    Text(
                        "Результаты по запросу «${state.submittedQuery}»",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 8.dp),
                    )
                },
                footer = {
                    if (state.isLoadingMore) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                },
            )
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
}
