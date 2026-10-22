package org.example.videoplayerkmp.data

import kotlinx.serialization.Serializable

@Serializable
data class Track(
    /** ID видео на YouTube */
    val id: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String? = null,
) {
    val url: String get() = "https://www.youtube.com/watch?v=$id"
}

private val videoIdRegex = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/live/)([\w-]{11})""")

fun extractVideoId(text: String): String? = videoIdRegex.find(text.trim())?.groupValues?.get(1)

fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "--:--"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatTotalDuration(tracks: List<Track>): String {
    val total = tracks.sumOf { it.durationSec.coerceAtLeast(0) }
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "$h ч $m мин" else "$m мин"
}

fun tracksCount(n: Int): String {
    val word = when {
        n % 100 in 11..14 -> "треков"
        n % 10 == 1 -> "трек"
        n % 10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$n $word"
}
