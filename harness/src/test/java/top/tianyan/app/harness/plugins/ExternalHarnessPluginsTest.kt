package top.tianyan.app.harness.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*
import top.tianyan.app.harness.workflow.WorkflowApprovalBroker

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ExternalHarnessPluginsTest {
    private val args = buildJsonObject { put("text", "hello") }
    private fun server(fixture: ExternalPackageFixture) = "plugin." + fixture.plugins.descriptors.single().id
    private val request = PluginToolRequest(args, "session", "/workspace/fixed", null)

    @Test fun importAloneDoesNotRegisterOrExecuteAndInstalledToolUsesPinnedIdentity() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        val manifest = fixture.import()
        runCurrent()
        assertTrue(fixture.plugins.descriptors.isEmpty())
        assertTrue(fixture.calls.isEmpty())
        fixture.markInstalled(manifest)
        runCurrent()
        assertEquals(manifest.version, fixture.plugins.descriptors.single().version)
        assertTrue(fixture.calls.isEmpty())
        assertEquals(true to "hello", fixture.plugins.call(server(fixture), "echo", request))
        val config = fixture.calls.single().first
        assertEquals("ubuntu", config.pluginDistribution)
        assertEquals(manifest.version, config.pluginIdentity!!.version)
        assertEquals("/opt/tianyan/tools/sample/bin/sample", config.command)
        assertEquals(listOf(config.id), fixture.closed)
    }

    @Test fun selectedUpgradeCannotReplaceInstalledCodeAndOldApprovalDomainCannotRunNewVersion() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        val first = fixture.import()
        fixture.markInstalled(first)
        runCurrent()
        val oldDomain = server(fixture)
        val next = fixture.import("2.0.0")
        assertEquals(true to "hello", fixture.plugins.call(oldDomain, "echo", request))
        assertEquals(first.version, fixture.calls.last().first.pluginIdentity!!.version)
        fixture.markInstalled(next)
        runCurrent()
        assertNotEquals(oldDomain, server(fixture))
        val calls = fixture.calls.size
        assertFalse(fixture.plugins.call(oldDomain, "echo", request).first)
        assertEquals(calls, fixture.calls.size)
    }

    @Test fun uninstallAndSystemSwitchRejectPreviouslyCapturedCallbacksBeforeStartingMcp() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        fixture.markInstalled(fixture.import())
        runCurrent()
        val callback = fixture.external.plugins().single().tools.single()
        fixture.distro.value = "debian"
        try { callback.execute(request); fail("Expected rejection") } catch (_: IllegalStateException) { }
        fixture.distro.value = "ubuntu"
        fixture.rows.value = emptyList()
        try { callback.execute(request); fail("Expected rejection") } catch (_: IllegalStateException) { }
        assertTrue(fixture.calls.isEmpty())
    }

    @Test fun providerUsesInstalledMcpAndFailsClosedAfterUninstall() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        fixture.markInstalled(fixture.import())
        runCurrent()
        val adapter = fixture.plugins.providerOverrides(OkHttpClient(), Json).values.single()
        val model = ModelConfig("Offline", "plugin.example.sample.offline", "test", "https://plugin.invalid", null)
        assertEquals("offline-response", adapter.chat(model, listOf(ApiMessage("user", "hello"))).content)
        assertEquals(1, fixture.calls.size)
        fixture.rows.value = emptyList()
        try { adapter.chat(model, emptyList()); fail("Expected rejection") } catch (_: IllegalStateException) { }
        assertEquals(1, fixture.calls.size)
    }

    @Test fun workflowCannotRunBeforeFreshApprovalAndCleanupRunsAfterSuccess() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        fixture.markInstalled(fixture.import())
        runCurrent()
        val broker = WorkflowApprovalBroker()
        val executor = PluginNodeExecutor(fixture.plugins, broker, SecretRedactor())
        val node = WorkflowNode("node", WorkflowNodeType.PLUGIN_ACTION, "Echo", config =
            mapOf("plugin" to "example.sample", "extension" to "step", "arguments" to args.toString()))
        val result = async { executor.execute(node, WorkflowRuntimeContext("run", "flow", "/workspace/fixed")) { _, _ -> } }
        runCurrent()
        assertTrue(fixture.calls.isEmpty())
        val pending = broker.currentRequest.value!!
        assertFalse(broker.decide("run", "node", WorkflowApprovalDecision(true, reviewToken = "old")))
        assertTrue(broker.decide("run", "node", WorkflowApprovalDecision(true, reviewToken = pending.reviewToken)))
        assertEquals(NodeRunStatus.SUCCESS, result.await().status)
        assertEquals(1, fixture.calls.size)
        assertEquals(1, fixture.closed.size)
    }

    @Test fun cleanupFailurePreventsFurtherProcessAllocationUntilCleanupSucceeds() = runTest {
        val fixture = ExternalPackageFixture(backgroundScope)
        fixture.markInstalled(fixture.import())
        runCurrent()
        fixture.failClose = true
        try { fixture.plugins.call(server(fixture), "echo", request); fail("Expected cleanup failure") } catch (_: IllegalStateException) { }
        try { fixture.plugins.call(server(fixture), "echo", request); fail("Expected blocked allocation") } catch (_: IllegalStateException) { }
        assertEquals(1, fixture.calls.size)
        fixture.failClose = false
        assertEquals(true to "hello", fixture.plugins.call(server(fixture), "echo", request))
        assertEquals(2, fixture.calls.size)
    }
}
