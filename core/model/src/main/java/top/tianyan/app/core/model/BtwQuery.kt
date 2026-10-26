package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/** 旁路问答（/btw）的执行状态。 */
@Serializable
enum class BtwStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * 旁路问答记录：Bot 忙碌时基于当前会话快照的"顺便问一句"。
 *
 * 回答由一次无工具意图的单次模型调用产生：只读、不进模型可见上下文、不写 Bot 记忆；
 * 卡片仅用户可见。答案基于快照时的已有进展，可能落后于实际进展。
 */
@Serializable
data class BtwQuery(
    val id: String,
    val sessionId: String,
    val question: String,
    val answer: String = "",
    val status: BtwStatus = BtwStatus.RUNNING,
    val error: String = "",
    val createdAt: Long,
    val finishedAt: Long? = null,
    val modelId: String? = null,
    val providerId: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedTokens: Long = 0,
) {
    val isRunning: Boolean get() = status == BtwStatus.RUNNING
}
