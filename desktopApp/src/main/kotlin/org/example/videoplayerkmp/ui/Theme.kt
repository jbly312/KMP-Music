package org.example.videoplayerkmp.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Палитра нейтральная: цвет в интерфейс приносит обложка текущего трека (см. ArtworkPalette).
 * Контраст (WCAG) — тёмная тема: основной текст 16.9:1, вторичный 7.4:1;
 * светлая: основной 17.0:1, вторичный 6.4:1. Акцент из обложки подгоняется до ≥ 4.5:1.
 */

/** Акцент, когда ничего не играет, — тот же фиолетовый, что у иконки приложения. */
val FallbackAccentDark = Color(0xFFA99BFF)
val FallbackAccentLight = Color(0xFF6550E0)

private val DarkColors = darkColorScheme(
    primary = FallbackAccentDark,
    onPrimary = Color(0xFF111113),
    background = Color(0xFF111113),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF111113),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF26262B),
    onSurfaceVariant = Color(0xFFA1A1AA),
    surfaceContainerLowest = Color(0xFF0B0B0D),
    surfaceContainerLow = Color(0xFF18181B),
    surfaceContainer = Color(0xFF1C1C20),
    surfaceContainerHigh = Color(0xFF232328),
    surfaceContainerHighest = Color(0xFF2C2C32),
    outline = Color(0xFF3F3F46),
    outlineVariant = Color(0xFF26262B),
    error = Color(0xFFFF8A80),
    inverseSurface = Color(0xFFF2F2F5),
    inverseOnSurface = Color(0xFF111113),
)

private val LightColors = lightColorScheme(
    primary = FallbackAccentLight,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF1C1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE4E4E8),
    onSurfaceVariant = Color(0xFF5E5E66),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF5F5F7),
    surfaceContainer = Color(0xFFF0F0F3),
    surfaceContainerHigh = Color(0xFFEAEAEE),
    surfaceContainerHighest = Color(0xFFE1E1E6),
    outline = Color(0xFFC7C7CC),
    outlineVariant = Color(0xFFE4E4E8),
    error = Color(0xFFC62828),
    inverseSurface = Color(0xFF2C2C30),
    inverseOnSurface = Color(0xFFF2F2F5),
)

/** Шкала под десктоп: основной текст 14 sp, вторичный 13 sp, минимум 11 sp. */
private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineMedium = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
        labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
        // Моноширинные цифры: таймер не «дрожит», пока идут секунды
        labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    )
}

/** Тёмная ли сейчас тема — нужно для подбора акцента и свечения. */
val LocalIsDark = staticCompositionLocalOf { true }

/**
 * Тема следует системной теме Windows/macOS. [artworkSeed] — цвет обложки текущего трека:
 * из него получается акцент, который плавно меняется при смене трека.
 */
@Composable
fun AppTheme(artworkSeed: Color?, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) DarkColors else LightColors
    val target = artworkSeed?.let { accentFromArtwork(it, dark, base.background) }
        ?: if (dark) FallbackAccentDark else FallbackAccentLight
    val accent by animateColorAsState(target, tween(durationMillis = 700), label = "accent")

    MaterialTheme(colorScheme = base.withAccent(accent, dark), typography = AppTypography) {
        CompositionLocalProvider(
            LocalIsDark provides dark,
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = base.onSurface.copy(alpha = 0.12f),
                hoverColor = base.onSurface.copy(alpha = 0.32f),
            ),
            content = content,
        )
    }
}

private fun ColorScheme.withAccent(accent: Color, dark: Boolean): ColorScheme {
    val onAccent = if (contrast(Color.White, accent) >= contrast(Color(0xFF111113), accent)) Color.White else Color(0xFF111113)
    val container = accent.copy(alpha = if (dark) 0.22f else 0.14f).compositeOver(background)
    return copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = container,
        onPrimaryContainer = onSurface,
        secondaryContainer = container,
        onSecondaryContainer = onSurface,
        surfaceTint = accent,
    )
}
