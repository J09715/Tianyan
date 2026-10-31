package top.tianyan.app.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.tianyan.app.ui.components.RuntimeSmallFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 判断列表是否已离开底部：最后一个可见项不是末项，或末项底边超出视口底端超过 [tolerancePx]。
 * 容差吸收流式增长的单帧增量，避免跟随滚动期间回底按钮闪烁。
 */
internal fun isAwayFromChatBottom(
    total: Int,
    lastIndex: Int?,
    lastEnd: Int,
    viewportEnd: Int,
    tolerancePx: Int = 0,
): Boolean = total > 0 && lastIndex != null && (lastIndex < total - 1 || lastEnd > viewportEnd + tolerancePx)

internal fun isAwayFromChatBottom(listState: LazyListState, tolerancePx: Int = 0): Boolean {
    val layout = listState.layoutInfo
    val last = layout.visibleItemsInfo.lastOrNull() ?: return false
    return isAwayFromChatBottom(layout.totalItemsCount, last.index, last.offset + last.size, layout.viewportEndOffset, tolerancePx)
}

/**
 * 跟随最新消息状态：只有用户手势滚动会中断跟随，程序化定位（流式跟随、发送回底、按钮跳转）
 * 不再顶掉跟随状态；列表无可前滚内容（停在绝对底部）时自动恢复跟随。
 */
@Composable
internal fun rememberChatFollowLatest(listState: LazyListState): Boolean {
    var following by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following = false
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    following = !isAwayFromChatBottom(listState)
                else -> Unit
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { more ->
            if (!more) following = true
        }
    }
    return following
}

/** Separate overlay keeps the jump action above list decorations and clipping. */
@Composable
internal fun ChatMessageViewport(listState: LazyListState, modifier: Modifier, content: @Composable () -> Unit) {
    // 与 ChatScreen 各自独立推导同一跟随状态：事件源相同，结果一致，免去参数穿透。
    val followLatest = rememberChatFollowLatest(listState)
    Box(modifier) {
        Box(Modifier.fillMaxSize()) { content() }
        ChatScrollToBottomButton(listState, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp).zIndex(1f),
            suppressed = followLatest)
    }
}

@Composable
internal fun ChatScrollToBottomButton(listState: LazyListState, modifier: Modifier = Modifier, suppressed: Boolean = false) {
    val tolerance = with(LocalDensity.current) { 64.dp.roundToPx() }
    val away by remember(listState) { derivedStateOf { isAwayFromChatBottom(listState, tolerance) } }
    val visible = !suppressed && away
    val scope = rememberCoroutineScope()
    if (visible) {
        val description = stringResource(R.string.chat_scroll_to_bottom)
        RuntimeSmallFloatingActionButton(
            onClick = { scope.launch { listState.safeScrollToAbsoluteBottom() } },
            modifier = modifier.shadow(4.dp, MaterialTheme.shapes.medium,
                ambientColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f),
                spotColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.06f))
                .semantics { contentDescription = description },
            shape = MaterialTheme.shapes.medium,
            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp,
                pressedElevation = 0.dp, focusedElevation = 0.dp, hoveredElevation = 0.dp),
        ) {
            RuntimeIcon(RuntimeIconName.ArrowUp, Modifier.size(22.dp).rotate(180f))
        }
    }
}
