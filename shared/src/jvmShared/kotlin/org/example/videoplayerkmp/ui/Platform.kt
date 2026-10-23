package org.example.videoplayerkmp.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/*
 * То, что на ПК и на телефоне устроено по-разному. Реализации — в jvmMain (ПК) и androidMain.
 */

/** Подсказка при наведении мыши. На телефоне наводить нечем — там только содержимое. */
@Composable
expect fun Tip(text: String, content: @Composable () -> Unit)

/** Полоса прокрутки списка. На телефоне её заменяет системный индикатор. */
@Composable
expect fun ListScrollbar(state: LazyListState, modifier: Modifier)

@Composable
expect fun ColumnScrollbar(state: ScrollState, modifier: Modifier)

/** Оформление полос прокрутки в цветах темы. */
@Composable
expect fun ScrollbarTheme(content: @Composable () -> Unit)

/** Системная кнопка «Назад» (Android). На ПК её нет. */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)

/** Возвращает действие «выбрать CSV-файл»; выбранный файл приходит в [onPicked] как имя и текст. */
@Composable
expect fun rememberCsvPicker(onPicked: (fileName: String, text: String) -> Unit): () -> Unit
