package top.tianyan.app.ui.chat

import androidx.compose.foundation.gestures.scrollBy
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

/**
 * 跳到列表绝对底部：先定位末项，再越过其剩余高度。末项高于视口时 scrollToItem 只会停在
 * 该项顶部，流式增长的正文尾部仍在视口之外；scrollBy 越过剩余距离才能停在真实底部。
 */
internal suspend fun LazyListState.safeScrollToAbsoluteBottom() {
    safeScrollToLastItem()
    try {
        scrollBy(1_000_000f)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        // 补跳失败可容忍（布局已变化），但不能吞掉协程取消
    }
}
