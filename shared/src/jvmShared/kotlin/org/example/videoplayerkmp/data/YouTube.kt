package org.example.videoplayerkmp.data

import com.yushosei.newpipe.extractor.Image
import com.yushosei.newpipe.extractor.MediaFormat
import com.yushosei.newpipe.extractor.NewPipe
import com.yushosei.newpipe.extractor.Page
import com.yushosei.newpipe.extractor.ServiceList
import com.yushosei.newpipe.extractor.stream.AudioStream
import com.yushosei.newpipe.extractor.stream.StreamInfoItem
import com.yushosei.newpipe.extractor.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import com.yushosei.newpipe.util.DefaultDownloaderImpl
import com.yushosei.newpipe.util.ExtractorHelper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class SearchKind(val filter: String, val title: String) {
    SONGS(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, "Песни"),
    VIDEOS(YoutubeSearchQueryHandlerFactory.VIDEOS, "Видео"),
}

class SearchPage(val tracks: List<Track>, val next: Page?)

/** Всё общение с YouTube через NewPipe Extractor. */
object YouTube {
    private val serviceId get() = ServiceList.YouTube.serviceId
    private val initLock = Mutex()
    @Volatile private var initialized = false

    /** id трека -> (поток, момент, когда ссылка протухнет) */
    private val audioSources = ConcurrentHashMap<String, Pair<AudioSource, Long>>()

    suspend fun init() {
        if (initialized) return
        initLock.withLock {
            if (!initialized) {
                NewPipe.init(DefaultDownloaderImpl.initDefault())
                initialized = true
            }
        }
    }

    suspend fun search(query: String, kind: SearchKind): SearchPage {
        init()
        val info = ExtractorHelper.searchFor(serviceId, query, listOf(kind.filter), "")
        return SearchPage(info.relatedItems.toTracks(), info.nextPage)
    }

    suspend fun searchMore(query: String, kind: SearchKind, page: Page): SearchPage {
        init()
        val result = ExtractorHelper.getMoreSearchItems(serviceId, query, listOf(kind.filter), "", page)
        return SearchPage(result.items.toTracks(), result.nextPage)
    }

    suspend fun suggestions(query: String): List<String> {
        init()
        return ExtractorHelper.suggestionsFor(serviceId, query)
    }

    /** Трек по ссылке на видео. */
    suspend fun track(url: String): Track {
        init()
        val info = ExtractorHelper.getStreamInfo(serviceId, url)
        return Track(info.id, info.name, info.uploaderName, info.duration, info.thumbnails.pickThumbnail())
    }

    /**
     * Прямая ссылка на M4A-поток трека.
     * Ссылки YouTube действуют ~6 часов (параметр expire), поэтому кэшируем их с запасом.
     */
    suspend fun audioSource(id: String, forceRefresh: Boolean = false): AudioSource {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            audioSources[id]?.let { (source, expiresAt) -> if (expiresAt > now) return source }
        }
        val source = runCatching { InnerTube.audioSource(id) }.getOrElse { audioSourceViaNewPipe(id) }
        val expiresAt = Regex("""[?&]expire=(\d+)""").find(source.url)?.groupValues?.get(1)?.toLongOrNull()
            ?.let { it * 1000 - 10 * 60_000 }
            ?: (now + 60 * 60_000)
        audioSources[id] = source to expiresAt
        return source
    }

    /** Запасной путь — медленнее (~5 с), зато NewPipe сам следит за изменениями YouTube. */
    private suspend fun audioSourceViaNewPipe(id: String): AudioSource {
        init()
        val info = ExtractorHelper.getStreamInfo(serviceId, "https://www.youtube.com/watch?v=$id", forceLoad = true)
        val stream = pickAudioStream(info.audioStreams) ?: error("У трека нет совместимого аудиопотока")
        return AudioSource(stream.content, null)
    }

    fun invalidateAudioUrl(id: String) {
        audioSources.remove(id)
    }

    /**
     * Треки для автоплея: «радио» YouTube Music по текущему треку,
     * а если оно недоступно — песни того же исполнителя.
     */
    suspend fun related(track: Track, exclude: Set<String>): List<Track> {
        val radio = runCatching { InnerTube.radio(track.id) }.getOrDefault(emptyList())
        val candidates = radio.ifEmpty {
            runCatching { search(track.artist, SearchKind.SONGS).tracks.shuffled() }.getOrDefault(emptyList())
        }
        return candidates
            .filter { it.id !in exclude && it.id != track.id && it.durationSec in 1..15 * 60 }
            .distinctBy { "${it.artist}|${it.title}".lowercase() }
            .take(15)
    }

    private fun List<*>.toTracks(): List<Track> = mapNotNull { item ->
        val stream = item as? StreamInfoItem ?: return@mapNotNull null
        val id = extractVideoId(stream.url) ?: return@mapNotNull null
        Track(
            id = id,
            // У некоторых видео в названии оказывается служебная вторая строка
            title = stream.name.substringBefore('\n').trim(),
            artist = stream.uploaderName.orEmpty(),
            durationSec = stream.duration,
            thumbnailUrl = stream.thumbnails.pickThumbnail(),
        )
    }

    /** Самая маленькая обложка, которая не будет мылом в списке и плеере. */
    private fun List<Image>.pickThumbnail(): String? =
        sortedBy { it.height }.firstOrNull { it.height >= 110 }?.url ?: maxByOrNull { it.height }?.url
}

/**
 * JavaFX Media не умеет WebM/Opus, поэтому берём только M4A (AAC),
 * предпочитая AAC-LC (mp4a.40.2) с максимальным битрейтом.
 */
fun pickAudioStream(streams: List<AudioStream>): AudioStream? =
    streams
        .filter { it.format == MediaFormat.M4A }
        .maxWithOrNull(compareBy<AudioStream>({ it.codec == "mp4a.40.2" }, { it.averageBitrate }))
