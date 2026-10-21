package org.example.videoplayerkmp.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Accent = Color(0xFF9B8CFF)

private val AppColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF15102F),
    primaryContainer = Color(0xFF2F2868),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFFC3BDE6),
    onSecondary = Color(0xFF1E1A33),
    secondaryContainer = Color(0xFF2C2A3A),
    onSecondaryContainer = Color(0xFFE3E0F2),
    background = Color(0xFF0E0E12),
    onBackground = Color(0xFFE9E8EF),
    surface = Color(0xFF0E0E12),
    onSurface = Color(0xFFE9E8EF),
    surfaceVariant = Color(0xFF26252E),
    onSurfaceVariant = Color(0xFF9F9DAC),
    surfaceContainerLowest = Color(0xFF09090C),
    surfaceContainerLow = Color(0xFF131318),
    surfaceContainer = Color(0xFF17171D),
    surfaceContainerHigh = Color(0xFF1F1F26),
    surfaceContainerHighest = Color(0xFF2A2933),
    outline = Color(0xFF3B3A45),
    outlineVariant = Color(0xFF24232B),
    error = Color(0xFFFF8A80),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AppColors) {
        CompositionLocalProvider(
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 8.dp,
                shape = RoundedCornerShape(4.dp),
                hoverDurationMillis = 300,
                unhoverColor = Color.White.copy(alpha = 0.10f),
                hoverColor = Color.White.copy(alpha = 0.28f),
            ),
            content = content,
        )
    }
}
