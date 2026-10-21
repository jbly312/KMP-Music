package org.example.videoplayerkmp.player

import kotlinx.coroutines.runBlocking
import org.example.videoplayerkmp.data.YouTube
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Фоновая загрузка трека на диск. Читатели могут начинать читать файл,
 * не дожидаясь конца загрузки — [awaitAvailable] блокирует до появления нужных байтов.
 */
class Download internal constructor(val id: String, val file: File) {
    private val lock = Object() as java.lang.Object
    private var signals = 0L

    @Volatile var total: Long = -1; private set
    @Volatile var available: Long = 0; private set
    @Volatile var isComplete = false; private set
    @Volatile var error: Throwable? = null; private set

    /** Размер трека в байтах; ждёт, пока загрузка его узнает. */
    fun awaitTotal(): Long = synchronized(lock) {
        while (total < 0) {
            error?.let { throw IOException(it.message, it) }
            waitStep()
        }
        total
    }

    /** Ждёт окончания загрузки. */
    fun awaitComplete() = synchronized(lock) {
        while (!isComplete) {
            error?.let { throw IOException(it.message, it) }
            waitStep()
        }
    }

    /** Ждёт, пока байт [pos] окажется на диске; возвращает, сколько байтов доступно. */
    fun awaitAvailable(pos: Long): Long = synchronized(lock) {
        while (available <= pos && !isComplete) {
            error?.let { throw IOException(it.message, it) }
            waitStep()
        }
        available
    }

    private fun waitStep() {
        val before = signals
        lock.wait(STALL_TIMEOUT_MS)
        if (signals == before && !isComplete && error == null) {
            throw IOException("Загрузка трека зависла")
        }
    }

    internal fun setTotal(value: Long) = signal { total = value }
    internal fun advance(value: Long) = signal { available = value }
    internal fun complete() = signal { available = total; isComplete = true }
    internal fun fail(e: Throwable) = signal { error = e }

    private inline fun signal(block: () -> Unit) = synchronized(lock) {
        block()
        signals++
        lock.notifyAll()
    }

    internal companion object {
        const val STALL_TIMEOUT_MS = 30_000L

        fun completed(id: String, file: File) = Download(id, file).apply {
            total = file.length()
            available = total
            isComplete = true
        }
    }
}

/**
 * Дисковый кэш треков (LRU по времени последнего использования).
 * Трек `<id>.m4a` считается целым, только если рядом есть маркер `<id>.done`.
 */
class AudioCache(private val dir: File, private val maxBytes: Long = 1L shl 30) {
    private val downloads = ConcurrentHashMap<String, Download>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "audio-download").apply { isDaemon = true }
    }

    /** Трек, который сейчас играет, — его нельзя вытеснять из кэша. */
    @Volatile var playingId: String? = null

    init {
        dir.mkdirs()
        // Недокачанные в прошлый раз файлы бесполезны
        dir.listFiles { f -> f.extension == "m4a" && !doneFile(f.nameWithoutExtension).exists() }
            ?.forEach { it.delete() }
        executor.execute { evict() }
    }

    private fun dataFile(id: String) = File(dir, "$id.m4a")
    private fun doneFile(id: String) = File(dir, "$id.done")

    fun isCached(id: String) = doneFile(id).exists() && dataFile(id).exists()

    /** Готовый трек из кэша или запущенная (либо уже идущая) загрузка. */
    fun open(id: String): Download = downloads.compute(id) { _, existing ->
        when {
            existing != null && existing.error == null && (!existing.isComplete || existing.file.exists()) -> existing
            isCached(id) -> Download.completed(id, dataFile(id))
            else -> Download(id, dataFile(id)).also { executor.execute { download(it) } }
        }.also { it.file.setLastModified(System.currentTimeMillis()) }
    }!!

    /** Заранее скачивает трек, чтобы переход на него был мгновенным. */
    fun prefetch(id: String) {
        open(id)
    }

    /** Выбрасывает трек из кэша (например, если файл оказался битым). */
    fun discard(id: String) {
        downloads.remove(id)
        doneFile(id).delete()
        dataFile(id).delete()
    }

    private fun download(d: Download) {
        try {
            val source = runBlocking { YouTube.audioSource(d.id) }
            var url = source.url
            val total = source.contentLength ?: probeSize(url) ?: run {
                // Ссылка могла протухнуть — получаем новую
                url = runBlocking { YouTube.audioSource(d.id, forceRefresh = true) }.url
                probeSize(url) ?: throw IOException("Сервер не отдал размер трека")
            }
            RandomAccessFile(d.file, "rw").use { it.setLength(total) }
            d.setTotal(total)
            ChunkedDownload(d, total) { forceRefresh ->
                if (forceRefresh) url = runBlocking { YouTube.audioSource(d.id, forceRefresh = true) }.url
                url
            }.run()
            doneFile(d.id).createNewFile()
            d.complete()
            evict()
        } catch (e: Throwable) {
            YouTube.invalidateAudioUrl(d.id)
            d.fail(e)
        }
    }

    /**
     * Качает трек параллельно несколькими Range-запросами: у YouTube ~1 с уходит на ответ
     * каждого запроса, и последовательная загрузка платила бы эту задержку за каждый мегабайт.
     * Плееру доступен только непрерывный префикс файла, поэтому куски берутся по порядку.
     */
    private inner class ChunkedDownload(
        private val d: Download,
        total: Long,
        private val url: (forceRefresh: Boolean) -> String,
    ) {
        // Первый кусок маленький — с него начинается воспроизведение
        private val ranges: List<LongRange> = buildList {
            var pos = 0L
            var size = FIRST_CHUNK_SIZE
            while (pos < total) {
                val end = minOf(pos + size, total)
                add(pos until end)
                pos = end
                size = CHUNK_SIZE
            }
        }
        private val written = LongArray(ranges.size)
        private val nextChunk = AtomicInteger(0)
        @Volatile private var failure: Throwable? = null

        fun run() {
            val workers = List(minOf(PARALLEL_CONNECTIONS, ranges.size)) {
                Thread({ work() }, "audio-chunk").apply { isDaemon = true; start() }
            }
            workers.forEach { it.join() }
            failure?.let { throw it }
        }

        private fun work() {
            RandomAccessFile(d.file, "rw").use { raf ->
                while (failure == null) {
                    val index = nextChunk.getAndIncrement()
                    if (index >= ranges.size) return
                    try {
                        downloadChunk(index, raf)
                    } catch (e: Throwable) {
                        failure = e
                    }
                }
            }
        }

        private fun downloadChunk(index: Int, raf: RandomAccessFile) {
            val range = ranges[index]
            val buffer = ByteArray(BUFFER_SIZE)
            var failures = 0
            var forceRefresh = false
            while (true) {
                // При повторе продолжаем с того места, где оборвалось
                val from = range.first + written[index]
                if (from > range.last) return
                try {
                    val connection = openRange(url(forceRefresh), from, range.last)
                    forceRefresh = false
                    when (connection.responseCode) {
                        in 200..299 -> connection.inputStream.use { input ->
                            raf.seek(from)
                            while (failure == null) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                raf.write(buffer, 0, n)
                                onWritten(index, n)
                            }
                        }
                        403, 410 -> {
                            forceRefresh = true
                            throw IOException("Ссылка на поток устарела")
                        }
                        else -> throw IOException("HTTP ${connection.responseCode}")
                    }
                    if (range.first + written[index] <= range.last) throw IOException("Соединение оборвалось")
                    return
                } catch (e: IOException) {
                    if (failure != null || ++failures > MAX_RETRIES) throw e
                    Thread.sleep(500L * failures)
                }
            }
        }

        @Synchronized
        private fun onWritten(index: Int, bytes: Int) {
            written[index] += bytes
            // Непрерывный префикс: все готовые куски подряд плюс начало следующего
            var available = 0L
            for (i in ranges.indices) {
                available += written[i]
                if (written[i] < ranges[i].last - ranges[i].first + 1) break
            }
            if (available > d.available) d.advance(available)
        }
    }

    @Synchronized
    private fun evict() {
        val files = dir.listFiles { f -> f.extension == "m4a" && doneFile(f.nameWithoutExtension).exists() }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        var used = 0L
        for (file in files) {
            used += file.length()
            val id = file.nameWithoutExtension
            if (used > maxBytes && id != playingId) {
                downloads.remove(id)
                doneFile(id).delete()
                file.delete()
            }
        }
    }

    private fun probeSize(url: String): Long? {
        val connection = openRange(url, 0, 0)
        return try {
            if (connection.responseCode !in 200..299) null
            else connection.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
        } finally {
            connection.disconnect()
        }
    }

    private fun openRange(url: String, from: Long, to: Long) =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            // Без Range YouTube режет скорость до ~16 КБ/с, с Range отдаёт на полной
            setRequestProperty("Range", "bytes=$from-$to")
        }

    private companion object {
        const val FIRST_CHUNK_SIZE = 256L * 1024
        const val CHUNK_SIZE = 1L shl 20 // 1 МБ
        const val PARALLEL_CONNECTIONS = 4
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_RETRIES = 3
    }
}
