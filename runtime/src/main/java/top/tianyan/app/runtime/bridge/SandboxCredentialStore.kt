package top.tianyan.app.runtime.bridge

/**
 * 沙箱宿主凭据存储器 —— 原生代码路径上唯一的沙箱凭据注入点。
 *
 * 每个已就绪的发行版签发一枚"宿主签发、可撤销"的可复用凭据，并按发行版缓存复用：
 * - 凭据通过 PRoot 命令环境注入 guest（[BRIDGE_TOKEN_ENV] / [BRIDGE_ACTIONS_ENV]）；
 * - guest 内 `tianyan-host` / `logcat-grabber` 只能使用该凭据绑定的动作集；
 * - HTTP 面不提供签发或续期端点，Web 内容与磁盘上都不存在任何共享密钥文件。
 *
 * 生产装配通过 [grantSnapshot] 绑定发行版、插件、版本和授权代次。开发者开关本身不授权；
 * 外部插件无确认时不签发凭据。每次鉴权复核快照，撤销、超时或关闭开关立即使旧凭据失效。
 * [grantedActions] 仅保留给兼容调用方和测试。
 *
 * 定性：PRoot guest 与 App 同 uid 运行，本机制是应用层代理门控，不构成强隔离边界。
 */
class SandboxCredentialStore(
    private val authority: HostCredentialAuthority,
    private val grantedActions: () -> Set<String> = { SANDBOX_ACTIONS },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val grantSnapshot: ((HostAccessScope) -> SandboxGrantSnapshot)? = null,
    private val onRevokeDistro: (String) -> Unit = {},
) {
    private class Entry(val token: String, val grant: SandboxGrantSnapshot, val issuedAt: Long)

    private val entries = mutableMapOf<HostAccessScope, Entry>()
    private val monitor = Any()
    private var observedEpoch = authority.epoch()

    /** guest 环境片段；凭据缺失、动作集变化、临期或代次切换时重新签发。 */
    fun environment(distroId: String, pluginId: String = "sandbox", version: String = "sandbox"): Map<String, String> = synchronized(monitor) {
        val target = HostAccessScope(distroId, pluginId, version)
        val grant = snapshot(target)
        val actions = grant.actions
        val epoch = authority.epoch()
        if (epoch != observedEpoch) {
            entries.clear()
            observedEpoch = epoch
        }
        val obsolete = entries.filter { (owner, entry) -> snapshot(owner) != entry.grant || now() - entry.issuedAt >= TTL_MILLIS }.keys.toList()
        obsolete.forEach { owner -> entries.remove(owner)?.let { authority.revoke(it.token) } }
        val entry = entries[target]
        if (actions.isEmpty()) {
            entry?.let { authority.revoke(it.token) }
            entries.remove(target)
            return@synchronized mapOf(BRIDGE_TOKEN_ENV to "", BRIDGE_ACTIONS_ENV to "")
        }
        val reusable = entry != null && entry.grant == grant && now() - entry.issuedAt < TTL_MILLIS / 2
        val current = if (reusable) {
            entry!!
        } else {
            entry?.let { authority.revoke(it.token) }
            Entry(authority.issue(binding(target, actions), TTL_MILLIS, reusable = true) { snapshot(target) == grant }, grant, now())
                .also { entries[target] = it }
        }
        mapOf(
            BRIDGE_TOKEN_ENV to current.token,
            BRIDGE_ACTIONS_ENV to current.grant.actions.sorted().joinToString(","),
        )
    }

    /** 发行版销毁（卸载/重置/重建）时回收该发行版凭据。 */
    fun revoke(distroId: String) {
        onRevokeDistro(distroId)
        synchronized(monitor) {
            entries.filterKeys { it.distroId == distroId }.values.forEach { authority.revoke(it.token) }
            entries.keys.removeAll { it.distroId == distroId }
        }
        authority.revokeAllForSubject(distroId)
    }

    fun revokeAll() {
        synchronized(monitor) {
            entries.values.forEach { authority.revoke(it.token) }
            entries.clear()
            observedEpoch = authority.epoch()
        }
    }

    private fun snapshot(target: HostAccessScope): SandboxGrantSnapshot = grantSnapshot?.invoke(target)
        ?: SandboxGrantSnapshot(grantedActions().ifEmpty { SANDBOX_ACTIONS }.toSet(), 0L)

    private fun binding(target: HostAccessScope, actions: Set<String>) = HostCredentialAuthority.Binding(
        subject = target.distroId,
        version = target.version,
        generation = 0,
        origin = "$SANDBOX_ORIGIN:${target.pluginId}",
        environment = SANDBOX_ENVIRONMENT,
        actions = actions,
        target = SANDBOX_TARGET,
    )

    companion object {
        const val BRIDGE_TOKEN_ENV = "TIANYAN_BRIDGE_TOKEN"
        const val BRIDGE_ACTIONS_ENV = "TIANYAN_BRIDGE_ACTIONS"

        const val SHELL_ACTION = "host.shell"

        /** 默认沙箱子集：APK 安装与 logcat 抓取；任意宿主 shell 不在其中。 */
        val SANDBOX_ACTIONS: Set<String> = setOf("host.install-apk", "host.logcat")

        /** 开发者全权模式下的动作集：默认沙箱子集加上宿主 shell。 */
        val FULL_ACCESS_ACTIONS: Set<String> = SANDBOX_ACTIONS + SHELL_ACTION

        private const val TTL_MILLIS = 8L * 60 * 60 * 1000
        private const val SANDBOX_ORIGIN = "tianyan:proot"
        private const val SANDBOX_ENVIRONMENT = "android:local"
        private const val SANDBOX_TARGET = "device"
    }
}
