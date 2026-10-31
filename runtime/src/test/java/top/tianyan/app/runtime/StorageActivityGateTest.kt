package top.tianyan.app.runtime

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class StorageActivityGateTest {
    @Test fun maintenanceReturnsResultAndRejectsNewCommandsUntilItFinishes() = runBlocking {
        val gate = StorageActivityGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val maintenance = async { gate.cleanup { entered.complete(Unit); release.await(); "done" } }
        entered.await()
        assertTrue(runCatching { gate.activity { fail("launch was admitted") } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        assertEquals("done", maintenance.await())
        assertEquals("launched", gate.activity { "launched" })
    }
    @Test fun existingLaunchPreventsDestructiveMaintenanceFromStarting() = runBlocking {
        val gate = StorageActivityGate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val launch = async { gate.activity { entered.complete(Unit); release.await() } }
        entered.await()
        assertTrue(runCatching { gate.cleanup { fail("cleanup started") } }.exceptionOrNull() is IllegalStateException)
        release.complete(Unit)
        launch.await()
        gate.cleanup { }
    }
    @Test fun failureOrCancellationReopensAdmissionAfterMaintenance() = runBlocking {
        val gate = StorageActivityGate()
        assertNotNull(runCatching { gate.cleanup { error("failed") } }.exceptionOrNull())
        gate.activity { }
        val entered = CompletableDeferred<Unit>()
        val maintenance = launch { gate.cleanup { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        maintenance.cancelAndJoin()
        gate.activity { }
    }
}
