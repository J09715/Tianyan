package top.tianyan.app.runtime.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/** 开关不授权；临时确认按系统、插件和版本隔离，旧令牌不可复活。 */
class FullAccessGrantTest {
    @Test fun externalPrincipalsHaveNoDefaultCredentialAndDistroRevokeInvalidatesExistingToken() {
        val grant = FullAccessGrant(MutableStateFlow(true), CoroutineScope(Dispatchers.Unconfined))
        val authority = HostCredentialAuthority()
        val store = SandboxCredentialStore(authority, grantSnapshot = grant::snapshot, onRevokeDistro = grant::revokeDistro)
        val target = HostAccessScope("ubuntu", "example.plugin", "1.0.0")
        assertEquals("", store.environment(target.distroId, target.pluginId, target.version).getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV))
        grant.allow(target)
        val token = store.environment(target.distroId, target.pluginId, target.version).getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertTrue(authority.authorize(token, "host.install-apk"))
        store.revoke("ubuntu")
        assertFalse(authority.authorize(token, "host.install-apk"))
        assertEquals(emptySet<String>(), grant.snapshot(target).actions)
    }
    @Test fun developerModeAloneNeverGrantsShellAndConsentRequiresIt() {
        val mode = MutableStateFlow(false)
        val grant = FullAccessGrant(mode, CoroutineScope(Dispatchers.Unconfined))
        val target = HostAccessScope("ubuntu", "example.plugin", "1.0.0")
        assertEquals(SandboxCredentialStore.SANDBOX_ACTIONS, grant.actions())
        assertFalse(grant.allow(target))
        mode.value = true
        assertEquals(emptySet<String>(), grant.snapshot(target).actions)
        assertTrue(grant.allow(target))
        assertEquals(SandboxCredentialStore.FULL_ACCESS_ACTIONS, grant.snapshot(target).actions)
        mode.value = false
        assertEquals(emptySet<String>(), grant.snapshot(target).actions)
    }

    @Test fun existingShellTokenIsRejectedImmediatelyAndCannotReviveOnReenable() {
        val mode = MutableStateFlow(true)
        val grant = FullAccessGrant(mode, CoroutineScope(Dispatchers.Unconfined))
        val authority = HostCredentialAuthority()
        val store = SandboxCredentialStore(authority, grantSnapshot = grant::snapshot)
        val target = HostAccessScope("ubuntu")
        grant.allow(target)
        val old = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertTrue(authority.authorize(old, "host.shell"))
        mode.value = false
        assertFalse(authority.authorize(old, "host.shell"))
        mode.value = true
        grant.allow(target)
        assertFalse(authority.authorize(old, "host.shell"))
        val next = store.environment("ubuntu").getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        assertTrue(authority.authorize(next, "host.shell"))
    }

    @Test fun consentIsBoundToDistroPluginAndVersionAndExpires() {
        var time = 0L
        val grant = FullAccessGrant(MutableStateFlow(true), CoroutineScope(Dispatchers.Unconfined), now = { time })
        val target = HostAccessScope("ubuntu", "example.plugin", "1.0.0")
        grant.allow(target)
        listOf(target.copy(distroId = "debian"), target.copy(pluginId = "example.other"), target.copy(version = "2.0.0"))
            .forEach { assertEquals(emptySet<String>(), grant.snapshot(it).actions) }
        time = FullAccessGrant.LIFETIME_MILLIS
        assertEquals(emptySet<String>(), grant.snapshot(target).actions)
    }

    @Test fun explicitRevokeAndRestartNeverRestorePreviousConsent() {
        val mode = MutableStateFlow(true)
        val grant = FullAccessGrant(mode, CoroutineScope(Dispatchers.Unconfined))
        val authority = HostCredentialAuthority()
        val target = HostAccessScope("ubuntu", "example.plugin", "1.0.0")
        val store = SandboxCredentialStore(authority, grantSnapshot = grant::snapshot)
        grant.allow(target)
        val token = store.environment(target.distroId, target.pluginId, target.version).getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)
        grant.revoke(target)
        assertFalse(authority.authorize(token, "host.shell"))
        val restarted = FullAccessGrant(mode, CoroutineScope(Dispatchers.Unconfined))
        assertEquals(emptySet<String>(), restarted.snapshot(target).actions)
    }
}
