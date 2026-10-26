package top.tianyan.app.ui.chat

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** Track user scrolling separately from streaming updates that grow the list. */
@Composable
internal fun rememberChatFollowLatest(listState: LazyListState): Boolean {
    var following by remember(listState) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling || !more) following = !more
        }
    }
    return following
}

/** Separate overlay keeps the jump action above list decorations and clipping. */
@Composable
internal fun ChatMessageViewport(listState: LazyListState, modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        Box(Modifier.fillMaxSize()) { content() }
        ChatScrollToBottomButton(listState, Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp).zIndex(1f))
    }
}

internal fun isAwayFromChatBottom(total: Int, lastIndex: Int?, lastEnd: Int, viewportEnd: Int): Boolean =
    total > 0 && lastIndex != null && (lastIndex < total - 1 || lastEnd > viewportEnd)

@Composable
internal fun ChatScrollToBottomButton(listState: LazyListState, modifier: Modifier = Modifier) {
    val visible by remember(listState) { derivedStateOf {
        val layout = listState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        isAwayFromChatBottom(layout.totalItemsCount, last?.index, last?.let { it.offset + it.size } ?: 0, layout.viewportEndOffset)
    } }
    val scope = rememberCoroutineScope()
    if (visible) {
        val description = stringResource(R.string.chat_scroll_to_bottom)
        RuntimeSmallFloatingActionButton(
            onClick = { scope.launch { listState.safeScrollToLastItem(animated = true) } },
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
