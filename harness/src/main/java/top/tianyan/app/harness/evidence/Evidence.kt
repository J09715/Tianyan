package top.tianyan.app.harness.evidence

/** 交付状态：未验证 / 已验证 / 验证失败 / 被阻塞。 */
enum class DeliveryStatus { UNVERIFIED, VERIFIED, FAILED, BLOCKED }

/**
 * 一条验证记录。[at] 是验证结束时刻，必须与 [EvidenceState.lastMutationAt] 比较，
 * 只有「晚于最后一次改动」的验证才能证明当前代码状态。
 */
data class VerificationRecord(
    val command: String,
    val passed: Boolean,
    val at: Long,
    val summary: String = "",
)

/** 单个会话的交付证据快照。 */
data class EvidenceState(
    val filesRead: Set<String> = emptySet(),
    val filesModified: Set<String> = emptySet(),
    val verifications: List<VerificationRecord> = emptyList(),
    val lastMutationAt: Long = 0L,
)
