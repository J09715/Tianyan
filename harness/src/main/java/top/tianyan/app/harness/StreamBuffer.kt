package top.tianyan.app.harness

import top.tianyan.app.core.model.ChunkedText

/**
 * 流式增量累积缓冲 + 发布节流器。
 *
 * 正文快照共享已封存的字符块，只复制活动尾块；字符串兼容接口按需拼接全文。
 * 发布间隔控制 UI 快照频率，不阻塞 SSE 接收；流结束后由调用方刷新剩余尾部。
 *
 * [maxChars] 限制累积上限（用于推理等过程数据；正文不可设限，工具调用协议依赖完整原文），
 * 超限后静默丢弃后续增量——推理是执行草稿，不是长期上下文。
 */
class StreamBuffer(private val maxChars: Int = Int.MAX_VALUE) {
    private val builder = ChunkedText.Builder()
    private var lastPublishAt = 0L
    private var publishedLength = 0

    val length: Int get() = builder.length

    fun append(chunk: String) {
        if (builder.length < maxChars) builder.append(chunk.take(maxChars - builder.length))
    }

    fun clear() {
        builder.clear()
        publishedLength = 0
        // 重试是新回合：清除发布时钟，允许清空后立即重新发布
        lastPublishAt = 0L
    }

    override fun toString(): String = snapshot().toString()

    /**
     * 距上次发布超过 [intervalMs] 且内容有新增时，返回当前全量内容；否则返回 null。
     * 内容不变（如已达累积上限）时不重复发布。
     */
    fun publishIfDue(nowMs: Long, intervalMs: Long): String? {
        return publishSnapshotIfDue(nowMs, intervalMs)?.toString()
    }

    fun snapshot(): ChunkedText = builder.snapshot()

    fun publishSnapshotIfDue(nowMs: Long, intervalMs: Long): ChunkedText? {
        if (builder.length == 0 || builder.length == publishedLength) return null
        if (nowMs - lastPublishAt < intervalMs) return null
        lastPublishAt = nowMs
        publishedLength = builder.length
        return snapshot()
    }
}
