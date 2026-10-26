package org.example.videoplayerkmp.android

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.videoplayerkmp.data.Track
import org.example.videoplayerkmp.data.YouTube
import org.example.videoplayerkmp.player.PlaybackEngine
import org.example.videoplayerkmp.player.perceptualVolume
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Воспроизведение на Android через Media3 ExoPlayer.
 *
 * Каждый трек скачивается целиком в кэш на телефоне (без ограничения размера) и дальше играет
 * без интернета. Ссылку на поток YouTube плеер получает только тогда, когда части трека
 * в кэше нет, — поэтому скачанные треки запускаются и в самолёте.
 *
 * Следующий трек очереди стоит в плейлисте ExoPlayer: плеер сам скачивает его, пока играет
 * текущий, и переключается без паузы. Так между треками он не останавливается — иначе Android
 * считает приложение фоновым и отключает ему сеть.
 */
@OptIn(UnstableApi::class)
class ExoPlayerEngine(context: Context) : PlaybackEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listener: PlaybackEngine.Listener? = null

    /** Не в cacheDir: оттуда система удаляет файлы, когда мало места, а скачанное должно оставаться. */
    private val cache = SimpleCache(File(context.filesDir, "audio"), NoOpCacheEvictor(), StandaloneDatabaseProvider(context))

    private val dataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(
            ResolvingDataSource.Factory(
                DefaultHttpDataSource.Factory().setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000),
                ::resolve,
            )
        )
        // Повреждённый кусок кэша не мешает играть — он скачается заново
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Буфер больше любой песни: плеер качает трек целиком сразу, а не по мере игры
                .setBufferDurationsMs(BUFFER_MS, BUFFER_MS, 1_500, 3_000)
                .setTargetBufferBytes(64 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
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

    private var track: Track? = null
    private var upcoming: Track? = null
    /** ExoPlayer сам перешёл на следующий трек — следующий [load] его не перезапускает. */
    private var autoAdvanced = false
    private var positionJob: Job? = null
    private var retried = false

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> listener?.onBuffering()
                    Player.STATE_READY -> listener?.onReady(durationSec())
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

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) return
                // Для очереди это обычный конец трека: она перейдёт к следующему, а он уже играет
                autoAdvanced = true
                retried = false
                listener?.onEnded()
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
        val advanced = autoAdvanced
        autoAdvanced = false
        if (advanced && player.currentMediaItem?.mediaId == track.id) {
            // Уже играет: убираем закончившийся трек из плейлиста плеера и сообщаем очереди состояние
            if (player.currentMediaItemIndex > 0) player.removeMediaItems(0, player.currentMediaItemIndex)
            player.playWhenReady = autoPlay
            if (player.playbackState == Player.STATE_READY) listener?.onReady(durationSec())
            if (player.isPlaying) listener?.onPlaying()
        } else {
            // Без stop(): плеер сразу переходит к загрузке нового трека и не простаивает
            player.setMediaItem(track.toMediaItem())
            player.playWhenReady = autoPlay
            player.prepare()
        }
        syncUpcoming()
    }

    override fun play() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        // После ошибки (например, не было сети) пробуем ещё раз с того же места
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun seek(seconds: Double) = player.seekTo((seconds * 1000).toLong())

    override fun setVolume(volume: Float) {
        player.volume = perceptualVolume(volume)
    }

    override fun setUpcoming(track: Track?) {
        upcoming = track
        syncUpcoming()
    }

    override fun shutdown() {
        scope.launch {
            player.release()
            cache.release()
        }
    }

    /** Плейлист плеера — текущий трек и следующий из очереди, если он известен. */
    private fun syncUpcoming() {
        if (player.currentMediaItem == null) return
        val next = upcoming?.takeIf { it.id != track?.id }
        val nextIndex = player.currentMediaItemIndex + 1
        if (player.mediaItemCount > nextIndex) {
            // Тот же трек уже стоит следующим — не трогаем, он, возможно, уже скачивается
            if (next != null && player.getMediaItemAt(nextIndex).mediaId == next.id && player.mediaItemCount == nextIndex + 1) return
            player.removeMediaItems(nextIndex, player.mediaItemCount)
        }
        if (next != null) player.addMediaItem(next.toMediaItem())
    }

    /** Ссылку на поток YouTube получаем, только когда нужного куска трека нет в кэше. */
    private fun resolve(dataSpec: DataSpec): DataSpec {
        val id = dataSpec.key ?: dataSpec.uri.lastPathSegment ?: return dataSpec
        val url = try {
            runBlocking { YouTube.audioSource(id).url }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw IOException(e.message, e)
        }
        val resolved = dataSpec.withUri(Uri.parse(url))
        // Без Range YouTube отдаёт поток со скоростью воспроизведения — и плеер спотыкается
        // каждую секунду. С начала файла ExoPlayer Range не ставит, поэтому ставим сами;
        // для остальных запросов он перекрывает наш заголовок своим.
        return if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) {
            resolved.withAdditionalHeaders(mapOf("Range" to "bytes=0-"))
        } else {
            resolved
        }
    }

    private fun onError(error: PlaybackException) {
        val track = track ?: return
        if (!retried) {
            // Чаще всего это протухшая ссылка на поток — один раз пробуем получить новую
            // и продолжаем с того места, где оборвалось
            retried = true
            YouTube.invalidateAudioUrl(player.currentMediaItem?.mediaId ?: track.id)
            player.prepare()
            return
        }
        listener?.onError(describe(error))
    }

    private fun describe(error: PlaybackException): String {
        var cause: Throwable? = error
        while (cause != null) {
            when (cause) {
                is UnknownHostException, is ConnectException -> return "Нет интернета, а этот трек ещё не сохранён на телефоне"
                is SocketTimeoutException -> return "YouTube не отвечает — проверьте интернет"
            }
            cause = cause.cause
        }
        return error.localizedMessage ?: "Не удалось воспроизвести трек"
    }

    private fun durationSec(): Double = player.duration.takeIf { it != C.TIME_UNSET }?.let { it / 1000.0 }
        ?: track?.durationSec?.toDouble() ?: 0.0

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

    private companion object {
        const val BUFFER_MS = 20 * 60 * 1000
    }
}

/**
 * Название, исполнитель и обложка — их показывают уведомление и экран блокировки.
 * Адрес условный: настоящую ссылку подставит [ExoPlayerEngine.resolve], а ключ кэша — id трека,
 * чтобы скачанное находилось, даже когда ссылка YouTube сменилась.
 */
private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri("kmp://track/$id")
    .setMediaId(id)
    .setCustomCacheKey(id)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(thumbnailUrl?.let(Uri::parse))
            .build()
    )
    .build()
