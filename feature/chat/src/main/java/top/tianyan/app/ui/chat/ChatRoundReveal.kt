package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult

/**
 * 「展开更多」分帧揭示状态机。
 *
 * 一次把 20+ 张工具卡插入列表会在**同一帧**内完成组合，长轮次展开必然掉帧；
 * 因此展开时按帧逐批放出隐藏段（每帧 [REVEAL_PER_FRAME] 条），使单帧组合量有上界。
 * 收起与短隐藏段仍立即生效，不做任何延迟。
 *
 * 独立成文件的原因：承载它的 ChatMessageList.kt 已触及 architecture-policy.json
 * 的文件尺寸棘轮上限（基线只许下调），故状态机与投影缓存入口一并外移。
 */
internal class RoundRevealState {
    var roundKey by mutableStateOf<String?>(null)
        private set
    var total by mutableStateOf(0)
        private set
    var limit by mutableStateOf(Int.MAX_VALUE)
        private set

    /** 仅当存在揭示中的轮次时才有值；交给投影函数决定放出多少隐藏项。 */
    val limits: Map<String, Int> get() = roundKey?.let { mapOf(it to limit) } ?: emptyMap()

    fun start(key: String, totalItems: Int) {
        total = totalItems
        limit = REVEAL_PER_FRAME
        roundKey = key
    }

    fun stop() {
        limit = Int.MAX_VALUE
        roundKey = null
    }

    /** 按帧推进，到达 total 后一次性放开并复位。 */
    suspend fun advanceByFrame() {
        var revealed = REVEAL_PER_FRAME
        while (revealed < total) {
            withFrameNanos { }
            revealed += REVEAL_PER_FRAME
            limit = revealed
        }
        stop()
    }

    /** 折叠条点击的统一入口：需要揭示则启动，否则立即生效（收起 / 隐藏段很短）。 */
    fun onToggled(item: ChatRenderItem.CollapseButtonItem, wasExpanded: Boolean, enabled: Boolean) {
        if (!wasExpanded && enabled && item.hiddenItemCount > REVEAL_PER_FRAME) {
            start(item.roundKey, item.hiddenItemCount)
        } else {
            stop()
        }
    }

    private companion object {
        const val REVEAL_PER_FRAME = 3
    }
}

/** 建立并驱动揭示状态机（`advanceByFrame` 在轮次切换时自动取消重启）。 */
@Composable
internal fun rememberRoundRevealState(): RoundRevealState {
    val state = remember { RoundRevealState() }
    LaunchedEffect(state.roundKey) {
        if (state.roundKey != null) state.advanceByFrame()
    }
    return state
}

/**
 * 流式增量投影缓存：不可变前缀的投影结果按引用复用，每 token 只重投影尾部。
 *
 * 流式期间 `messages` 每个 token 产出新快照，全量投影（600 条窗口）每 token 会重建 600+ 个
 * [ChatRenderItem] 并让 LazyColumn 的 key diff 全表重跑。这里取「最后一个链外消息」为安全切点：
 * `groupToolSteps` 只在相邻的链内步骤之间合并，切点之后的分组不可能与前缀合并，
 * 因此前缀结果可以按引用复用，只对切点之后的新消息做投影（rawIndex 由 [rawIndexOffset] 保真）。
 * 折叠模式按轮次重排、无法做前缀复用，仍走全量投影。
 */
internal class ChatRenderItemsCache {
    private var config: List<Any?>? = null
    private var messages: List<HarnessMessage> = emptyList()
    private var items: List<ChatRenderItem> = emptyList()
    private var prefixSize = 0
    private var prefixItems = 0

    fun project(
        messages: List<HarnessMessage>,
        toolResults: Map<String, ToolResult>,
        expandedOverrides: Map<String, Boolean>,
        collapseEnabled: Boolean,
        revealLimits: Map<String, Int>,
        focusedId: String?,
    ): List<ChatRenderItem> {
        val focusedRound = focusedRoundKey(messages, focusedId)
        val overrides = if (focusedRound == null) expandedOverrides else expandedOverrides + (focusedRound to true)
        val nextConfig = listOf(toolResults, overrides, collapseEnabled, revealLimits, focusedId)
        if (messages === this.messages && nextConfig == config) return items
        val reusable = !collapseEnabled && nextConfig == config && prefixSize > 0 &&
            messages.size >= prefixSize && samePrefix(messages)
        val projected = if (reusable) {
            val tail = projectChatTimeline(
                messages.subList(prefixSize, messages.size), toolResults, overrides,
                collapseEnabled, revealLimits, rawIndexOffset = prefixSize,
            )
            ArrayList<ChatRenderItem>(prefixItems + tail.size).apply {
                addAll(items.subList(0, prefixItems))
                addAll(tail)
            }
        } else {
            projectChatTimeline(messages, toolResults, overrides, collapseEnabled, revealLimits)
        }
        // 切点：末条消息在流式期间还会继续增长，永远留给尾部重投影。
        // 同时必须落在过程链之外——把一条链切成两段会投影成两条独立过程链（与全量投影不等价）。
        var safe = (messages.size - 1).coerceAtLeast(0)
        while (safe > 0 && (isChainStepMessage(messages[safe]) || isChainStepMessage(messages[safe - 1]))) {
            safe--
        }
        // 用「项内最小消息下标」判界：过程链的 stepRawIndex 是 -1，按它判会把链整条留在前缀里。
        var cut = projected.size
        while (cut > 0 && projected[cut - 1].firstRawIndex >= safe) cut--
        this.config = nextConfig
        this.messages = messages
        items = projected
        prefixSize = safe
        prefixItems = cut
        return projected
    }

    private fun samePrefix(current: List<HarnessMessage>): Boolean {
        for (index in 0 until prefixSize) if (messages[index] != current[index]) return false
        return true
    }

    private fun isChainStepMessage(message: HarnessMessage): Boolean {
        val call = message as? ToolCall ?: return false
        return call.tool != HarnessTool.SUBAGENT && call.tool != HarnessTool.RENDER_SURFACE
    }
}

/**
 * 投影 + 缓存键的统一入口。
 * 折叠关闭（默认）时投影不读取 toolResults，因此也不把它列为缓存键，
 * 避免工具结果变化时白白让投影失效重建（与关闭折叠时的历史行为一致）。
 */
@Composable
internal fun rememberChatRenderItems(
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    expandedOverrides: Map<String, Boolean>,
    chatRoundCollapse: Boolean,
    revealLimits: Map<String, Int>,
): List<ChatRenderItem> {
    val projectionToolResults = if (chatRoundCollapse) toolResults else emptyMap()
    val focusedId = LocalChatTimelineFocus.current?.value
    val cache = remember { ChatRenderItemsCache() }
    return cache.project(messages, projectionToolResults, expandedOverrides, chatRoundCollapse, revealLimits, focusedId)
}
