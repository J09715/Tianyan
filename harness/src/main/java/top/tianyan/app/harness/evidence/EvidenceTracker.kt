package top.tianyan.app.harness.evidence

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 会话级交付证据账本：记录读过哪些文件、改过哪些文件、跑过哪些验证。
 *
 * 为什么要有它：模型可以在没有任何工具结果支撑的情况下写出「所有测试通过」。
 * 把「改动」与「验证」做成可查询的事实账本后，交付闸门（[DeliveryGate]）与
 * 声称审计（[ClaimAuditor]）才有比对依据——它们只认这里记录的事实，不认模型的自述。
 *
 * 并发：读改同锁。工具轮次可能并发执行（只读工具白名单），账本必须串行化，
 * 否则 recordModified 与 recordVerification 之间的顺序会被打乱，
 * 让「验证晚于改动」的时间判据失效。
 */
@Singleton
class EvidenceTracker @Inject constructor() {

    private val mutex = Mutex()
    private val states = HashMap<String, EvidenceState>()

    /** 读取成功即算「读过」，用于判断模型是否有依据谈论某文件。 */
    suspend fun recordRead(sessionId: String, path: String) {
        if (sessionId.isBlank() || path.isBlank()) return
        mutex.withLock {
            val current = states[sessionId] ?: EvidenceState()
            states[sessionId] = current.copy(filesRead = current.filesRead + path)
        }
    }

    /** 写入/编辑成功即算「改过」；同时推进 lastMutationAt，使更早的验证全部作废。 */
    suspend fun recordModified(sessionId: String, path: String, at: Long = System.currentTimeMillis()) {
        if (sessionId.isBlank() || path.isBlank()) return
        mutex.withLock {
            val current = states[sessionId] ?: EvidenceState()
            states[sessionId] = current.copy(
                filesModified = current.filesModified + path,
                // 时间戳只许单调前进：乱序完成（并发/重放）的写入不能把判据往回拨。
                lastMutationAt = maxOf(current.lastMutationAt, at),
            )
        }
    }

    /** 验证记录只保留最近 MAX_VERIFICATIONS 条，避免长会话里无界增长。 */
    suspend fun recordVerification(sessionId: String, record: VerificationRecord) {
        if (sessionId.isBlank()) return
        mutex.withLock {
            val current = states[sessionId] ?: EvidenceState()
            states[sessionId] = current.copy(
                verifications = (current.verifications + record).takeLast(MAX_VERIFICATIONS),
            )
        }
    }

    suspend fun state(sessionId: String): EvidenceState = mutex.withLock {
        states[sessionId] ?: EvidenceState()
    }

    /** 新用户回合开始时清账：上一回合的绿不能替本回合作证。 */
    suspend fun reset(sessionId: String) {
        if (sessionId.isBlank()) return
        mutex.withLock { states.remove(sessionId) }
    }

    companion object {
        const val MAX_VERIFICATIONS = 50
    }
}
