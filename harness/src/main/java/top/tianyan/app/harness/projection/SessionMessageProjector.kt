package top.tianyan.app.harness.projection

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
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
@Singleton
class SessionMessageProjector @Inject constructor(
    private val store: SessionTreeStore,
    private val tracker: CurrentSessionTracker,
) : LiveMessagePort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val liveFlows = ConcurrentHashMap<String, MutableStateFlow<List<HarnessMessage>>>()
    private val lastAccess = ConcurrentHashMap<String, Long>()
    private val accessCounter = AtomicLong()
    private val streamingSessions = ConcurrentHashMap.newKeySet<String>()

    /**
     * 待发射的流式变换：sessionId → 最新一次更新。
     * 流式 chunk 到达频率远高于屏幕刷新率，这里按节流窗口合并，
     * 把「每 chunk 一次全量列表拷贝 + 一次聊天页全量重组」压到每窗口一次。
     */
    private val pendingStreams = ConcurrentHashMap<String, (List<HarnessMessage>) -> List<HarnessMessage>>()

    /** 每会话上次真实发射时刻（nanoTime），用于判断当前 chunk 是否落在合并窗口外。 */
    private val lastStreamEmitAt = ConcurrentHashMap<String, Long>()

    init {
        scope.launch {
            while (true) {
                delay(STREAM_FLUSH_INTERVAL_MS)
                runCatching { flushPendingStreams() }
            }
        }
    }

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
        val flow = liveFlows.putIfAbsent(sessionId, created) ?: created.also {
            scope.launch(Dispatchers.IO) {
                val history = history(sessionId)
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
        evictLeastRecentlyUsed(sessionId)
        return flow
    }

    private suspend fun history(sessionId: String): List<HarnessMessage> =
        withContext(Dispatchers.IO) { store.load(sessionId) }

    /** 会话历史（活动分支），供分支切换 / 重生成等操作重建实时流。 */
    suspend fun loadHistory(sessionId: String): List<HarnessMessage> = history(sessionId)

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
        liveFlows[sessionId] = MutableStateFlow(emptyList())
        evictLeastRecentlyUsed(sessionId)
    }

    /** 新建/加载会话后复位前台镜像为该会话当前值。 */
    fun resetForegroundProjection(value: List<HarnessMessage>) {
        _foregroundMessages.value = boundLiveWindow(value)
    }

    /** 整体替换某会话的实时消息（重生成 / 回退 / 分支切换等场景）。 */
    fun replaceAll(sessionId: String, messages: List<HarnessMessage>) {
        // 整体替换后残留的流式增量会把旧内容重新写回来，必须丢弃。
        pendingStreams.remove(sessionId)
        val bounded = boundLiveWindow(messages)
        messagesFlow(sessionId).value = bounded
        mirrorIfForeground(sessionId, bounded)
    }

    fun removeSession(sessionId: String) {
        liveFlows.remove(sessionId)
        lastAccess.remove(sessionId)
        streamingSessions.remove(sessionId)
        pendingStreams.remove(sessionId)
        lastStreamEmitAt.remove(sessionId)
    }

    override suspend fun append(sessionId: String, message: HarnessMessage) {
        store.append(sessionId, message)
        publishPersisted(sessionId, message)
    }

    override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) {
        // 已提交内容权威性高于流式增量：不清掉残留增量的话，下一个 flush 会把半截文本写回覆盖终态。
        pendingStreams.remove(sessionId)
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
        if (message is AssistantText) streamingSessions.remove(sessionId)
    }

    override fun snapshot(sessionId: String): List<HarnessMessage> = messagesFlow(sessionId).value

    /** 移除一条仅存在于实时流中的消息（如出错后的空气泡）。 */
    fun remove(sessionId: String, messageId: String) {
        pendingStreams.remove(sessionId)
        val flow = messagesFlow(sessionId)
        flow.update { current -> current.filterNot { it.id == messageId } }
        mirrorIfForeground(sessionId, flow.value)
        streamingSessions.remove(sessionId)
    }

    /** Mark a provider stream complete even when it yielded only tool calls/reasoning. */
    fun endStreaming(sessionId: String) {
        streamingSessions.remove(sessionId)
        // 立即补发残留增量，避免最后一小段内容要等一个窗口才上屏。
        val transform = pendingStreams.remove(sessionId) ?: return
        val flow = liveFlows[sessionId] ?: return
        flow.update { current -> boundLiveWindow(transform(current)) }
        mirrorIfForeground(sessionId, flow.value)
    }

    /** 流式助手文本增量刷新：保留已有 reasoning。 */
    fun streamText(sessionId: String, id: String, createdAt: Long, text: String) {
        streamingSessions += sessionId
        touch(sessionId)
        // 只登记变换、由统一 ticker 合并发射：provider 每个 chunk 都调一次流式更新，
        // 直接落地会让每次 chunk 拷贝整个消息列表并唤醒聊天页全量重组，
        // 对话越长（列表上限 MAX_LIVE_ENTRIES 条）越卡，表现为「消息一多就卡」。
        enqueueStream(sessionId) { current -> upsertStreaming(current, id, createdAt, text, keepReasoning = true) }
    }

    /** 流式思考过程增量刷新：文本未就绪时先行生成占位气泡。 */
    fun streamReasoning(sessionId: String, id: String, createdAt: Long, reasoning: String) {
        streamingSessions += sessionId
        touch(sessionId)
        enqueueStream(sessionId) { current ->
            val idx = current.indexOfFirst { it.id == id }
            val existing = current.getOrNull(idx)
            if (existing is AssistantText) {
                current.toMutableList().apply { this[idx] = existing.copy(reasoning = reasoning) }
            } else {
                current + AssistantText(id = id, createdAt = createdAt, text = "", reasoning = reasoning)
            }
        }
    }

    /**
     * 登记一次流式变换，按会话去重（同一会话只保留最新一次），由 [flushPendingStreams] 定期合并发射。
     * 变换本身是纯函数（只依赖传入的当前列表），因此与其它 update 操作并发安全。
     */
    private fun enqueueStream(sessionId: String, transform: (List<HarnessMessage>) -> List<HarnessMessage>) {
        // 保证实时流已建立（否则 flush 时无处落地）；历史合并仍走 messagesFlow 内的异步分支。
        messagesFlow(sessionId)
        pendingStreams[sessionId] = transform
        // 窗口外的 chunk 立即上屏（首字延迟不被人为放大），窗口内的合并等下一次 tick。
        val now = System.nanoTime()
        val last = lastStreamEmitAt[sessionId] ?: 0L
        if (now - last >= STREAM_FLUSH_INTERVAL_NS) flushPendingStreams()
    }

    private fun flushPendingStreams() {
        if (pendingStreams.isEmpty()) return
        // 逐个原子 remove 再应用：remove 之后新到的 chunk 会留在 map 里交给下一 tick，不会被整批 clear 吞掉。
        val batch = ArrayList<Pair<String, (List<HarnessMessage>) -> List<HarnessMessage>>>(pendingStreams.size)
        for (sessionId in pendingStreams.keys.toList()) {
            val transform = pendingStreams.remove(sessionId) ?: continue
            batch.add(sessionId to transform)
        }
        for ((sessionId, transform) in batch) {
            val flow = liveFlows[sessionId] ?: continue
            flow.update { current -> boundLiveWindow(transform(current)) }
            mirrorIfForeground(sessionId, flow.value)
            lastStreamEmitAt[sessionId] = System.nanoTime()
        }
    }

    private fun upsertStreaming(
        current: List<HarnessMessage>,
        id: String,
        createdAt: Long,
        text: String,
        keepReasoning: Boolean,
    ): List<HarnessMessage> {
        val idx = current.indexOfFirst { it.id == id }
        val existing = current.getOrNull(idx)
        val message = AssistantText(
            id = id,
            createdAt = createdAt,
            text = text,
            reasoning = if (keepReasoning) (existing as? AssistantText)?.reasoning else null,
        )
        return if (idx >= 0) {
            current.toMutableList().apply { this[idx] = message }
        } else {
            current + message
        }
    }

    private fun touch(sessionId: String) {
        lastAccess[sessionId] = accessCounter.incrementAndGet()
    }

    private fun boundLiveWindow(messages: List<HarnessMessage>): List<HarnessMessage> =
        if (messages.size > SessionTreeStore.MAX_LIVE_ENTRIES) {
            messages.takeLast(SessionTreeStore.MAX_LIVE_ENTRIES)
        } else {
            messages
        }

    private fun evictLeastRecentlyUsed(protectedSessionId: String) {
        var excess = liveFlows.size - MAX_CACHED_SESSIONS
        if (excess <= 0) return
        lastAccess.entries.asSequence()
            .filter { (id, _) ->
                id != protectedSessionId && !tracker.isForeground(id) && id !in streamingSessions
            }
            .sortedBy { it.value }
            .map { it.key }
            .forEach { id ->
                if (excess <= 0) return@forEach
                if (liveFlows.remove(id) != null) {
                    lastAccess.remove(id)
                    excess--
                }
            }
    }

    private companion object {
        /**
         * 流式合并发射窗口。低于人眼可辨的刷新粒度，把 provider 的 chunk 速率与 UI 重组解耦。
         */
        const val STREAM_FLUSH_INTERVAL_MS = 50L

        /** 同一常量以 nanoTime 单位表示，用于窗口判定（System.nanoTime 与 delay 的时基不同）。 */
        const val STREAM_FLUSH_INTERVAL_NS = STREAM_FLUSH_INTERVAL_MS * 1_000_000L

        /**
         * 内存中最多缓存的会话实时消息流数量。
         * 降至 4：每个缓存会话在长对话场景下驻留的 List<HarnessMessage> 可达数 MB，
         * 256MB 堆上 8 个并发会话容易触发 OOM；4 可覆盖常见多会话工作流且安全余量更充足。
         */
        const val MAX_CACHED_SESSIONS = 4
    }
}
