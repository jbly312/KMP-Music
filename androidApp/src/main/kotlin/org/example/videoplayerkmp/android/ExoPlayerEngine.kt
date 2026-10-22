package org.example.videoplayerkmp.android

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.YouTube
import org.example.videoplayerkmp.player.PlaybackEngine
import org.example.videoplayerkmp.player.perceptualVolume

/**
 * Воспроизведение на Android через Media3 ExoPlayer.
 *
 * В отличие от ПК, здесь не нужен ни локальный прокси, ни кэш: ExoPlayer сам играет поток YouTube
 * по мере загрузки и умеет перематывать фрагментированный MP4. Запрос `Range: bytes=0-` YouTube
 * отдаёт на полной скорости, так что стандартного HTTP-источника хватает.
 */
class ExoPlayerEngine(context: Context) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: PlaybackEngine.Listener? = null

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            // Пауза при звонке и уступка звука другим приложениям
            true,
        )
        // Пауза, когда отключили наушники
        .setHandleAudioBecomingNoisy(true)
        // Не даём системе усыпить сеть и процессор, пока играет музыка
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private var generation = 0
    private var track: Track? = null
    private var loadJob: Job? = null
    private var positionJob: Job? = null
    private var retried = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> listener?.onReady(player.duration.coerceAtLeast(0) / 1000.0)
                    Player.STATE_ENDED -> listener?.onEnded()
                    else -> Unit
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    listener?.onPlaying()
                    startPositionUpdates()
                } else {
                    positionJob?.cancel()
                    listener?.onPosition(player.currentPosition / 1000.0)
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Пауза пользователем, звонком или отключением наушников
                if (!playWhenReady) listener?.onPaused()
            }

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
    }

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun load(track: Track, autoPlay: Boolean) {
        this.track = track
        retried = false
        player.stop()
        player.clearMediaItems()
        player.playWhenReady = autoPlay
        start(++generation, track, forceRefresh = false)
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    override fun pause() = player.pause()

    override fun seek(seconds: Double) = player.seekTo((seconds * 1000).toLong())

    override fun setVolume(volume: Float) {
        player.volume = perceptualVolume(volume)
    }

    /** Ссылка на поток получается заранее — переход на следующий трек не ждёт запроса к YouTube. */
    override fun prefetch(track: Track) {
        scope.launch(Dispatchers.IO) { runCatching { YouTube.audioSource(track.id) } }
    }

    override fun shutdown() {
        generation++
        scope.launch { player.release() }
    }

    private fun start(gen: Int, track: Track, forceRefresh: Boolean, startAtMs: Long = 0) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val url = try {
                withContext(Dispatchers.IO) { YouTube.audioSource(track.id, forceRefresh).url }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) listener?.onError(e.message ?: "Не удалось получить поток")
                return@launch
            }
            if (gen != generation) return@launch
            player.setMediaItem(track.toMediaItem(url), startAtMs)
            player.prepare()
        }
    }

    private fun onError(error: PlaybackException) {
        val track = track ?: return
        if (!retried) {
            // Чаще всего это протухшая ссылка на поток — один раз пробуем получить новую
            retried = true
            YouTube.invalidateAudioUrl(track.id)
            // Продолжаем с того места, где оборвалось
            start(++generation, track, forceRefresh = true, startAtMs = player.currentPosition)
            return
        }
        listener?.onError(error.localizedMessage ?: "Не удалось воспроизвести трек")
    }

    /** У ExoPlayer нет события «позиция изменилась» — опрашиваем, пока играет. */
    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = scope.launch {
            while (isActive) {
                listener?.onPosition(player.currentPosition / 1000.0)
                delay(250)
            }
        }
    }
}

/** Название, исполнитель и обложка — их показывают уведомление и экран блокировки. */
private fun Track.toMediaItem(url: String): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(thumbnailUrl?.let(Uri::parse))
            .build()
    )
    .build()
