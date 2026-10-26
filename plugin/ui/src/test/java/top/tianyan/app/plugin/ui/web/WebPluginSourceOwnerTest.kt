package top.tianyan.app.plugin.ui.web

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.runtime.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebPluginSourceOwnerTest {
    private suspend fun runtime(dispatcher: kotlinx.coroutines.CoroutineDispatcher): Pair<PluginRuntime, PluginActivation> {
        val descriptor = PluginDescriptor("example.service", "1")
        lateinit var activation: PluginActivation
        val runtime = PluginRuntime(PluginCatalog.create(listOf(descriptor)), mapOf(descriptor.id to { activation = it }),
            object : PluginSelectionStore {
                override suspend fun disabledPlugins() = emptySet<String>()
                override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
            }, dispatcher, timeoutMillis = 100)
        runtime.initialize()
        return runtime to activation
    }

    @Test fun disposalBeforeOpenPreventsStartingTheService() = runTest {
        val (runtime, activation) = runtime(StandardTestDispatcher(testScheduler))
        val owner = WebPluginSourceOwner(activation, "source")
        assertTrue(owner.close())
        assertTrue(runCatching { owner.open { error("must not start") } }.isFailure)
        assertTrue(runtime.shutdown())
    }

    @Test fun cancellationAfterAcquisitionStillReleasesTheOwnedService() = runTest {
        val (runtime, activation) = runtime(StandardTestDispatcher(testScheduler))
        val owner = WebPluginSourceOwner(activation, "source")
        val returning = CompletableDeferred<Unit>()
        var releases = 0
        val pending = async {
            try {
                owner.open {
                    withContext(NonCancellable) { returning.await() }
                    WebPluginAssetLease(emptyMap(), release = { releases++; true })
                }
            } finally { owner.close() }
        }
        runCurrent()
        pending.cancel()
        returning.complete(Unit)
        runCurrent()
        assertTrue(pending.isCancelled)
        assertEquals(1, releases)
        assertTrue(runtime.shutdown())
        assertEquals(1, releases)
    }

    @Test fun drainingDuringOpenRetainsAndThenClosesLateReturnedService() = runTest {
        val (runtime, activation) = runtime(StandardTestDispatcher(testScheduler))
        val owner = WebPluginSourceOwner(activation, "source")
        val returning = CompletableDeferred<Unit>()
        var releases = 0
        val opening = async {
            try { runCatching { owner.open { returning.await(); WebPluginAssetLease(emptyMap(), release = { releases++; true }) } } }
            finally { owner.close() }
        }
        runCurrent()
        assertFalse(runtime.shutdown()) // In-progress acquisition cannot be mistaken for a released resource.
        returning.complete(Unit)
        runCurrent()
        assertTrue(opening.await().isFailure)
        assertEquals(1, releases)
        assertTrue(runtime.shutdown())
    }
}
