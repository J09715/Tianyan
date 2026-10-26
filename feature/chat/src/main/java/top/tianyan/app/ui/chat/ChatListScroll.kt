package top.tianyan.app.ui.chat

import androidx.compose.foundation.lazy.LazyListState

/** Last item index for [LazyListState.scrollToItem], or null when the list has not laid out yet. */
internal fun lastLazyItemIndex(totalItemsCount: Int): Int? =
    (totalItemsCount - 1).takeIf { it >= 0 }

/**
 * Scroll helpers that never pass a negative index into LazyListState.
 *
 * `layoutInfo.totalItemsCount` is 0 before the first measure and during IME relayout;
 * `count - 1` would then crash with `Index should be non-negative (-1)`.
 */
internal suspend fun LazyListState.safeScrollToLastItem(animated: Boolean = false) {
    val index = lastLazyItemIndex(layoutInfo.totalItemsCount) ?: return
    safeScrollToItem(index, animated)
}

internal suspend fun LazyListState.safeScrollToItem(index: Int, animated: Boolean = false) {
    val lastIndex = lastLazyItemIndex(layoutInfo.totalItemsCount) ?: return
    val safeIndex = index.coerceIn(0, lastIndex)
    runCatching {
        if (animated) animateScrollToItem(safeIndex) else scrollToItem(safeIndex)
    }
}
