package top.tianyan.app.runtime.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宿主凭据权威与沙箱凭据存储器的单元测试：
 * 验证"唯一校验路径"的语义——可复用票据按动作绑定、一次性票据被原子消费、
 * 过期/撤销/代次切换一律拒绝、容量有上限、缓存层在代次切换后自愈。
 */
class HostCredentialAuthorityTest {
    private var time = 0L
    private val authority = HostCredentialAuthority({ time })

    private fun bind(
        subject: String = "ubuntu",
        actions: Set<String> = setOf("host.install-apk"),
    ) = HostCredentialAuthority.Binding(subject = subject, actions = actions)

    private fun issue(
        binding: HostCredentialAuthority.Binding = bind(),
        ttlMillis: Long = 60_000,
        reusable: Boolean = true,
    ) = authority.issue(binding, ttlMillis, reusable) { true }

    @Test fun reusableTicketStaysValidForItsBoundActionsOnly() {
        val token = issue(bind(actions = setOf("host.install-apk", "host.logcat")))
        assertTrue(authority.authorize(token, "host.install-apk"))
        assertTrue(authority.authorize(token, "host.logcat"))
        assertFalse(authority.authorize(token, "host.shell"))
        // 可复用：重复校验仍然通过
        assertTrue(authority.authorize(token, "host.install-apk"))
    }

    @Test fun oneShotTicketIsConsumedExactlyOnce() {
        val token = issue(reusable = false)
        assertTrue(authority.authorize(token, "host.install-apk"))
        assertFalse(authority.authorize(token, "host.install-apk"))
        assertFalse(authority.authorize(token, null))
    }

    @Test fun nullActionAuthorizesAnyValidCredentialAndUnknownTokensAreDenied() {
        assertTrue(authority.authorize(issue(), null))
        assertFalse(authority.authorize("", null))
        assertFalse(authority.authorize(null, null))
        assertFalse(authority.authorize("not-a-token", "host.install-apk"))
    }

    @Test fun expiryIsExactAndUnavailableBindingIsDenied() {
        val available = true
        var active = available
        val expiring = authority.issue(bind(), 10_000, reusable = true) { active }
        assertTrue(authority.authorize(expiring, "host.install-apk"))
        time = 10_000
        assertFalse(authority.authorize(expiring, "host.install-apk"))
        time = 0
        val revokedOwner = authority.issue(bind(), 60_000, reusable = true) { active }
        active = false
        assertFalse(authority.authorize(revokedOwner, "host.install-apk"))
    }

    @Test fun revokeAllForSubjectOnlyDropsThatSubject() {
        val ubuntu = issue(bind(subject = "ubuntu"))
        val alpine = issue(bind(subject = "alpine"))
        authority.revokeAllForSubject("ubuntu")
        assertFalse(authority.authorize(ubuntu, "host.install-apk"))
        assertTrue(authority.authorize(alpine, "host.install-apk"))
    }

    @Test fun revokeAllInvalidatesEverythingAndAdvancesTheEpoch() {
        val token = issue()
        val epoch = authority.epoch()
        authority.revokeAll()
        assertFalse(authority.authorize(token, "host.install-apk"))
        assertNotEquals(epoch, authority.epoch())
    }

    @Test fun issuanceRejectsIncompleteBindingsAndOutOfRangeTtl() {
        assertTrue(runCatching { issue(bind(subject = "   ")) }.isFailure)
        assertTrue(runCatching { issue(bind(actions = emptySet())) }.isFailure)
        assertTrue(runCatching { issue(ttlMillis = 0) }.isFailure)
        assertTrue(runCatching { issue(ttlMillis = 25L * 60 * 60 * 1000) }.isFailure)
    }

    @Test fun liveTicketsAreBoundedAndExpiredOnesAreRecycled() {
        repeat(256) { issue() }
        assertTrue(runCatching { issue() }.isFailure)
        time = 60_000
        assertTrue(runCatching { issue() }.isSuccess)
    }

    @Test fun sandboxStoreInjectsTokenWithBoundActionsAndReusesIt() {
        val store = SandboxCredentialStore(authority, now = { time })
        val env = store.environment("ubuntu")
        val token = env.getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        // 默认动作集不含任意宿主 shell
        assertEquals("host.install-apk,host.logcat", env.getValue(SandboxCredentialStore.BRIDGE_ACTIONS_ENV))
        assertFalse(authority.authorize(token, "host.shell"))
        assertTrue(authority.authorize(token, "host.install-apk"))
        assertFalse(authority.authorize(token, "host.app-info"))
        // 同一发行版第二条命令复用同一凭据
        assertEquals(token, store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV))
        // 新发行版拿到独立凭据
        assertNotEquals(token, store.environment("alpine").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV))
    }

    @Test fun sandboxStoreRecoversAfterGlobalRevocationAndOnExplicitRevoke() {
        val store = SandboxCredentialStore(authority, now = { time })
        val storing = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertTrue(authority.authorize(storing, "host.install-apk"))
        authority.revokeAll()
        val refreshed = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertNotEquals(storing, refreshed)
        assertTrue(authority.authorize(refreshed, "host.install-apk"))
        store.revoke("ubuntu")
        assertFalse(authority.authorize(refreshed, "host.install-apk"))
    }

    @Test fun sandboxStoreReissuesWhenGrantedActionsChange() {
        val granted = mutableSetOf("host.install-apk")
        val store = SandboxCredentialStore(authority, { granted.toSet() }, now = { time })
        val first = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        granted += "host.shell"
        val second = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertNotEquals(first, second)
        assertFalse(authority.authorize(first, "host.shell"))
        assertTrue(authority.authorize(second, "host.shell"))
    }

    @Test fun sandboxStoreNeverGrantsAnEmptyActionSet() {
        val store = SandboxCredentialStore(authority, { emptySet() }, now = { time })
        val token = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertTrue(authority.authorize(token, "host.install-apk"))
    }

    @Test fun sandboxStoreIssuesHostShellOnlyUnderFullAccessGrant() {
        val store = SandboxCredentialStore(authority, { SandboxCredentialStore.FULL_ACCESS_ACTIONS }, now = { time })
        val env = store.environment("ubuntu")
        val token = env.getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertEquals("host.install-apk,host.logcat,host.shell", env.getValue(SandboxCredentialStore.BRIDGE_ACTIONS_ENV))
        assertTrue(authority.authorize(token, "host.shell"))
    }
}
