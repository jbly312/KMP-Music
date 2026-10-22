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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.YouTube
import kotlin.math.abs

/** JavaFX-тулкит можно запустить только один раз за жизнь процесса. */
private object Fx {
    private var started = false

    @Synchronized
    fun ensureStarted() {
        if (started) return
        Platform.setImplicitExit(false)
        Platform.startup {}
        started = true
    }

    fun run(block: () -> Unit) {
        ensureStarted()
        Platform.runLater(block)
    }
}

/**
 * Воспроизведение на ПК через JavaFX Media.
 *
 * Трек начинает играть сразу — через локальный [StreamServer], пока [AudioCache] докачивает его в фоне.
 * Перематывать такой поток JavaFX не умеет (YouTube отдаёт фрагментированный MP4), поэтому при перемотке
 * движок дожидается файла и переключается на него.
 *
 * Состояние меняется в главном потоке (Swing), [MediaPlayer] трогается только в потоке JavaFX.
 * Каждая загрузка получает свой номер [generation], чтобы события старого плеера отбрасывались.
 */
class JavaFxEngine(
    private val cache: AudioCache,
    private val server: StreamServer,
) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: PlaybackEngine.Listener? = null

    @Volatile private var generation = 0
    private var track: Track? = null
    private var loadJob: Job? = null
    private var seekJob: Job? = null
    private var retried = false
    private var volume = 0.7f

    /** Играем ли мы из потока через прокси (он не перематывается) или из файла. */
    private var playingFromStream = false

    /** Куда перемотать, когда докачается файл; пока не null — старый плеер стоит на паузе. */
    @Volatile private var pendingSeek: Double? = null

    /** Хочет ли пользователь, чтобы трек играл, — в том числе пока ждём файл для перемотки. */
    @Volatile private var wantPlaying = false

    // Только поток JavaFX
    private var player: MediaPlayer? = null

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun warmUp() {
        scope.launch(Dispatchers.IO) { Fx.ensureStarted() }
    }

    override fun load(track: Track, autoPlay: Boolean) {
        this.track = track
        val gen = ++generation
        loadJob?.cancel()
        seekJob?.cancel()
        pendingSeek = null
        retried = false
        wantPlaying = autoPlay
        cache.playingId = track.id
        releasePlayer()
        startStream(gen, track)
    }

    override fun play() {
        wantPlaying = true
        if (pendingSeek == null) Fx.run { player?.play() }
    }

    override fun pause() {
        wantPlaying = false
        if (pendingSeek == null) Fx.run { player?.pause() }
    }

    override fun seek(seconds: Double) {
        val track = track ?: return
        if (!playingFromStream) {
            Fx.run { player?.seek(Duration.seconds(seconds)) }
            return
        }
        // Поток не перематывается: дожидаемся загрузки файла и переключаемся на него
        val gen = generation
        pendingSeek = seconds
        if (seekJob?.isActive == true) return
        Fx.run { player?.pause() }
        listener?.onBuffering()
        seekJob = scope.launch {
            try {
                runInterruptible(Dispatchers.IO) { cache.open(track.id).awaitComplete() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch fail(gen, e.message)
            }
            if (gen != generation) return@launch
            createPlayer(gen, cache.open(track.id).file.toURI().toString(), fromStream = false, startAt = pendingSeek ?: seconds)
        }
    }

    override fun setVolume(volume: Float) {
        this.volume = volume
        Fx.run { player?.volume = perceptualVolume(volume).toDouble() }
    }

    override fun prefetch(track: Track) = cache.prefetch(track.id)

    override fun shutdown() {
        generation++
        Fx.run {
            player?.dispose()
            player = null
            Platform.exit()
        }
    }

    private fun startStream(gen: Int, track: Track) {
        loadJob = scope.launch {
            val fromStream = !cache.isCached(track.id)
            try {
                // Дожидаемся, пока загрузка узнает размер трека, — значит, поток доступен
                runInterruptible(Dispatchers.IO) { cache.open(track.id).awaitTotal() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch fail(gen, e.message)
            }
            if (gen != generation) return@launch
            val uri = if (fromStream) server.urlFor(track.id) else cache.open(track.id).file.toURI().toString()
            createPlayer(gen, uri, fromStream, startAt = 0.0)
        }
    }

    private fun createPlayer(gen: Int, uri: String, fromStream: Boolean, startAt: Double) {
        playingFromStream = fromStream
        val volume = perceptualVolume(volume).toDouble()
        Fx.run {
            if (gen != generation) return@run
            player?.dispose()
            player = null
            try {
                val media = Media(uri)
                val p = MediaPlayer(media)
                p.volume = volume
                var lastReported = -1.0
                p.setOnReady {
                    if (startAt > 0) p.seek(Duration.seconds(startAt))
                    if (wantPlaying) p.play()
                    val duration = media.duration.toSeconds()
                    onMain(gen) {
                        pendingSeek = null
                        listener?.onPosition(startAt)
                        listener?.onReady(duration)
                    }
                }
                p.setOnPlaying { onMain(gen) { listener?.onPlaying() } }
                p.setOnPaused {
                    // Пауза на время переключения на файл — не пользовательская
                    onMain(gen) { if (pendingSeek == null) listener?.onPaused() }
                }
                p.setOnStalled { onMain(gen) { listener?.onBuffering() } }
                p.setOnEndOfMedia { onMain(gen) { listener?.onEnded() } }
                p.setOnError { onMain(gen) { onPlayerError(gen, p.error?.message) } }
                p.currentTimeProperty().addListener { _, _, t ->
                    val seconds = t.toSeconds()
                    // JavaFX сообщает время очень часто — интерфейсу хватает нескольких раз в секунду
                    if (gen == generation && pendingSeek == null && abs(seconds - lastReported) >= 0.2) {
                        lastReported = seconds
                        onMain(gen) { if (pendingSeek == null) listener?.onPosition(seconds) }
                    }
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

    private fun onPlayerError(gen: Int, message: String?) {
        val track = track ?: return
        if (gen != generation) return
        if (!retried) {
            // Чаще всего это протухшая ссылка или битый файл в кэше — пробуем один раз заново
            retried = true
            YouTube.invalidateAudioUrl(track.id)
            cache.discard(track.id)
            val retryGen = ++generation
            releasePlayer()
            startStream(retryGen, track)
            return
        }
        fail(gen, message)
    }

    private fun fail(gen: Int, message: String?) {
        if (gen == generation) listener?.onError(message ?: "Не удалось воспроизвести трек")
    }

    private fun onMain(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }
}
