package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.harness.mcp.McpCatalogAvailability
import top.tianyan.app.harness.mcp.LinuxMcpStdioChannelFactory
import top.tianyan.app.harness.mcp.McpCommandBuilder
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

class McpCatalogAvailabilityTest {
    private val selection = kotlinx.coroutines.flow.MutableStateFlow("debian")
    private val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader,
        arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
        if (method.name == "getActiveDistroId") selection
        else error("Must not probe the phone")
    } as LinuxRuntime
    private val preset = McpCatalog.installable.first().serverPreset()

    private fun world(installed: Boolean) = object : ExecutionEnvironment by
        FakeWorld(ExecutionEnvironmentId("ssh", "remote", "/srv/repo", "test")) {
        override suspend fun execute(command: ShellCommand): CommandResult {
            assertFalse(command.commandLine.contains("npm view"))
            assertTrue(command.commandLine.contains("probe ${preset.id}"))
            return CommandResult(0, if (installed) preset.installComponentId + "\n" else "", "", 0)
        }
    }

    @Test fun readinessFollowsBoundWorldAndPreservesDisabledPreference() = runBlocking {
        val availability = McpCatalogAvailability(runtime)
        withContext(ExecutionEnvironmentContext(world(true)) { AutoCloseable {} }) {
            assertTrue(availability.resolve(listOf(preset)).single().isEnabled)
            assertFalse(availability.resolve(listOf(preset.copy(userToggled = true))).single().isEnabled)
        }
        withContext(ExecutionEnvironmentContext(world(false)) { AutoCloseable {} }) {
            assertFalse(availability.resolve(listOf(preset.copy(isEnabled = true, userToggled = true))).single().isEnabled)
        }
    }

    @Test fun standaloneProbeClosesItsOwnedEnvironment() = runBlocking {
        var closed = false
        val owned = object : ExecutionEnvironment by world(true) {
            override suspend fun close() { closed = true }
        }
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { owner, _, _ ->
            assertEquals("mcp:catalog-probe@environment:local:debian", owner)
            owned
        })
        assertTrue(availability.resolve(listOf(preset)).single().isEnabled)
        assertTrue(closed)
    }

    @Test fun cancelledProbeIsNotConvertedIntoMissingInstallation() = runBlocking {
        val cancelled = object : ExecutionEnvironment by world(true) {
            override suspend fun execute(command: ShellCommand): CommandResult = throw CancellationException("cancelled")
        }
        try {
            withContext(ExecutionEnvironmentContext(cancelled) { AutoCloseable {} }) { McpCatalogAvailability(runtime).resolve(listOf(preset)) }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun standaloneSelectionChangeUsesANewBoundIdentity() = runBlocking {
        val owners = mutableListOf<String>()
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { owner, _, _ ->
            owners += owner
            world(true)
        })
        availability.resolve(listOf(preset))
        selection.value = "ubuntu"
        availability.resolve(listOf(preset))
        assertEquals(listOf("mcp:catalog-probe@environment:local:debian",
            "mcp:catalog-probe@environment:local:ubuntu"), owners)
    }

    @Test fun standaloneCancellationStillClosesProbeEnvironment() = runBlocking {
        var closed = false
        val owned = object : ExecutionEnvironment by world(true) {
            override suspend fun execute(command: ShellCommand): CommandResult = throw CancellationException("cancelled")
            override suspend fun close() { closed = true }
        }
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { _, _, _ -> owned })
        try {
            availability.resolve(listOf(preset))
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertTrue(closed)
    }

    @Test fun failedCleanupDisablesProbeAndRetriesBeforeOpeningReplacement() = runBlocking {
        var canClose = false
        var opened = 0
        var closes = 0
        val owned = object : ExecutionEnvironment by world(true) {
            override suspend fun close() {
                closes++
                check(canClose) { "命令进程尚未退出，保留资源供重试" }
            }
        }
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { _, _, _ ->
            opened++
            owned
        })
        assertFalse(availability.resolve(listOf(preset)).single().installationReady == true)
        assertEquals(1, opened)
        assertFalse(availability.resolve(listOf(preset)).single().installationReady == true)
        assertEquals(1, opened)
        assertEquals(2, closes)
        canClose = true
        assertTrue(availability.resolve(listOf(preset)).single().installationReady == true)
        assertEquals(2, opened)
        assertEquals(4, closes)
    }

    @Test fun cleanupFailureDoesNotReplaceProbeCancellation() = runBlocking {
        var canClose = false
        var opened = 0
        val cancelled = object : ExecutionEnvironment by world(true) {
            override suspend fun execute(command: ShellCommand): CommandResult = throw CancellationException("probe cancelled")
            override suspend fun close() { check(canClose) { "cleanup failed" } }
        }
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { _, _, _ ->
            if (++opened == 1) cancelled else world(true)
        })
        try {
            availability.resolve(listOf(preset))
            fail("Cancellation must propagate")
        } catch (cancelled: CancellationException) {
            assertEquals("probe cancelled", cancelled.message)
        }
        assertFalse(availability.resolve(listOf(preset)).single().installationReady == true)
        assertEquals(1, opened)
        canClose = true
        assertTrue(availability.resolve(listOf(preset)).single().installationReady == true)
        assertEquals(2, opened)
    }

    @Test fun failedCleanupDoesNotDisableAnotherDistribution() = runBlocking {
        val stuck = object : ExecutionEnvironment by world(true) {
            override suspend fun close() { error("cleanup failed") }
        }
        val availability = McpCatalogAvailability(runtime, ExecutionEnvironmentFactory { owner, _, _ ->
            if (owner.endsWith("local:debian")) stuck else world(true)
        })
        assertFalse(availability.resolve(listOf(preset)).single().installationReady == true)
        selection.value = "ubuntu"
        assertTrue(availability.resolve(listOf(preset)).single().installationReady == true)
    }

    @Test fun channelStartupCannotBypassMissingPackage() = runBlocking {
        var opened = false
        val missing = object : ExecutionEnvironment by world(false) {
            override suspend fun execute(command: ShellCommand) =
                if (command.commandLine.startsWith("command -v")) CommandResult(0, "/bin/sh", "", 0)
                else CommandResult(1, "", "missing", 0)
            override suspend fun openSession(config: SessionConfig): LinuxSession {
                opened = true
                error("Must not start")
            }
        }
        val factory = LinuxMcpStdioChannelFactory(runtime, McpCommandBuilder(),
            ExecutionEnvironmentFactory { _, _, _ -> missing })
        try {
            withContext(ExecutionEnvironmentContext(missing) { AutoCloseable {} }) { factory.open(preset) }
            fail("Missing package should be rejected")
        } catch (_: IllegalStateException) { }
        assertFalse(opened)
    }
}
