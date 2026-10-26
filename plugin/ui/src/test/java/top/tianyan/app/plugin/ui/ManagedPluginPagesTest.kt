package top.tianyan.app.plugin.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.runtime.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ManagedPluginPagesTest {
    @Test fun compositionDisposesPageLeaseDuringDisableAndAdmitsFreshGenerationOnEnable() = runTest {
        val runtime = PluginRuntime(
            PluginCatalog.create(listOf(PluginDescriptor("test.page", "1"))), mapOf("test.page" to {}),
            object : PluginSelectionStore {
                override suspend fun disabledPlugins() = emptySet<String>()
                override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
            }, StandardTestDispatcher(testScheduler), timeoutMillis = 1_000,
        )
        runtime.initialize()
        val clock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(EmptyApplier(), recomposer)
        val runner = launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        var observed: PluginLease? = null
        fun frame(time: Long) {
            Snapshot.sendApplyNotifications()
            runCurrent()
            clock.sendFrame(time)
            runCurrent()
        }
        try {
            composition.setContent {
                val states by runtime.states.collectAsState()
                val state = states.getValue("test.page")
                val lease = rememberPluginLease(runtime, "test.page", state.phase, state.generation)
                SideEffect { observed = lease }
            }
            runCurrent()
            frame(1)
            runCurrent()
            val first = requireNotNull(observed).activation.generation
            val disabling = async { runtime.setEnabled("test.page", false) }
            runCurrent()
            assertNull(runtime.acquire("test.page"))
            frame(2)
            runCurrent()
            assertTrue(disabling.isCompleted)
            disabling.await()
            frame(3)
            runCurrent()
            assertNull(observed)
            assertEquals(PluginPhase.STOPPED, runtime.states.value.getValue("test.page").phase)
            runtime.setEnabled("test.page", true)
            runCurrent()
            frame(4)
            runCurrent()
            frame(5)
            runCurrent()
            assertTrue(requireNotNull(observed).activation.generation > first)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
            assertTrue(runtime.shutdown())
        }
    }

    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertBottomUp(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun insertTopDown(index: Int, instance: Unit) = error("Unexpected UI node")
        override fun move(from: Int, to: Int, count: Int) = error("Unexpected UI node")
        override fun remove(index: Int, count: Int) = error("Unexpected UI node")
        override fun onClear() = Unit
    }
}
