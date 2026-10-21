package org.example.videoplayerkmp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.text.Normalizer
import kotlin.math.abs

data class SpotifyTrack(val title: String, val artists: List<String>, val durationMs: Long)

data class SpotifySource(
    val name: String,
    val tracks: List<SpotifyTrack>,
    /** Встраиваемая страница отдаёт не больше 100 треков — остальное можно взять из CSV. */
    val maybeTruncated: Boolean = false,
)

/** Загрузка плейлистов и альбомов Spotify без ключей API: по публичной ссылке или из CSV. */
object Spotify {
    private const val EMBED_LIMIT = 100
    private val json = Json { ignoreUnknownKeys = true }
    private val linkRegex = Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]+)""")

    fun isLink(text: String) = linkRegex.containsMatchIn(text)

    /**
     * Публичный плейлист или альбом по ссылке. Данные берём со встраиваемой страницы (embed) —
     * ту же страницу Spotify отдаёт сайтам, которые вставляют плеер.
     */
    suspend fun fetch(link: String): SpotifySource = withContext(Dispatchers.IO) {
        val match = linkRegex.find(link) ?: throw IOException("Это не ссылка на плейлист или альбом Spotify")
        val (type, id) = match.destructured
        val connection = (URI("https://open.spotify.com/embed/$type/$id").toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            setRequestProperty("Accept-Language", "en")
        }
        when (connection.responseCode) {
            in 200..299 -> Unit
            404 -> throw IOException("Плейлист не найден. Проверьте, что он публичный")
            else -> throw IOException("Spotify ответил ошибкой ${connection.responseCode}")
        }
        val html = connection.inputStream.use { it.readBytes().decodeToString() }
        val data = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IOException("Не удалось прочитать страницу Spotify")
        val entity = json.parseToJsonElement(data).jsonObject
            .path("props", "pageProps", "state", "data", "entity")
            ?: throw IOException("Плейлист закрыт или недоступен в вашем регионе")

        val tracks = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { element ->
            val track = element as? JsonObject ?: return@mapNotNull null
            val title = track.str("title") ?: return@mapNotNull null
            SpotifyTrack(
                title = title,
                // Исполнители перечислены через запятую с неразрывным пробелом
                artists = track.str("subtitle").orEmpty().split(',').map { it.trim(' ', ' ') }.filter { it.isNotEmpty() },
                durationMs = (track["duration"] as? JsonPrimitive)?.longOrNull ?: -1,
            )
        }
        if (tracks.isEmpty()) throw IOException("В плейлисте нет треков")
        SpotifySource(
            name = entity.str("name") ?: entity.str("title") ?: "Плейлист из Spotify",
            tracks = tracks,
            maybeTruncated = type == "playlist" && tracks.size >= EMBED_LIMIT,
        )
    }

    /**
     * CSV, выгруженный через Exportify (exportify.app) или похожие сервисы.
     * Нужны колонки с названием и исполнителем; длительность — по возможности.
     */
    fun parseCsv(file: File): SpotifySource {
        val rows = parseCsvRows(file.readText().removePrefix("﻿"))
        if (rows.size < 2) throw IOException("Файл пустой")
        val header = rows.first().map { it.trim().lowercase() }
        fun column(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }
            .takeIf { it >= 0 } ?: header.indexOfFirst { h -> names.any { h.contains(it) } }
        val titleCol = column("track name", "title", "name", "название")
        val artistCol = column("artist name(s)", "artist name", "artists", "artist", "исполнитель")
        val durationCol = column("duration (ms)", "duration_ms", "duration")
        if (titleCol < 0 || artistCol < 0) {
            throw IOException("В файле не нашлось колонок с названием трека и исполнителем")
        }
        val tracks = rows.drop(1).mapNotNull { row ->
            val title = row.getOrNull(titleCol)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SpotifyTrack(
                title = title,
                // Exportify разделяет исполнителей запятой, некоторые сервисы — точкой с запятой
                artists = row.getOrNull(artistCol).orEmpty().split(',', ';').map { it.trim() }.filter { it.isNotEmpty() },
                durationMs = durationCol.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim()?.toLongOrNull() } ?: -1,
            )
        }
        if (tracks.isEmpty()) throw IOException("В файле нет треков")
        return SpotifySource(file.nameWithoutExtension.replace('_', ' '), tracks)
    }

    /** CSV по RFC 4180: запятые и переводы строк внутри кавычек, "" — экранированная кавычка. */
    private fun parseCsvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += field.toString(); field.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += field.toString(); field.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = mutableListOf()
                }
                else -> field.append(c)
            }
            i++
        }
        row += field.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    private fun JsonObject.path(vararg keys: String): JsonObject? =
        keys.fold(this as JsonObject?) { acc, key -> acc?.get(key) as? JsonObject }

    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
}

/**
 * Ищет трек Spotify на YouTube. Сначала среди песен YouTube Music, а если там подходящего нет
 * (часть каталога крупных лейблов закрыта по регионам) — среди обычных видео, где обычно
 * есть официальный клип или аудио.
 */
object TrackMatcher {
    /**
     * Найденный трек хранит название и исполнителя из Spotify (они чище, чем у загрузок на YouTube),
     * а id, обложку и длительность — от найденного видео.
     */
    suspend fun match(track: SpotifyTrack): Track? {
        val query = "${track.artists.firstOrNull().orEmpty()} ${cleanTitle(track.title)}".trim()
        val found = best(track, query, SearchKind.SONGS) ?: best(track, query, SearchKind.VIDEOS) ?: return null
        return found.copy(title = track.title, artist = track.artists.joinToString(", ").ifEmpty { found.artist })
    }

    private suspend fun best(track: SpotifyTrack, query: String, kind: SearchKind): Track? {
        val candidates = runCatching { YouTube.search(query, kind).tracks.take(8) }.getOrDefault(emptyList())
        return candidates
            .map { it to score(track, it, isVideo = kind == SearchKind.VIDEOS) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Сходство названий, обязательное совпадение исполнителя и длительность.
     * Чужие версии (караоке, каверы, slowed, live…) отбрасываются сразу.
     */
    private fun score(source: SpotifyTrack, candidate: Track, isVideo: Boolean): Double {
        val sourceWords = words(cleanTitle(source.title))
        val candidateTitleWords = words(cleanTitle(candidate.title))
        // Проверяем полное название: очистка срезала бы «- Acoustic Version»
        val allSourceWords = words(source.title)
        if (words(candidate.title).any { word -> UNWANTED.any { word.startsWith(it) } && word !in allSourceWords }) return 0.0

        val diff = if (source.durationMs <= 0 || candidate.durationSec <= 0) null else abs(source.durationMs / 1000 - candidate.durationSec)
        // Другая длительность — почти наверняка другая версия (концерт, расширенный микс)
        if (diff != null && diff > if (isVideo) 45 else 20) return 0.0

        // У видео исполнитель часто записан в названии («Artist - Title») или в имени канала («ArtistVEVO»)
        val artistWords = source.artists.flatMap { words(it) }.toSet()
        val haystack = compact(candidate.artist + " " + if (isVideo) candidate.title else "")
        val artistMatches = source.artists.any { artist -> compact(artist).let { it.isNotEmpty() && haystack.contains(it) } }
        if (!artistMatches) return 0.0

        val compared = candidateTitleWords - artistWords - (if (isVideo) DECORATIONS else emptySet())
        if (sourceWords.isEmpty() || compared.isEmpty()) return 0.0
        val coverage = (sourceWords intersect compared).size.toDouble() / sourceWords.size
        val extra = (compared - sourceWords).size.toDouble() / compared.size
        // Лишние слова (даты, площадки, «mix») сильно снижают уверенность
        val titleScore = coverage - extra * 1.2

        val durationScore = when {
            diff == null -> 0.5
            diff <= 3 -> 1.0
            diff <= 8 -> 0.7
            // У клипов бывают вступления и титры
            isVideo && diff <= 45 -> 0.5
            else -> 0.2
        }
        return titleScore * 0.5 + 0.3 + durationScore * 0.2
    }

    /** Убираем приписки, которых часто нет на YouTube: «feat.», «Remastered», «Radio Edit». */
    private fun cleanTitle(title: String): String = title
        .replace(Regex("""\s*[(\[](feat\.?|ft\.?|with|from)\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+-\s+.*(remaster|version|edit|mono|stereo|from ).*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    /** Нижний регистр, без диакритики (ADÉLA → adela) и знаков препинания. */
    private fun normalize(text: String) = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .trim()

    private fun words(text: String) = normalize(text).split(' ').filter { it.isNotEmpty() }.toSet()

    /** Без пробелов: «Sabrina Carpenter» находится в «SabrinaCarpenterVEVO». */
    private fun compact(text: String) = normalize(text).replace(" ", "")

    private val UNWANTED = setOf(
        "karaoke", "cover", "slowed", "sped", "nightcore", "reverb", "boosted", "8d", "instrumental",
        "live", "remix", "acoustic", "piano", "mashup", "reaction", "tutorial", "loop", "hour", "sing",
    )

    /** Обычные приписки к официальным видео — не считаем их лишними словами. */
    private val DECORATIONS = setOf(
        "official", "video", "music", "audio", "lyric", "lyrics", "visualizer", "visualiser",
        "hd", "hq", "4k", "mv", "clip", "feat", "ft", "vevo", "topic",
    )

    private const val MIN_SCORE = 0.72
}
