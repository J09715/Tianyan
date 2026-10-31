package top.tianyan.app.ui.chat

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

/** Timeline navigation can reveal a call inside a collapsed group without changing persisted messages. */
internal val LocalChatTimelineFocus = staticCompositionLocalOf<MutableState<String?>?> { null }
internal data class ChatTimelineSnapshot(val items: List<ChatRenderItem> = emptyList(), val focusedId: String? = null)
internal val LocalChatTimelineItems = staticCompositionLocalOf<MutableState<ChatTimelineSnapshot>?> { null }

@Composable
internal fun rememberToolDetailsVisible(id: String): MutableState<Boolean> {
    val expanded = rememberSaveable(id) { mutableStateOf(false) }
    val focusedId = LocalChatTimelineFocus.current?.value
    LaunchedEffect(focusedId, id) { if (focusedId == id) expanded.value = true }
    return expanded
}
