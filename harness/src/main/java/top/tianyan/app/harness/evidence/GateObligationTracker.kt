package top.tianyan.app.harness.evidence

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 闸门义务账本：给「闸门强制续跑」设一个硬上限。
 *
 * 为什么必须有它：闸门若可以无限次否决完成，就是一个死循环发生器——
 * 模型不跑验证 → 闸门继续 → 模型还是不跑 → 再继续。这里的语义是
 * 「同一个回合、同一个理由，只给一次机会」：第一次问 true，之后恒为 false，
 * 于是闸门最多把回合延长一轮，之后必须放行完成。
 *
 * key 用调用方给的语义名（如 "delivery-gate" / "claim-audit"），
 * 不同理由各占一次机会，互不挤占。
 */
@Singleton
class GateObligationTracker @Inject constructor() {

    private val consumed = ConcurrentHashMap<String, MutableSet<String>>()

    /** 首次调用返回 true，之后同一 (sessionId, key) 恒为 false。 */
    fun shouldContinueOnce(sessionId: String, key: String): Boolean {
        if (sessionId.isBlank()) return true
        val keys = consumed.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        return keys.add(key)
    }

    /** 新用户回合开始时重置，让闸门在新回合里重新拥有一次机会。 */
    fun reset(sessionId: String) {
        if (sessionId.isBlank()) return
        consumed.remove(sessionId)
    }
}
