package org.example.videoplayerkmp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipArea(
        tooltip = {
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shadowElevation = 6.dp,
            ) {
                Text(text, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        },
        delayMillis = 500,
        content = content,
    )
}

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@Composable
actual fun ColumnScrollbar(state: ScrollState, modifier: Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier)
}

@Composable
actual fun ScrollbarTheme(content: @Composable () -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(
        LocalScrollbarStyle provides ScrollbarStyle(
            minimalHeight = 32.dp,
            thickness = 8.dp,
            shape = RoundedCornerShape(4.dp),
            hoverDurationMillis = 300,
            unhoverColor = onSurface.copy(alpha = 0.12f),
            hoverColor = onSurface.copy(alpha = 0.32f),
        ),
        content = content,
    )
}

@Composable
actual fun rememberCsvPicker(onPicked: (fileName: String, text: String) -> Unit): () -> Unit = remember(onPicked) {
    {
        val dialog = FileDialog(null as Frame?, "Выберите CSV-файл плейлиста", FileDialog.LOAD).apply {
            file = "*.csv"
            setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
            isVisible = true
        }
        dialog.file?.let { name -> onPicked(name, File(dialog.directory, name).readText()) }
    }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
