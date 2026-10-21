package org.example.videoplayerkmp.player

import javafx.application.Platform
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.util.Duration
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.runInterruptible
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

/** JavaFX-тулкит можно запустить только один раз за жизнь процесса. */
private object Fx {
    private var started = false

    @Synchronized
    private fun ensureStarted() {
        if (started) return
        Platform.setImplicitExit(false)
        Platform.startup {}
        started = true
    }

    fun run(block: () -> Unit) {
        ensureStarted()
        Platform.runLater(block)
    }

    fun warmUp() = ensureStarted()
}

/**
 * Очередь и воспроизведение.
 *
 * Состояние меняется только в главном потоке (Swing), [MediaPlayer] трогается только в потоке JavaFX.
 * Каждая загрузка трека получает свой номер [generation], чтобы колбэки от старого плеера игнорировались.
 */
class PlayerController(
    private val cache: AudioCache,
    private val server: StreamServer,
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

    @Volatile private var generation = 0
    private var loadJob: Job? = null
    private var seekJob: Job? = null
    private var recommendationsJob: Job? = null
    private var retried = false

    /** Источник текущего плеера: поток через прокси (не перематывается) или файл. */
    private var playingFromStream = false

    /** Куда перемотать, когда докачается файл; пока не null — старый плеер стоит на паузе. */
    @Volatile private var pendingSeek: Double? = null
    private var seekResume = false

    // Только поток JavaFX
    private var player: MediaPlayer? = null

    /** Прогрев: JavaFX и NewPipe инициализируются заранее, чтобы первый трек стартовал быстрее. */
    fun warmUp() {
        scope.launch(Dispatchers.IO) {
            Fx.warmUp()
            runCatching { YouTube.init() }
        }
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
        cache.prefetch(track.id)
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
        if (pendingSeek != null) seekResume = true else Fx.run { player?.play() }
    }

    fun pause() {
        _state.update { it.copy(isPlaying = false) }
        if (pendingSeek != null) seekResume = false else Fx.run { player?.pause() }
    }

    fun seek(seconds: Double) {
        val track = _state.value.current ?: return
        val target = seconds.coerceIn(0.0, _state.value.duration.coerceAtLeast(0.0))
        _position.value = target
        if (!playingFromStream) {
            Fx.run { player?.seek(Duration.seconds(target)) }
            return
        }
        // Поток не перематывается: дожидаемся загрузки файла и переключаемся на него
        val gen = generation
        val resume = if (pendingSeek != null) seekResume else _state.value.isPlaying
        seekResume = resume
        pendingSeek = target
        if (seekJob?.isActive == true) return
        Fx.run { player?.pause() }
        _state.update { it.copy(isBuffering = true) }
        seekJob = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) { cache.open(track.id).awaitComplete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch onTrackFailed(gen, e.message)
            }
            if (gen != generation) return@launch
            val at = pendingSeek ?: _position.value
            createPlayer(gen, cache.open(track.id).file.toURI().toString(), fromStream = false, startAt = at, autoPlay = seekResume)
        }
    }

    fun setVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v) }
        library.setVolume(v)
        Fx.run { player?.volume = perceptual(v) }
    }

    fun shutdown() {
        generation++
        Fx.run {
            player?.dispose()
            player = null
            Platform.exit()
        }
    }

    // ---------- Внутренности ----------

    private fun loadCurrent() {
        val track = _state.value.current ?: return
        val gen = ++generation
        loadJob?.cancel()
        seekJob?.cancel()
        pendingSeek = null
        retried = false
        _position.value = 0.0
        _state.update {
            it.copy(isPlaying = true, isBuffering = true, duration = track.durationSec.toDouble(), error = null)
        }
        library.addToHistory(track)
        cache.playingId = track.id
        releasePlayer()

        loadJob = scope.launch {
            val fromStream = !cache.isCached(track.id)
            try {
                // Дожидаемся, пока загрузка узнает размер трека, — значит, поток доступен
                runInterruptible(Dispatchers.IO) { cache.open(track.id).awaitTotal() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch onTrackFailed(gen, e.message)
            }
            if (gen != generation) return@launch
            val uri = if (fromStream) server.urlFor(track.id) else cache.open(track.id).file.toURI().toString()
            createPlayer(gen, uri, fromStream, startAt = 0.0)
            ensureUpcoming()
        }
    }

    private fun createPlayer(
        gen: Int,
        uri: String,
        fromStream: Boolean,
        startAt: Double,
        autoPlay: Boolean = _state.value.isPlaying,
    ) {
        playingFromStream = fromStream
        val volume = perceptual(_state.value.volume)
        Fx.run {
            if (gen != generation) return@run
            player?.dispose()
            player = null
            try {
                val media = Media(uri)
                val p = MediaPlayer(media)
                p.volume = volume
                p.setOnReady {
                    if (startAt > 0) p.seek(Duration.seconds(startAt))
                    if (autoPlay) p.play()
                    val duration = media.duration.toSeconds()
                    onMain(gen) {
                        pendingSeek = null
                        _position.value = startAt
                        // При автозапуске буферизацию снимет onPlaying
                        _state.update { it.copy(duration = duration, isBuffering = autoPlay && it.isBuffering) }
                    }
                }
                p.setOnPlaying { onMain(gen) { _state.update { it.copy(isPlaying = true, isBuffering = false) } } }
                p.setOnPaused {
                    // Пауза на время переключения на файл — не пользовательская
                    onMain(gen) { if (pendingSeek == null) _state.update { it.copy(isPlaying = false, isBuffering = false) } }
                }
                p.setOnStalled { onMain(gen) { _state.update { it.copy(isBuffering = true) } } }
                p.setOnEndOfMedia { onMain(gen) { onEnded() } }
                p.setOnError { onMain(gen) { onPlayerError(gen, p.error?.message) } }
                p.currentTimeProperty().addListener { _, _, t ->
                    if (gen == generation && pendingSeek == null) _position.value = t.toSeconds()
                }
                player = p
            } catch (e: Exception) {
                onMain(gen) { onPlayerError(gen, e.message) }
            }
        }
    }

    private fun releasePlayer() = Fx.run {
        player?.dispose()
        player = null
    }

    private fun onEnded() {
        if (_state.value.repeat == RepeatMode.ONE) {
            seek(0.0)
            play()
        } else {
            next()
        }
    }

    private fun onPlayerError(gen: Int, message: String?) {
        val track = _state.value.current ?: return
        if (!retried) {
            // Чаще всего это протухшая ссылка или битый файл в кэше — пробуем один раз заново
            retried = true
            YouTube.invalidateAudioUrl(track.id)
            cache.discard(track.id)
            val retryGen = ++generation
            releasePlayer()
            loadJob = scope.launch {
                try {
                    runInterruptible(Dispatchers.IO) { cache.open(track.id).awaitTotal() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch onTrackFailed(retryGen, e.message)
                }
                if (retryGen == generation) createPlayer(retryGen, server.urlFor(track.id), fromStream = true, startAt = 0.0)
            }
            return
        }
        onTrackFailed(gen, message)
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
        s.queue.getOrNull(s.index + 1)?.let { cache.prefetch(it.id) }
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }

    /** Громкость на слух меняется нелинейно — квадрат даёт более ровную шкалу. */
    private fun perceptual(v: Float) = (v * v).toDouble()
}
