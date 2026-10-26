package top.tianyan.app.runtime.bridge

import java.util.UUID

/**
 * 宿主凭据权威 (Host Credential Authority) —— 宿主侧唯一的票据签发与校验路径。
 *
 * 设计要点：
 * 1. 只有原生代码可以签发（[issue] 不暴露任何 HTTP 端点），签发时绑定身份、动作集、目标与可选正文；
 * 2. 一次性票据在首次校验时原子消费，可复用票据在有效期内反复校验（沙箱长期凭据）；
 * 3. 过期、显式撤销、代次 (epoch) 切换、归属不可用四种情形一律拒绝；
 * 4. 票据永不返回 Web 内容、不写磁盘、不进日志；
 * 5. 定性：PRoot guest 与 App 同 uid 运行，本机制是应用层代理门控，不是对同 uid 恶意代码的强隔离边界。
 */
class HostCredentialAuthority(
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val capacity: Int = MAX_TICKETS,
) {

    /**
     * 原生签发的绑定事实。[payload] 供适配层做正文等价校验（沙箱凭据为 null），
     * [actions] 是本次凭据允许触发的结构化动作集合。
     */
    internal class Binding(
        val subject: String,
        val actions: Set<String>,
        val target: String = "device",
        val version: String = "",
        val generation: Long = 0,
        val origin: String = "",
        val environment: String = "",
        val payload: Any? = null,
    )

    internal class Ticket(
        val binding: Binding,
        val deadline: Long,
        val epoch: Long,
        val reusable: Boolean,
        val available: () -> Boolean,
    )

    private val tickets = mutableMapOf<String, Ticket>()
    private val monitor = Any()
    private var epoch = 0L

    internal fun issue(
        binding: Binding,
        ttlMillis: Long,
        reusable: Boolean,
        available: () -> Boolean,
    ): String = synchronized(monitor) {
        require(ttlMillis in 1..MAX_TTL_MILLIS) { "Host credential ttl out of range" }
        require(binding.subject.isNotBlank() && binding.actions.isNotEmpty()) { "Host credential binding is incomplete" }
        tickets.entries.removeAll { now() >= it.value.deadline }
        check(tickets.size < capacity && available()) { "Host credential unavailable" }
        val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
        tickets[token] = Ticket(binding, now() + ttlMillis, epoch, reusable, available)
        token
    }

    /** 原子取出票据：一次性票据即消费（重放必失败），可复用票据保留至过期或撤销。 */
    internal fun begin(token: String?): Ticket? = synchronized(monitor) {
        val ticket = tickets[token] ?: return null
        if (!ticket.reusable) tickets.remove(token)
        ticket
    }

    /** 票据当前仍有效：代次一致、未过期、归属可用。 */
    internal fun valid(ticket: Ticket?): Boolean = synchronized(monitor) {
        ticket != null && ticket.epoch == epoch && now() < ticket.deadline && ticket.available()
    }

    /**
     * 端点的统一校验入口：取出票据 + 有效性 +（可选）所需动作绑定。
     * [action] 为 null 表示只校验"持有有效凭据"，用于未知路径先鉴权后 404。
     */
    internal fun authorize(token: String?, action: String?): Boolean {
        val ticket = begin(token) ?: return false
        return valid(ticket) && (action == null || action in ticket.binding.actions)
    }

    internal fun revoke(token: String) {
        synchronized(monitor) { tickets.remove(token) }
    }

    /** 发行版级兜底撤销：长驻终端/后台服务凭据在发行版销毁时必须回收。 */
    fun revokeAllForSubject(subject: String) {
        synchronized(monitor) { tickets.entries.removeAll { it.value.binding.subject == subject } }
    }

    /** 代次标记：全量撤销后自增，供缓存层感知自身凭据已失效。 */
    internal fun epoch(): Long = synchronized(monitor) { epoch }

    fun revokeAll() {
        synchronized(monitor) {
            epoch++
            tickets.clear()
        }
    }

    private companion object {
        const val MAX_TICKETS = 256
        const val MAX_TTL_MILLIS = 24L * 60 * 60 * 1000
    }
}
