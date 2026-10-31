package top.tianyan.app.harness.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.RuntimeState

class McpRuntimeReadyTest {

    @Test
    fun `already ready returns immediately`() = runBlocking {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        assertTrue(awaitLinuxRuntimeReady(state, timeoutMs = 50L))
    }

    @Test
    fun `waits until restore flips state to ready`() = runBlocking {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
        launch {
            delay(40)
            state.value = RuntimeState.Ready
        }
        assertTrue(awaitLinuxRuntimeReady(state, timeoutMs = 1_000L))
    }

    @Test
    fun `timeout leaves stdio discovery skipped instead of throwing`() = runBlocking {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.NotInitialized)
        assertFalse(awaitLinuxRuntimeReady(state, timeoutMs = 40L))
    }
}
