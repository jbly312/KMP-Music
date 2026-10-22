package org.example.videoplayerkmp.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.videoplayerkmp.data.Library
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.YouTube

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val duration: Double = 0.0,
    val volume: Float = 0.7f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val autoplay: Boolean = true,
    val error: String? = null,
    /** Трек, по которому строится радио в конце очереди. */
    val radioSeed: Track? = null,
) {
    val current: Track? get() = queue.getOrNull(index)
    val upcoming: List<Track> get() = if (index < 0) emptyList() else queue.drop(index + 1)
}

/**
 * Очередь, повтор, перемешивание и автоплей. Само воспроизведение — в платформенном [PlaybackEngine].
 *
 * Состояние меняется только в главном потоке. Каждая загрузка трека получает свой номер [generation],
 * чтобы отложенные действия (переход после ошибки, рекомендации) не срабатывали для старого трека.
 */
class PlayerController(
    private val engine: PlaybackEngine,
    private val library: Library,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow(PlayerState(volume = library.data.value.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    /** Позиция в секундах — отдельно, чтобы не перерисовывать весь UI несколько раз в секунду. */
    private val _position = MutableStateFlow(0.0)
    val position: StateFlow<Double> = _position.asStateFlow()

    /** Порядок очереди до включения перемешивания. */
    private var unshuffled: List<Track>? = null

    private var generation = 0
    private var recommendationsJob: Job? = null

    init {
        engine.setListener(object : PlaybackEngine.Listener {
            override fun onReady(durationSec: Double) =
                // При автозапуске буферизацию снимет onPlaying
                _state.update { it.copy(duration = durationSec, isBuffering = it.isPlaying && it.isBuffering) }

            override fun onPlaying() = _state.update { it.copy(isPlaying = true, isBuffering = false) }

            override fun onPaused() = _state.update { it.copy(isPlaying = false, isBuffering = false) }

            override fun onBuffering() = _state.update { it.copy(isBuffering = true) }

            override fun onPosition(seconds: Double) {
                _position.value = seconds
            }

            override fun onEnded() = this@PlayerController.onEnded()

            override fun onError(message: String) = onTrackFailed(generation, message)
        })
        engine.setVolume(_state.value.volume)
    }

    /** Прогрев: JavaFX и NewPipe инициализируются заранее, чтобы первый трек стартовал быстрее. */
    fun warmUp() {
        engine.warmUp()
        scope.launch(Dispatchers.IO) { runCatching { YouTube.init() } }
    }

    // ---------- Очередь ----------

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        recommendationsJob?.cancel()
        _state.update { it.copy(radioSeed = null) }
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        val shuffled = shuffle || _state.value.shuffle
        if (shuffled) {
            unshuffled = tracks
            val rest = tracks.filterIndexed { i, _ -> i != start }.shuffled()
            _state.update { it.copy(queue = listOf(tracks[start]) + rest, index = 0, shuffle = true) }
        } else {
            unshuffled = null
            _state.update { it.copy(queue = tracks, index = start) }
        }
        loadCurrent()
    }

    /** Играет трек и сразу строит по нему радио — так работает клик в поиске. */
    fun playRadio(track: Track) {
        playTracks(listOf(track))
        continueWithRecommendations(playWhenReady = false)
    }

    fun playNext(track: Track) {
        val s = _state.value
        if (s.current == null) return playTracks(listOf(track))
        val queue = s.queue.toMutableList().apply { add(s.index + 1, track) }
        unshuffled = unshuffled?.plus(track)
        _state.update { it.copy(queue = queue) }
        engine.prefetch(track)
    }

    fun addToQueue(track: Track) {
        val s = _state.value
        if (s.current == null) return playTracks(listOf(track))
        unshuffled = unshuffled?.plus(track)
        _state.update { it.copy(queue = it.queue + track) }
    }

    fun removeFromQueue(index: Int) {
        val s = _state.value
        if (index == s.index || index !in s.queue.indices) return
        val removed = s.queue[index]
        unshuffled = unshuffled?.toMutableList()?.apply { remove(removed) }
        _state.update {
            it.copy(
                queue = it.queue.filterIndexed { i, _ -> i != index },
                index = if (index < it.index) it.index - 1 else it.index,
            )
        }
    }

    /** Убирает из очереди всё, что после текущего трека. */
    fun clearUpcoming() {
        val s = _state.value
        val current = s.current ?: return
        unshuffled = null
        _state.update { it.copy(queue = it.queue.take(it.index) + current) }
    }

    fun skipTo(index: Int) {
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        loadCurrent()
    }

    fun next() {
        val s = _state.value
        when {
            s.index < s.queue.lastIndex -> skipTo(s.index + 1)
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> skipTo(0)
            s.autoplay && s.current != null -> continueWithRecommendations(playWhenReady = true)
            else -> {
                // Очередь закончилась: останавливаемся в начале последнего трека
                pause()
                seek(0.0)
            }
        }
    }

    fun previous() {
        val s = _state.value
        if (_position.value > 3 || s.index <= 0) seek(0.0) else skipTo(s.index - 1)
    }

    fun toggleShuffle() {
        val s = _state.value
        val current = s.current
        if (!s.shuffle) {
            unshuffled = s.queue
            val upcoming = s.queue.drop(s.index + 1).shuffled()
            _state.update { it.copy(queue = it.queue.take(it.index + 1) + upcoming, shuffle = true) }
        } else {
            val original = unshuffled
            unshuffled = null
            if (original != null && current != null) {
                val restored = original + s.queue.filter { t -> original.none { it.id == t.id } }
                _state.update {
                    it.copy(queue = restored, index = restored.indexOfFirst { t -> t.id == current.id }, shuffle = false)
                }
            } else {
                _state.update { it.copy(shuffle = false) }
            }
        }
        prefetchNext()
    }

    fun cycleRepeat() = _state.update {
        it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size])
    }

    fun toggleAutoplay() {
        _state.update { it.copy(autoplay = !it.autoplay) }
        ensureUpcoming()
    }

    // ---------- Воспроизведение ----------

    fun togglePlay() {
        val s = _state.value
        when {
            s.current == null -> return
            s.error != null -> loadCurrent()
            s.isPlaying -> pause()
            else -> play()
        }
    }

    fun play() {
        _state.update { it.copy(isPlaying = true) }
        engine.play()
    }

    fun pause() {
        _state.update { it.copy(isPlaying = false) }
        engine.pause()
    }

    fun seek(seconds: Double) {
        if (_state.value.current == null) return
        val target = seconds.coerceIn(0.0, _state.value.duration.coerceAtLeast(0.0))
        _position.value = target
        engine.seek(target)
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        library.setVolume(v)
        engine.setVolume(v)
    }

    fun shutdown() {
        generation++
        engine.shutdown()
    }

    // ---------- Внутренности ----------

    private fun loadCurrent() {
        val track = _state.value.current ?: return
        generation++
        _position.value = 0.0
        _state.update {
            it.copy(isPlaying = true, isBuffering = true, duration = track.durationSec.toDouble(), error = null)
        }
        library.addToHistory(track)
        engine.load(track, autoPlay = true)
        ensureUpcoming()
    }

    private fun onEnded() {
        if (_state.value.repeat == RepeatMode.ONE) {
            seek(0.0)
            play()
        } else {
            next()
        }
    }

    private fun onTrackFailed(gen: Int, message: String?) {
        if (gen != generation) return
        _state.update {
            it.copy(isPlaying = false, isBuffering = false, error = message ?: "Не удалось воспроизвести трек")
        }
        // Не застреваем на битом треке: через пару секунд идём дальше по очереди
        scope.launch {
            delay(2500)
            val s = _state.value
            if (gen == generation && s.error != null && s.index < s.queue.lastIndex) skipTo(s.index + 1)
        }
    }

    /** Подгружает рекомендации, если текущий трек последний и включён автоплей. */
    private fun ensureUpcoming() {
        val s = _state.value
        if (s.autoplay && s.repeat == RepeatMode.OFF && s.current != null && s.index == s.queue.lastIndex) {
            continueWithRecommendations(playWhenReady = false)
        } else {
            prefetchNext()
        }
    }

    private fun continueWithRecommendations(playWhenReady: Boolean) {
        val current = _state.value.current ?: return
        if (recommendationsJob?.isActive == true && !playWhenReady) return
        recommendationsJob?.cancel()
        if (playWhenReady) _state.update { it.copy(isBuffering = true) }
        recommendationsJob = scope.launch {
            val exclude = _state.value.queue.map { it.id }.toSet() +
                library.data.value.history.take(50).map { it.id }
            val tracks = runCatching { withContext(Dispatchers.IO) { YouTube.related(current, exclude) } }
                .getOrDefault(emptyList())
            if (tracks.isEmpty()) {
                if (playWhenReady) _state.update { it.copy(isBuffering = false, isPlaying = false) }
                return@launch
            }
            // Пока грузили, пользователь мог запустить другой список — тогда рекомендации уже не нужны
            if (_state.value.queue.lastOrNull()?.id != current.id) return@launch
            unshuffled = unshuffled?.plus(tracks)
            _state.update { it.copy(queue = it.queue + tracks, radioSeed = current) }
            if (playWhenReady) next() else prefetchNext()
        }
    }

    private fun prefetchNext() {
        val s = _state.value
        s.queue.getOrNull(s.index + 1)?.let(engine::prefetch)
    }
}
