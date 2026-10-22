package org.example.videoplayerkmp.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Доминирующий «живой» цвет обложки — из него строится акцент интерфейса. */
object ArtworkPalette {
    private val cache = ConcurrentHashMap<String, Color>()
    private val grayscale = ConcurrentHashMap.newKeySet<String>()

    suspend fun seed(url: String): Color? {
        cache[url]?.let { return it }
        if (url in grayscale) return null
        val bitmap = ImageLoader.load(url) ?: return null
        val color = withContext(Dispatchers.Default) { dominant(bitmap.toPixelMap()) }
        if (color == null) grayscale += url else cache[url] = color
        return color
    }

    /**
     * Делим оттенки на 12 секторов и ищем сектор с наибольшим «весом» (насыщенность × яркость).
     * Чёрно-белые обложки не дают цвета — тогда остаётся фирменный акцент.
     */
    private fun dominant(pixels: androidx.compose.ui.graphics.PixelMap): Color? {
        val buckets = 12
        val weight = FloatArray(buckets)
        val r = FloatArray(buckets)
        val g = FloatArray(buckets)
        val b = FloatArray(buckets)
        val step = max(1, min(pixels.width, pixels.height) / 48)
        var total = 0f
        var samples = 0
        for (y in 0 until pixels.height step step) {
            for (x in 0 until pixels.width step step) {
                val c = pixels[x, y]
                samples++
                val (h, s, v) = hsv(c)
                if (s < 0.25f || v < 0.2f) continue
                val w = s * v
                val i = ((h / 360f) * buckets).toInt().coerceIn(0, buckets - 1)
                weight[i] += w
                r[i] += c.red * w
                g[i] += c.green * w
                b[i] += c.blue * w
                total += w
            }
        }
        // Меньше ~4% «цветных» пикселей — обложка по сути монохромная
        if (samples == 0 || total < samples * 0.04f) return null
        val best = weight.indices.maxBy { weight[it] }
        val w = weight[best]
        return Color(r[best] / w, g[best] / w, b[best] / w)
    }
}

/**
 * Превращает цвет обложки в акцент: умеренная насыщенность и светлота,
 * подобранная так, чтобы контраст с фоном был не ниже 4.5:1 (текст и иконки).
 */
fun accentFromArtwork(seed: Color, dark: Boolean, background: Color): Color {
    val (h, s, _) = hsl(seed)
    val saturation = s.coerceIn(0.45f, 0.85f)
    var lightness = if (dark) 0.70f else 0.42f
    var accent = Color.hsl(h, saturation, lightness)
    repeat(30) {
        if (contrast(accent, background) >= 4.5f) return accent
        lightness = (lightness + if (dark) 0.02f else -0.02f).coerceIn(0.1f, 0.95f)
        accent = Color.hsl(h, saturation, lightness)
    }
    return accent
}

fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return max(la, lb) / min(la, lb)
}

private fun hsv(c: Color): Triple<Float, Float, Float> {
    val mx = max(c.red, max(c.green, c.blue))
    val mn = min(c.red, min(c.green, c.blue))
    val d = mx - mn
    val h = hue(c, mx, d)
    return Triple(h, if (mx == 0f) 0f else d / mx, mx)
}

private fun hsl(c: Color): Triple<Float, Float, Float> {
    val mx = max(c.red, max(c.green, c.blue))
    val mn = min(c.red, min(c.green, c.blue))
    val d = mx - mn
    val l = (mx + mn) / 2
    val s = if (d == 0f) 0f else d / (1 - abs(2 * l - 1))
    return Triple(hue(c, mx, d), s.coerceIn(0f, 1f), l)
}

private fun hue(c: Color, mx: Float, d: Float): Float {
    if (d == 0f) return 0f
    val h = when (mx) {
        c.red -> 60 * (((c.green - c.blue) / d) % 6)
        c.green -> 60 * (((c.blue - c.red) / d) + 2)
        else -> 60 * (((c.red - c.green) / d) + 4)
    }
    return if (h < 0) h + 360 else h
}
