package org.example.videoplayerkmp.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI

/** Загрузка обложек с кэшем в памяти. */
object ImageLoader {
    private val cache = object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 400
    }
    private val parallel = Semaphore(6)

    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        return parallel.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val connection = URI(url).toURL().openConnection().apply {
                        connectTimeout = 10_000
                        readTimeout = 15_000
                    }
                    val bytes = connection.getInputStream().use { it.readBytes() }
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()?.also { synchronized(cache) { cache[url] = it } }
            }
        }
    }
}

/** У обложек YouTube Music размер задаётся в URL (=w120-h120) — просим нужный. */
fun sizedThumbnail(url: String?, px: Int): String? =
    url?.replace(Regex("""=w\d+-h\d+"""), "=w$px-h$px")

@Composable
fun Thumbnail(url: String?, modifier: Modifier, shape: Shape) {
    // Состояние привязано к url: при смене трека старая картинка не должна оставаться
    var bitmap by remember(url) { mutableStateOf(url?.let(ImageLoader::cached)) }
    LaunchedEffect(url) {
        if (url != null && bitmap == null) bitmap = ImageLoader.load(url)
    }
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(
                Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(0.45f),
            )
        }
    }
}
