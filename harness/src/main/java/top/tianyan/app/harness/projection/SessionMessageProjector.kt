package top.tianyan.app.harness.projection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.core.model.ChunkedText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.TurnChangesEvent
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.session.SessionTreeStore

/**
 * 会话消息投影的窄端口：写入与读取实时消息，无需感知前台镜像的实现细节。
 * 供 HarnessLoop 与 CapabilityEventWriter 等协作者使用。
 */
interface LiveMessagePort {
    suspend fun append(sessionId: String, message: HarnessMessage)
    suspend fun publishPersisted(sessionId: String, message: HarnessMessage)
    fun snapshot(sessionId: String): List<HarnessMessage>
}

/**
 * 实时消息投影器：维护每个会话的内存态消息流（含异步历史合并），
 * 并把前台聚焦会话的列表镜像到全局 [foregroundMessages] 流。
 *
 * 持久化本身由 [SessionTreeStore] 负责；本类只做"已提交内容的发布 + 流式上屏"。
 */
class SessionMessageProjector(
    private val store: SessionTreeStore,
    private val tracker: CurrentSessionTracker,
) : LiveMessagePort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val liveFlows = ConcurrentHashMap<String, MutableStateFlow<List<HarnessMessage>>>()
    private val historyVersions = ConcurrentHashMap<String, AtomicLong>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val accessCounter = AtomicLong()
    private val streamingSessions = ConcurrentHashMap.newKeySet<String>()

    /** 每个流式会话最近一次收到流式增量的时间；用于兜底清理异常终止的流式登记。 */
    private val streamingLastActivity = ConcurrentHashMap<String, Long>()

    private val _foregroundMessages = MutableStateFlow<List<HarnessMessage>>(emptyList())
    /** 当前前台聚焦会话的消息列表（供聊天界面观察）。 */
    val foregroundMessages: StateFlow<List<HarnessMessage>> = _foregroundMessages.asStateFlow()

    private fun mirrorIfForeground(sessionId: String, value: List<HarnessMessage>) {
        if (tracker.isForeground(sessionId)) {
            _foregroundMessages.value = value
        }
    }

    /**
     * 获取或创建会话的实时消息流。首次创建时异步合并持久化历史：
     * 历史读取期间新追加的消息不会被丢弃，而是按时间归并。
     */
    fun messagesFlow(sessionId: String): MutableStateFlow<List<HarnessMessage>> {
        touch(sessionId)
        liveFlows[sessionId]?.let {
            evictLeastRecentlyUsed(sessionId)
            return it
        }
        val created = MutableStateFlow<List<HarnessMessage>>(emptyList())
        val version = historyVersions.computeIfAbsent(sessionId) { AtomicLong() }
        val expectedVersion = version.get()
        val flow = liveFlows.putIfAbsent(sessionId, created) ?: created.also {
            scope.launch(Dispatchers.IO) {
                val history = history(sessionId)
                synchronized(created) {
                    if (liveFlows[sessionId] === created && version.get() == expectedVersion) {
                        created.update { current ->
                            if (current.isEmpty()) {
                                boundLiveWindow(history)
                            } else {
                                // Current contains newer stream/persisted projections and wins on duplicate ids.
                                boundLiveWindow(
                                    (history + current).associateBy { it.id }.values.sortedBy { it.createdAt },
                                )
                            }
                        }
                        mirrorIfForeground(sessionId, created.value)
                    }
                }
            }
        }
        evictLeastRecentlyUsed(sessionId)
        return flow
    }

    private suspend fun history(sessionId: String): List<HarnessMessage> =
        withContext(Dispatchers.IO) { store.load(sessionId) }

    /** 会话历史（活动分支），供分支切换 / 重生成等操作重建实时流。 */
    suspend fun loadHistory(sessionId: String): List<HarnessMessage> = history(sessionId)

    /** Strict storage read for remote snapshots and terminal task acknowledgments. */
    suspend fun loadPersistedHistory(sessionId: String): List<HarnessMessage> =
        withContext(Dispatchers.IO) { store.loadStrict(sessionId) }

    /**
     * loadSession 的预置路径：已有流直接复用；否则先读历史再创建，
     * 避免异步合并窗口期把刚切换的会话闪成空列表。
     */
    suspend fun preparedForLoad(sessionId: String): MutableStateFlow<List<HarnessMessage>> {
        touch(sessionId)
        liveFlows[sessionId]?.let { return it }
        val created = MutableStateFlow(boundLiveWindow(loadHistory(sessionId)))
        val selected = liveFlows.putIfAbsent(sessionId, created) ?: created
        evictLeastRecentlyUsed(sessionId)
        return selected
    }

    /** 新建会话：无条件以空列表开局。 */
    fun seedEmpty(sessionId: String) {
        touch(sessionId)
        val current = liveFlows[sessionId]
        if (current != null) {
            synchronized(current) {
                historyVersions.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
                liveFlows[sessionId] = MutableStateFlow(emptyList())
            }
        } else {
            historyVersions.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
            liveFlows[sessionId] = MutableStateFlow(emptyList())
        }
        evictLeastRecentlyUsed(sessionId)
    }

    /** 新建/加载会话后复位前台镜像为该会话当前值。 */
    fun resetForegroundProjection(value: List<HarnessMessage>) {
        _foregroundMessages.value = boundLiveWindow(value)
    }

    /** 整体替换某会话的实时消息（重生成 / 回退 / 分支切换等场景）。 */
    fun replaceAll(sessionId: String, messages: List<HarnessMessage>) {
        val bounded = boundLiveWindow(messages)
        val flow = messagesFlow(sessionId)
        synchronized(flow) {
            historyVersions.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
            flow.value = bounded
            mirrorIfForeground(sessionId, bounded)
        }
    }

    fun removeSession(sessionId: String) {
        val flow = liveFlows[sessionId]
        if (flow != null) {
            synchronized(flow) {
                liveFlows.remove(sessionId, flow)
                historyVersions.remove(sessionId)
            }
        } else {
            historyVersions.remove(sessionId)
        }
        lastAccess.remove(sessionId)
        endStreamingInternal(sessionId)
    }

    override suspend fun append(sessionId: String, message: HarnessMessage) {
        store.append(sessionId, message)
        publishPersisted(sessionId, message)
    }

    override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == message.id }
            val updated = if (idx >= 0) {
                // in-place 替换：避免整列 map() copy 产生的额外 List 对象（工具密集轮次频繁触发）
                current.toMutableList().apply { this[idx] = message }
            } else {
                current + message
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
        if (message is AssistantText) endStreamingInternal(sessionId)
    }

    override fun snapshot(sessionId: String): List<HarnessMessage> = messagesFlow(sessionId).value

    /** 移除一条仅存在于实时流中的消息（如出错后的空气泡）。 */
    fun remove(sessionId: String, messageId: String) {
        val flow = messagesFlow(sessionId)
        flow.update { current -> current.filterNot { it.id == messageId } }
        mirrorIfForeground(sessionId, flow.value)
        endStreamingInternal(sessionId)
    }

    /** Mark a provider stream complete even when it yielded only tool calls/reasoning. */
    fun endStreaming(sessionId: String) {
        endStreamingInternal(sessionId)
    }

    private fun endStreamingInternal(sessionId: String) {
        streamingSessions.remove(sessionId)
        streamingLastActivity.remove(sessionId)
    }

    /** 流式助手文本增量刷新：保留已有 reasoning。 */
    fun streamText(sessionId: String, id: String, createdAt: Long, text: String) {
        streamText(sessionId, id, createdAt, ChunkedText.of(text))
    }

    fun streamText(sessionId: String, id: String, createdAt: Long, text: ChunkedText) {
        streamingSessions += sessionId
        streamingLastActivity[sessionId] = System.currentTimeMillis()
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == id }
            val existing = current.getOrNull(idx)
            val message = AssistantText(
                id = id,
                createdAt = createdAt,
                textContent = text,
                reasoning = (existing as? AssistantText)?.reasoning,
            )
            val updated = if (idx >= 0) {
                current.toMutableList().apply { this[idx] = message }
            } else {
                current + message
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
    }

    /** 流式思考过程增量刷新：文本未就绪时先行生成占位气泡。 */
    fun streamReasoning(sessionId: String, id: String, createdAt: Long, reasoning: String) {
        streamingSessions += sessionId
        streamingLastActivity[sessionId] = System.currentTimeMillis()
        val flow = messagesFlow(sessionId)
        flow.update { current ->
            val idx = current.indexOfFirst { it.id == id }
            val updated = if (idx >= 0) {
                val existing = current[idx]
                (existing as? AssistantText)?.let {
                    current.toMutableList().apply { this[idx] = it.copy(reasoning = reasoning) }
                } ?: (current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning))
            } else {
                current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning)
            }
            boundLiveWindow(updated)
        }
        mirrorIfForeground(sessionId, flow.value)
    }

    private fun touch(sessionId: String) {
        lastAccess[sessionId] = accessCounter.incrementAndGet()
    }

    private fun boundLiveWindow(messages: List<HarnessMessage>): List<HarnessMessage> {
        val byCount = if (messages.size > SessionTreeStore.MAX_LIVE_ENTRIES) {
            messages.takeLast(SessionTreeStore.MAX_LIVE_ENTRIES)
        } else {
            messages
        }
        // 条数上限挡不住单条大载荷（tool_result 正文 ≤64KB、内嵌 base64 图片、edit 的 diff）：
        // 工具/图片密集的长会话里 600 条能叠到数百 MB，是 OOM 峰值主因。这里再从最新往回累计
        // 字符数，超过 [MAX_LIVE_CHARS] 即丢弃更旧的前缀，保证单会话常驻窗口有硬字节上限。
        if (byCount.isEmpty()) return byCount
        var total = 0L
        var start = 0
        for (index in byCount.indices.reversed()) {
            total += messageWeight(byCount[index])
            if (total > MAX_LIVE_CHARS) {
                // 至少保留最新一条：单条超预算也不至于把窗口清空。
                start = (index + 1).coerceAtMost(byCount.lastIndex)
                break
            }
        }
        return if (start == 0) byCount else byCount.subList(start, byCount.size).toList()
    }

    /**
     * 单条消息的近似字符量（UTF-16，1 字符≈2 字节），仅用于窗口字节预算，不做序列化。
     * 覆盖承载大载荷的字段；未计入的字段（id/枚举/时间戳）量级可忽略。
     */
    private fun messageWeight(message: HarnessMessage): Int = when (message) {
        is UserMessage -> message.text.length + message.imageUrls.sumOf { it.length }
        is AssistantText -> message.textContent.length + (message.reasoning?.length ?: 0)
        is ToolCall -> jsonWeight(message.args) + (message.reasoning?.length ?: 0)
        is ToolResult -> message.output.length +
            (message.imageDataUrl?.length ?: 0) +
            message.metadata.values.sumOf { it.length }
        is CapabilityEvent -> message.name.length + message.details.length
        is SkillSuggestion ->
            message.description.length + message.systemPrompt.length + message.reason.length
        is ModelSwitchEvent -> message.fromLabel.length + message.toLabel.length
        is TaskDelivery -> 256 + message.report.observations.size * 256 + message.report.artifacts.sumOf { it.path.length + it.sha256.length + it.resultEntryId.length + 256 }
        is TurnChangesEvent -> (message.created + message.modified + message.touched).sumOf { it.length }
    }

    /** JsonElement 的字符量：JsonPrimitive 直接取 content 长度（O(1)），容器递归求和。 */
    private fun jsonWeight(element: JsonElement): Int = when (element) {
        is JsonPrimitive -> element.content.length
        is JsonObject -> element.values.sumOf(::jsonWeight)
        is JsonArray -> element.sumOf(::jsonWeight)
    }

    private fun evictLeastRecentlyUsed(protectedSessionId: String) {
        var excess = liveFlows.size - MAX_CACHED_SESSIONS
        if (excess <= 0) return
        val now = System.currentTimeMillis()
        lastAccess.entries.asSequence()
            .filter { (id, _) ->
                // 流式会话不驱逐，但调用方异常终止（如重试耗尽后 rethrow）未清理登记时
                // 会永久驻留；用“超过 STREAM_STALE_MS 无任何流式增量”兜底判定为死流，允许驱逐。
                // 活跃流式发布间隔为数百毫秒级，10 分钟静默必然是异常终止的流。
                id != protectedSessionId && !tracker.isForeground(id) &&
                    (id !in streamingSessions || now - (streamingLastActivity[id] ?: 0L) > STREAM_STALE_MS)
            }
            .sortedBy { it.value }
            .map { it.key }
            .forEach { id ->
                if (excess <= 0) return@forEach
                if (liveFlows.remove(id) != null) {
                    historyVersions.remove(id)
                    lastAccess.remove(id)
                    endStreamingInternal(id)
                    excess--
                }
            }
    }

    companion object {
        /**
         * 内存中最多缓存的会话实时消息流数量。
         * 降至 4：每个缓存会话在长对话场景下驻留的 List<HarnessMessage> 可达数 MB，
         * 256MB 堆上 8 个并发会话容易触发 OOM；4 可覆盖常见多会话工作流且安全余量更充足。
         */
        private const val MAX_CACHED_SESSIONS = 4

        /**
         * 单会话实时窗口的字符预算（UTF-16，1 字符≈2 字节）：条数上限 [SessionTreeStore.MAX_LIVE_ENTRIES]
         * 挡不住单条大载荷（tool_result 正文、内嵌 base64 图片、edit 的 diff），长会话可叠到数百 MB。
         * 8M 字符≈16MB，4 个缓存会话合计常驻 ≤64MB，把 OOM 峰值拦截在窗口层。
         *
         * internal 供单元测试断言窗口预算生效。
         */
        internal const val MAX_LIVE_CHARS = 8 * 1024 * 1024

        /** 流式登记的兜底过期阈值：超过该时长无任何流式增量视为异常终止的死流。 */
        private const val STREAM_STALE_MS = 10 * 60 * 1000L
    }
}
