package top.tianyan.app.core.tools

import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.common.McpCatalogRevisions
import top.tianyan.app.core.model.McpCatalog
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.*
import java.util.UUID

class McpCatalogUpgradeTest {
    @Test fun `already latest does not restart existing processes or query again`() = runBlocking {
        val distro = "unchanged-${UUID.randomUUID()}"
        val entry = McpCatalog.installable.first()
        var calls = 0
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getActiveDistroId" -> MutableStateFlow(distro)
                "execute" -> { calls++; CommandResult(0, "INSTALLED=2.2.0\nLATEST=2.2.0\nCHANGED=0\n", "", 0) }
                else -> error(method.name)
            }
        } as LinuxRuntime
        assertTrue(McpCatalogUpdater(runtime).upgrade(entry).error.isBlank())
        assertEquals(1, calls)
        assertEquals(0L, McpCatalogRevisions.current(distro, entry.id))
    }
    @Test fun `successful upgrade publishes for captured distro even if selection changes`() = runBlocking {
        val distro = "distro-${UUID.randomUUID()}"
        val selected = MutableStateFlow(distro)
        val executed = mutableListOf<String?>()
        val entry = McpCatalog.installable.first()
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getActiveDistroId" -> selected
                "execute" -> {
                    executed += args[1] as String?
                    selected.value = "other"
                    CommandResult(0, "INSTALLED=2.2.1\nLATEST=2.2.1\nCHANGED=1\n", "", 0)
                }
                else -> error(method.name)
            }
        } as LinuxRuntime
        assertTrue(McpCatalogUpdater(runtime).upgrade(entry).error.isBlank())
        assertEquals(listOf(distro), executed)
        assertEquals(1L, McpCatalogRevisions.current(distro, entry.id))
        assertEquals(0L, McpCatalogRevisions.current("other", entry.id))
    }

    @Test fun `failed or cancelled upgrade does not publish a replacement`() = runBlocking {
        val entry = McpCatalog.installable.first()
        for (cancel in listOf(false, true)) {
            val distro = "failed-${UUID.randomUUID()}"
            val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
                when (method.name) {
                    "getActiveDistroId" -> MutableStateFlow(distro)
                    "execute" -> if (cancel) throw CancellationException("cancel") else CommandResult(1, "", "[MCP] 错误：install failed", 0)
                    else -> error(method.name)
                }
            } as LinuxRuntime
            try {
                assertTrue(McpCatalogUpdater(runtime).upgrade(entry).error.isNotBlank())
                assertFalse(cancel)
            } catch (_: CancellationException) { assertTrue(cancel) }
            assertEquals(0L, McpCatalogRevisions.current(distro, entry.id))
        }
    }
}
