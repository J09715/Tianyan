package top.tianyan.app.runtime.virtualdisplay

import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PhoneTaskRegistryTest {
    @Test fun excludesSameSessionButAllowsOtherSessionsAndSafeRelease() {
        val registry = PhoneTaskRegistry()
        val first = registry.start("a", Job())!!
        assertNull(registry.start("a", Job()))
        assertNotNull(registry.start("b", Job()))
        registry.release("a", first)
        val replacement = registry.start("a", Job())!!
        registry.release("a", first)
        assertSame(replacement, registry.get("a"))
    }
    @Test fun pauseResumeInvalidatesInFlightObservation() = runBlocking {
        val control = PhoneTaskControl(Job())
        val version = control.awaitReady()
        assertTrue(control.isCurrent(version))
        control.pause()
        val waiting = async { control.awaitReady() }
        yield()
        assertFalse(waiting.isCompleted)
        control.resume()
        assertFalse(control.isCurrent(version))
        assertTrue(control.isCurrent(waiting.await()))
    }
    @Test fun cancelStopsOwningJob() {
        val job = Job()
        val control = PhoneTaskControl(job)
        control.cancel()
        assertTrue(job.isCancelled)
        assertEquals(PhoneTaskState.CANCELLED, control.state.value)
    }
    @Test fun distinguishesPauseFromManualPageChanges() {
        val task = PhoneTaskControl(Job())
        task.pause()
        task.resume()
        assertEquals(0L, task.manualRevision())
        task.pause(manual = true)
        task.resume()
        assertEquals(1L, task.manualRevision())
    }
}
