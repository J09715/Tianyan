package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable

/** 独立的思考行：只含思考、周围没有工具调用时原地渲染。 */
@Composable
internal fun ReasoningRow(item: ChatRenderItem.ReasoningItem, defaultExpanded: Boolean, live: Boolean,
    autoTranslate: Boolean, onNavigateToSettings: (() -> Unit)?) {
    ThinkingBlock(
        id = item.messageId,
        reasoning = item.reasoning,
        defaultExpanded = defaultExpanded,
        live = live,
        durationMs = item.durationMs,
        autoTranslate = autoTranslate,
        onNavigateToSettings = onNavigateToSettings,
    )
}
