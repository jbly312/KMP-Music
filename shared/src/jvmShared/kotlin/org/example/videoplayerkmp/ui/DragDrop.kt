package org.example.videoplayerkmp.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Перетаскивание строк в LazyColumn за ручку.
 * Индексы — позиции в LazyColumn; [canMoveTo] отсекает заголовок и подвал списка.
 */
class DragDropState(
    private val list: LazyListState,
    private val scope: CoroutineScope,
    private val canMoveTo: (lazyIndex: Int) -> Boolean,
    private val onMove: (fromLazyIndex: Int, toLazyIndex: Int) -> Unit,
) {
    var draggingIndex by mutableStateOf<Int?>(null)
        private set
    private var draggedDelta by mutableFloatStateOf(0f)
    private var initialOffset by mutableIntStateOf(0)
    private var autoScroll: Job? = null

    /** На сколько сдвинуть перетаскиваемую строку относительно её места в списке. */
    val draggingOffset: Float
        get() = draggingItem?.let { initialOffset + draggedDelta - it.offset } ?: 0f

    private val draggingItem: LazyListItemInfo?
        get() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingIndex }

    fun start(lazyIndex: Int) {
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == lazyIndex } ?: return
        draggingIndex = lazyIndex
        initialOffset = item.offset
        draggedDelta = 0f
    }

    fun drag(dy: Float) {
        draggedDelta += dy
        moveUnderPointer()
        if (autoScroll?.isActive != true && edgeSpeed() != 0f) {
            // Если тащить строку к краю списка, он прокручивается сам, пока строка у края
            autoScroll = scope.launch {
                while (isActive && draggingIndex != null) {
                    val speed = edgeSpeed()
                    if (speed == 0f || list.scrollBy(speed) == 0f) break
                    // Прокрутка уводит список из-под мыши — пересчитываем, над какой строкой она теперь
                    moveUnderPointer()
                    delay(16)
                }
            }
        }
    }

    fun end() {
        autoScroll?.cancel()
        draggingIndex = null
        draggedDelta = 0f
        initialOffset = 0
    }

    private fun moveUnderPointer() {
        val item = draggingItem ?: return
        val middle = (item.offset + draggingOffset + item.size / 2f).toInt()
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull {
            middle in it.offset..(it.offset + it.size) && it.index != item.index && canMoveTo(it.index)
        } ?: return
        // Без этого LazyColumn «прыгает», когда меняется первый видимый элемент
        if (item.index == list.firstVisibleItemIndex || target.index == list.firstVisibleItemIndex) {
            scope.launch { list.scrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
        }
        onMove(item.index, target.index)
        draggingIndex = target.index
    }

    private fun edgeSpeed(): Float {
        val item = draggingItem ?: return 0f
        val top = item.offset + draggingOffset
        val bottom = top + item.size
        val viewport = list.layoutInfo
        return when {
            top < viewport.viewportStartOffset + EDGE -> -SCROLL_STEP
            bottom > viewport.viewportEndOffset - EDGE -> SCROLL_STEP
            else -> 0f
        }
    }

    private companion object {
        const val EDGE = 48
        const val SCROLL_STEP = 14f
    }
}

/** Ручка, за которую тащат строку с позицией [lazyIndex]. */
fun Modifier.dragHandle(state: DragDropState, lazyIndex: () -> Int): Modifier = pointerInput(state) {
    detectDragGestures(
        onDragStart = { state.start(lazyIndex()) },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
        onDragEnd = state::end,
        onDragCancel = state::end,
    )
}
