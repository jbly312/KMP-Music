package org.example.videoplayerkmp.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class AudioSource(val url: String, val contentLength: Long?)

/**
 * Прямые запросы к внутреннему API YouTube (InnerTube).
 *
 * NewPipe при получении потока делает 5 запросов подряд (~5 с), а нам нужен только один —
 * от клиента visionOS, который отдаёт готовые ссылки без шифрования подписи (~1 с).
 */
internal object InnerTube {
    private val json = Json { ignoreUnknownKeys = true }
    private val visitorLock = Mutex()
    @Volatile private var visitorData: String? = null

    private const val VISIONOS_UA =
        "com.google.visionos.youtube/1.02(RealityDevice14,1; U; CPU visionOS 25_6_0 like Mac OS X; GB)"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    private fun visionOsContext(visitor: String?) = buildJsonObject {
        putJsonObject("client") {
            put("clientName", "VISIONOS")
            put("clientVersion", "1.02")
            put("clientScreen", "WATCH")
            put("platform", "MOBILE")
            put("deviceMake", "Apple")
            put("deviceModel", "RealityDevice14,1")
            put("osName", "visionOS")
            put("osVersion", "25.6.0.23O471")
            put("hl", "en-GB")
            put("gl", "GB")
            put("utcOffsetMinutes", 0)
            if (visitor != null) put("visitorData", visitor)
        }
    }

    private val visionOsHeaders = mapOf("User-Agent" to VISIONOS_UA, "X-Goog-Api-Format-Version" to "2")

    /** M4A-поток трека: AAC-LC 128 kbps (itag 140), иначе любой M4A. */
    suspend fun audioSource(videoId: String): AudioSource = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("context", visionOsContext(visitor()))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val response = post(
            "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&id=$videoId",
            body, visionOsHeaders,
        )
        val status = response.obj("playabilityStatus")?.str("status")
        if (status != "OK") {
            visitorData = null
            throw IOException(response.obj("playabilityStatus")?.str("reason") ?: "Трек недоступен ($status)")
        }
        val formats = response.obj("streamingData")?.get("adaptiveFormats")?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it.str("mimeType")?.startsWith("audio/mp4") == true && it.str("url") != null }
        val format = formats.firstOrNull { it.long("itag") == 140L }
            ?: formats.maxByOrNull { it.long("bitrate") ?: 0 }
            ?: throw IOException("У трека нет совместимого аудиопотока")
        AudioSource(format.str("url")!!, format.str("contentLength")?.toLongOrNull())
    }

    /** «Радио» YouTube Music по треку — то же, что предлагает официальный клиент. */
    suspend fun radio(videoId: String): List<Track> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20251015.03.00")
                    put("hl", "en")
                    put("gl", "US")
                }
            }
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
        }
        val response = post(
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false", body,
            mapOf("User-Agent" to BROWSER_UA, "Origin" to "https://music.youtube.com"),
        )
        buildList { collect(response, "playlistPanelVideoRenderer", this) }
            .mapNotNull { it.toRadioTrack() }
            .distinctBy { it.id }
    }

    private suspend fun visitor(): String? {
        visitorData?.let { return it }
        return visitorLock.withLock {
            visitorData ?: runCatching {
                val body = buildJsonObject { put("context", visionOsContext(null)) }
                post("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false", body, visionOsHeaders)
                    .obj("responseContext")?.str("visitorData")
            }.getOrNull().also { visitorData = it }
        }
    }

    private fun JsonObject.toRadioTrack(): Track? {
        val id = str("videoId") ?: return null
        val title = obj("title")?.runsText() ?: return null
        // longBylineText: «Исполнитель • Альбом • Год»
        val artist = obj("longBylineText")?.get("runs")?.jsonArray?.firstOrNull()?.jsonObject?.str("text").orEmpty()
        val duration = obj("lengthText")?.runsText()?.let(::parseDuration) ?: -1
        val thumbs = obj("thumbnail")?.get("thumbnails")?.jsonArray.orEmpty().map { it.jsonObject }
        val thumb = thumbs.sortedBy { it.long("height") ?: 0 }.firstOrNull { (it.long("height") ?: 0) >= 110 } ?: thumbs.lastOrNull()
        return Track(id, title, artist, duration, thumb?.str("url"))
    }

    private fun parseDuration(text: String): Long? =
        text.split(':').map { it.trim().toLongOrNull() ?: return null }.fold(0L) { acc, part -> acc * 60 + part }

    private fun collect(element: JsonElement, key: String, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> element.forEach { (k, v) ->
                if (k == key && v is JsonObject) out += v else collect(v, key, out)
            }
            is JsonArray -> element.forEach { collect(it, key, out) }
            else -> Unit
        }
    }

    private fun post(url: String, body: JsonObject, headers: Map<String, String>): JsonObject {
        val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        if (connection.responseCode !in 200..299) throw IOException("InnerTube HTTP ${connection.responseCode}")
        return connection.inputStream.use { json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject }
    }

    private fun JsonObject.obj(key: String) = get(key) as? JsonObject
    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.runsText() =
        get("runs")?.jsonArray?.joinToString("") { it.jsonObject.str("text").orEmpty() }?.takeIf { it.isNotEmpty() }
}
