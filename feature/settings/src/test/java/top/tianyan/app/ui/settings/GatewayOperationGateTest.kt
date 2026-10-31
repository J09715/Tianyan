package top.tianyan.app.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayOperationGateTest {
    @Test fun operationsRunSeriallyInSubmissionOrder() = runBlocking {
        val gate = GatewayOperationGate()
        val order = mutableListOf<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run { order += "first-enter"; firstEntered.complete(Unit); release.await(); order += "first-exit" }
        }
        firstEntered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { order += "second" } }
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first-enter", "first-exit", "second"), order)
    }

    @Test fun operatingStaysTrueUntilTheLastOverlappingOperationCompletes() = runBlocking {
        val gate = GatewayOperationGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { entered.complete(Unit); release.await() } }
        entered.await()
        assertTrue(gate.operating.value)
        val second = launch(start = CoroutineStart.UNDISPATCHED) { gate.run { } }
        release.complete(Unit)
        first.join()
        second.join()
        assertFalse(gate.operating.value)
    }

    @Test fun failedOperationStillResetsOperatingAndKeepsTheGateUsable() = runBlocking {
        val gate = GatewayOperationGate()
        val error = runCatching { gate.run<Unit> { error("boom") } }.exceptionOrNull()
        assertEquals("boom", error?.message)
        assertFalse(gate.operating.value)
        assertEquals(1, gate.run { 1 })
        assertFalse(gate.operating.value)
    }
}
