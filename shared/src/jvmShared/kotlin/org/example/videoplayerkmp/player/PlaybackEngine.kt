package org.example.videoplayerkmp.player

import org.example.videoplayerkmp.data.Track

/**
 * Платформенное воспроизведение одного трека: JavaFX на ПК, Media3 на Android.
 * Очередь, повтор и автоплей — забота [PlayerController], движок знает только текущий трек.
 *
 * Методы вызываются из главного потока. События [Listener] движок тоже отдаёт в главном потоке
 * и только для последнего загруженного трека — события от уже выгруженных он отбрасывает сам.
 */
interface PlaybackEngine {
    fun setListener(listener: Listener)

    /** Выгружает текущий трек и загружает [track]; при [autoPlay] сразу запускает. */
    fun load(track: Track, autoPlay: Boolean)

    fun play()

    fun pause()

    fun seek(seconds: Double)

    /** Громкость 0..1 в том виде, в каком её показывает интерфейс. */
    fun setVolume(volume: Float)

    /** Подготовить трек заранее, чтобы переход на него был мгновенным. */
    fun prefetch(track: Track) {}

    /**
     * Трек, который пойдёт после текущего, или null (конец очереди, повтор одного трека).
     * Движок может переключиться на него сам, без паузы между треками: тогда он сообщает
     * [Listener.onEnded], а следующий [load] этого же трека просто продолжает игру.
     */
    fun setUpcoming(track: Track?) {}

    /** Подготовить движок до первого трека — например, запустить тяжёлые библиотеки. */
    fun warmUp() {}

    fun shutdown()

    interface Listener {
        fun onReady(durationSec: Double)

        fun onPlaying()

        fun onPaused()

        fun onBuffering()

        fun onPosition(seconds: Double)

        fun onEnded()

        /** Трек воспроизвести не удалось — движок уже сделал всё, что мог (в том числе повторы). */
        fun onError(message: String)
    }
}

/** Громкость на слух меняется нелинейно — квадрат даёт более ровную шкалу. */
fun perceptualVolume(volume: Float): Float = volume * volume
