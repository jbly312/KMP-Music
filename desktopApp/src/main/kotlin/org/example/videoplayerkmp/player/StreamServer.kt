package org.example.videoplayerkmp.player

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Локальный HTTP-сервер, через который JavaFX получает ещё не скачанный трек.
 * Отдаёт байты по мере загрузки, поэтому воспроизведение начинается почти сразу.
 *
 * Перематывать такой поток JavaFX не умеет (YouTube отдаёт фрагментированный MP4),
 * поэтому для перемотки плеер переключается на скачанный файл — см. [PlayerController].
 */
class StreamServer(private val cache: AudioCache) {
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-server").apply { isDaemon = true } }
        createContext("/audio/") { handle(it) }
        start()
    }

    /** Расширение .m4a обязательно: по нему JavaFX определяет формат. */
    fun urlFor(id: String) = "http://127.0.0.1:${server.address.port}/audio/$id.m4a"

    fun stop() = server.stop(0)

    private fun handle(exchange: HttpExchange) {
        try {
            val id = exchange.requestURI.path.removePrefix("/audio/").removeSuffix(".m4a")
            val download = cache.open(id)
            val total = download.awaitTotal()

            val range = parseRange(exchange.requestHeaders.getFirst("Range"), total)
            if (range == null) {
                exchange.responseHeaders.add("Content-Range", "bytes */$total")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val (start, end) = range
            val partial = exchange.requestHeaders.containsKey("Range")
            val length = end - start + 1

            exchange.responseHeaders.apply {
                add("Content-Type", "audio/mp4")
                add("Accept-Ranges", "bytes")
                if (partial) add("Content-Range", "bytes $start-$end/$total")
            }
            val code = if (partial) 206 else 200
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.add("Content-Length", length.toString())
                exchange.sendResponseHeaders(code, -1)
                return
            }
            exchange.sendResponseHeaders(code, length)
            exchange.responseBody.use { out -> copyFromFile(download, start, end, out) }
        } catch (_: IOException) {
            // Плеер закрыл соединение (перемотка, смена трека) или загрузка упала
        } catch (e: Exception) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            exchange.close()
        }
    }

    private fun copyFromFile(download: Download, start: Long, end: Long, out: OutputStream) {
        RandomAccessFile(download.file, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var pos = start
            while (pos <= end) {
                val available = download.awaitAvailable(pos)
                val toRead = minOf(buffer.size.toLong(), minOf(available, end + 1) - pos).toInt()
                val n = raf.read(buffer, 0, toRead)
                if (n < 0) throw EOFException()
                out.write(buffer, 0, n)
                pos += n
            }
        }
    }

    /** Разбирает `Range: bytes=start-end`; без заголовка — весь файл. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return 0L to total - 1
        val spec = header.removePrefix("bytes=").substringBefore(',').trim()
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', "")
        val (start, end) = if (startText.isEmpty()) {
            // bytes=-500 — последние 500 байт
            val suffix = endText.toLongOrNull() ?: return null
            (total - suffix).coerceAtLeast(0) to total - 1
        } else {
            val s = startText.toLongOrNull() ?: return null
            s to (endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1))
        }
        return if (start in 0..end) start to end else null
    }
}
