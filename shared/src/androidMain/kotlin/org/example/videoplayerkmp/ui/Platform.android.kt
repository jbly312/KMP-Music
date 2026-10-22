package org.example.videoplayerkmp.ui

import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun Tip(text: String, content: @Composable () -> Unit) = content()

@Composable
actual fun ListScrollbar(state: LazyListState, modifier: Modifier) = Unit

@Composable
actual fun ColumnScrollbar(state: ScrollState, modifier: Modifier) = Unit

@Composable
actual fun ScrollbarTheme(content: @Composable () -> Unit) = content()

@Composable
actual fun rememberCsvPicker(onPicked: (fileName: String, text: String) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "playlist.csv"
        val text = resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: return@rememberLauncherForActivityResult
        currentOnPicked(name, text)
    }
    // Многие файловые менеджеры отдают CSV как text/plain или text/comma-separated-values
    return { launcher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")) }
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)
