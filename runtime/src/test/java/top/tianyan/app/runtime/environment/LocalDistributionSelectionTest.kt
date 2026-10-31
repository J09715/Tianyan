package top.tianyan.app.runtime.environment

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.RuntimeState

class LocalDistributionSelectionTest {
    @Test fun `runtime becoming ready unblocks capture even while preferences are pending`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
        val selection = LocalDistributionSelection(MutableSharedFlow(), MutableStateFlow("debian"), state, scope)
        try {
            val captured = async(start = CoroutineStart.UNDISPATCHED) { selection.capture() }
            assertFalse(captured.isCompleted)
            state.value = RuntimeState.Ready
            assertEquals("debian", withTimeout(2_000) { captured.await() })
        } finally { scope.cancel() }
    }
    @Test fun `capture waits for saved identity without waiting for RootFS`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val persisted = MutableSharedFlow<String>(replay = 1)
        val active = MutableStateFlow("ubuntu")
        val state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
        val selection = LocalDistributionSelection(persisted, active, state, scope)
        try {
            val captured = async(start = CoroutineStart.UNDISPATCHED) { selection.capture() }
            assertFalse("Temporary runtime default must not become a saved target", captured.isCompleted)
            persisted.emit("debian")
            assertEquals("debian", withTimeout(2_000) { captured.await() })
            assertEquals(RuntimeState.NotInitialized, state.value)
            assertEquals("debian", selection.current())
            assertEquals("debian", selection.changes.first())
        } finally { scope.cancel() }
    }

    @Test fun `ready runtime uses live choice even before preference write catches up`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val active = MutableStateFlow("oracle")
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        val selection = LocalDistributionSelection(flowOf("debian"), active, state, scope)
        try {
            assertEquals("oracle", selection.current())
            assertEquals("oracle", selection.capture())
            active.value = "ubuntu"
            assertEquals("ubuntu", selection.capture())
            assertEquals("ubuntu", selection.changes.first())
        } finally { scope.cancel() }
    }
}
