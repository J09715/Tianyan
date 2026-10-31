package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginDescriptor

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginPageResourceTest {
    @Test fun earlyConfirmedPageReleaseRemovesResourceFromGenerationLedger() = runTest {
        var releases = 0
        lateinit var activation: PluginActivation
        val plugin = PluginDescriptor("example.service", "1")
        val runtime = PluginRuntime(PluginCatalog.create(listOf(plugin)), mapOf(plugin.id to { owner -> activation = owner }),
            store(), StandardTestDispatcher(testScheduler))
        runtime.initialize()
        activation.own("page-source") { releases++; true }
        assertTrue(activation.releaseOwned("page-source"))
        assertTrue(activation.releaseOwned("page-source"))
        assertTrue(runtime.shutdown())
        assertEquals(1, releases)
    }

    @Test fun failedOrThrownPageReleaseRemainsInLedgerForShutdownRetry() = runTest {
        var attempts = 0
        lateinit var activation: PluginActivation
        val plugin = PluginDescriptor("example.service", "1")
        val runtime = PluginRuntime(PluginCatalog.create(listOf(plugin)), mapOf(plugin.id to { owner -> activation = owner }),
            store(), StandardTestDispatcher(testScheduler))
        runtime.initialize()
        activation.own("page-source") {
            when (++attempts) { 1 -> false; 2 -> error("stop failed"); else -> true }
        }
        assertFalse(activation.releaseOwned("page-source"))
        assertFalse(activation.releaseOwned("page-source"))
        assertTrue(runtime.shutdown())
        assertEquals(3, attempts)
    }

    private fun store() = object : PluginSelectionStore {
        override suspend fun disabledPlugins() = emptySet<String>()
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
    }
}
