package top.tianyan.app.harness

/**
 * 思考流生命周期计时器（对标 RikkaHub 的多通道 StreamChunk 思考流耗时统计）。
 *
 * 语义：首个 reasoning 增量到达时开始计时；首个正文增量到达时停止；
 * 若 reasoning 结束后一直没有正文（例如只输出 reasoning 或流被截断），
 * 则由 [finish] 在流收尾时以当前时刻封口。非推理模型未观测到 reasoning 时返回 null。
 *
 * 时钟可注入（默认 [System.currentTimeMillis]），便于确定性单元测试。
 */
internal class ReasoningTimingTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private var startedAt: Long? = null
    private var finishedAt: Long? = null

    /** reasoning 增量到达：仅首次开启计时。 */
    fun onReasoningChunk() {
        if (startedAt == null) startedAt = clock()
    }

    /** 正文增量到达：结束思考计时（只记一次）。 */
    fun onContentChunk() {
        if (startedAt != null && finishedAt == null) finishedAt = clock()
    }

    /** 流收尾：封口未结束的思考计时并返回耗时（毫秒）；从未观测到 reasoning 时返回 null。 */
    fun finish(): Long? {
        if (startedAt != null && finishedAt == null) finishedAt = clock()
        val start = startedAt ?: return null
        val end = finishedAt ?: return null
        return (end - start).coerceAtLeast(0)
    }
}