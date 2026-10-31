package top.tianyan.app.ui.bot

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.model.bot.GroupMessage

/** 群聊滚动跟随：首屏贴底、新消息跟随、上滑后统计未读，不在用户阅读时抢滚动。 */
@Composable
internal fun GroupChatScrollEffects(
    listState: LazyListState,
    messages: List<GroupMessage>,
    following: Boolean,
    onFollowingChange: (Boolean) -> Unit,
    onAnchorChange: (String?) -> Unit,
) {
    val latestMessages by rememberUpdatedState(messages)
    val currentFollowing by rememberUpdatedState(following)
    var initialPositioned by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var previous = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.canScrollForward,
            )
        }.collect { (index, offset, canScrollForward) ->
            val movedUp = index < previous.first || (index == previous.first && offset < previous.second)
            previous = index to offset
            if (!canScrollForward) {
                onFollowingChange(true)
                onAnchorChange(null)
            } else if (movedUp && currentFollowing) {
                onFollowingChange(false)
                onAnchorChange(latestMessages.lastOrNull()?.id)
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (!initialPositioned) {
            initialPositioned = true
            onFollowingChange(true)
            onAnchorChange(null)
            listState.jumpToBottom()
        } else if (following) {
            listState.animateToBottom()
        }
    }

    LaunchedEffect(messages.lastOrNull()?.id, messages.lastOrNull()?.contentText, following) {
        if (!following || !initialPositioned || messages.isEmpty()) return@LaunchedEffect
        listState.followLatestEdge()
    }
}

private suspend fun LazyListState.awaitItems() {
    if (layoutInfo.totalItemsCount > 0) return
    snapshotFlow { layoutInfo.totalItemsCount }.first { it > 0 }
}

/** 首屏直接贴到最新内容末尾，避免长消息只显示开头。 */
internal suspend fun LazyListState.jumpToBottom() {
    awaitItems()
    scrollToItem(layoutInfo.totalItemsCount - 1)
    scrollRemainingToBottom(animated = false)
}

/** 点击“回到最新”时滚到真正的内容底部，而不是最后一条消息的顶部。 */
internal suspend fun LazyListState.animateToBottom() {
    awaitItems()
    animateScrollToItem(layoutInfo.totalItemsCount - 1)
    scrollRemainingToBottom(animated = true)
}

private suspend fun LazyListState.scrollRemainingToBottom(animated: Boolean) {
    repeat(4) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        val delta = last.offset + last.size - info.viewportEndOffset
        if (delta <= 0) return
        if (animated) animateScrollBy(delta.toFloat()) else scrollBy(delta.toFloat())
    }
}

/** 跟随流式增长：只补最后一条消息底部的增量，不把用户拉回消息顶部。 */
private suspend fun LazyListState.followLatestEdge() {
    repeat(2) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        if (last.index != info.totalItemsCount - 1) return
        val delta = last.offset + last.size - info.viewportEndOffset
        if (delta <= 0) return
        animateScrollBy(delta.toFloat())
    }
}
