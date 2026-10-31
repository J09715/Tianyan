package top.tianyan.app.plugin.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.api.PluginCatalog
import top.tianyan.app.plugin.api.PluginDescriptor

@OptIn(ExperimentalCoroutinesApi::class)
class PluginRuntimeTest {
    private val base = PluginDescriptor("test.base", "1")
    private val dependent = PluginDescriptor("test.ui", "1", dependencies = setOf(base.id))
    private val independent = PluginDescriptor("test.independent", "1")

    private class Selection(var disabled: Set<String> = emptySet()) : PluginSelectionStore {
        var failRead = false
        var failWrite = false
        override suspend fun disabledPlugins(): Set<String> {
            check(!failRead)
            return disabled
        }
        override suspend fun setEnabled(id: String, enabled: Boolean) {
            check(!failWrite)
            disabled = if (enabled) disabled - id else disabled + id
        }
    }

    private fun TestScope.runtime(
        plugins: List<PluginDescriptor> = listOf(base),
        actions: Map<String, suspend (PluginActivation) -> Unit> = emptyMap(),
        store: Selection = Selection(),
    ) = PluginRuntime(
        PluginCatalog.create(plugins), plugins.associate { it.id to (actions[it.id] ?: {}) },
        store, StandardTestDispatcher(testScheduler), timeoutMillis = 100,
    )

    private fun PluginController.phase(id: String = base.id) = states.value.getValue(id).phase

    @Test fun activatesDependenciesFirstAndReleasesInReverseOrder() = runTest {
        val events = mutableListOf<String>()
        val runtime = runtime(listOf(dependent, base), listOf(base, dependent).associate { plugin ->
            plugin.id to { activation: PluginActivation ->
                events += "start:${activation.pluginId}"
                activation.own("resource") { events += "stop:${activation.pluginId}"; true }
            }
        })
        runtime.initialize()
        assertEquals(listOf("start:test.base", "start:test.ui"), events)
        runtime.initialize()
        assertTrue(runtime.shutdown())
        assertEquals(listOf("start:test.base", "start:test.ui", "stop:test.ui", "stop:test.base"), events)
        assertNull(runtime.acquire(base.id))
    }

    @Test fun stopClosesAdmissionAndWaitsForExistingLeaseDisposal() = runTest {
        var releases = 0
        val runtime = runtime(actions = mapOf(base.id to { it.own("process") { releases++; true } }))
        runtime.initialize()
        val lease = runtime.acquire(base.id)!!
        val stop = async { runtime.setEnabled(base.id, false) }
        runCurrent()
        assertEquals(PluginPhase.DRAINING, runtime.phase())
        assertNull(runtime.acquire(base.id))
        assertEquals(0, releases)
        lease.close()
        lease.close()
        stop.await()
        assertEquals(1, releases)
        assertEquals(PluginPhase.STOPPED, runtime.phase())
        assertTrue(runtime.shutdown())
    }

    @Test fun timedOutLeaseRetainsGenerationAndCannotBeReplacedUntilReleased() = runTest {
        var starts = 0
        val runtime = runtime(actions = mapOf(base.id to { starts++ }))
        runtime.initialize()
        val lease = runtime.acquire(base.id)!!
        val firstGeneration = lease.activation.generation
        runtime.setEnabled(base.id, false)
        assertEquals(PluginPhase.FAILED, runtime.phase())
        assertNull(runtime.acquire(base.id))
        runtime.setEnabled(base.id, true)
        assertEquals(1, starts)
        lease.close()
        runtime.setEnabled(base.id, true)
        val replacement = runtime.acquire(base.id)!!
        assertTrue(replacement.activation.generation > firstGeneration)
        assertEquals(2, starts)
        replacement.close()
        assertTrue(runtime.shutdown())
    }

    @Test fun failedReleaseRetainsEarlierResourcesAndRetriesOnlyUnconfirmedOnes() = runTest {
        val releases = mutableListOf<String>()
        var canRelease = false
        val runtime = runtime(actions = mapOf(base.id to { activation ->
            activation.own("first") { releases += "first"; true }
            activation.own("second") { releases += "second"; canRelease }
            activation.own("third") { releases += "third"; true }
        }))
        runtime.initialize()
        runtime.setEnabled(base.id, false)
        assertEquals(listOf("third", "second"), releases)
        assertEquals(PluginPhase.FAILED, runtime.phase())
        canRelease = true
        runtime.setEnabled(base.id, false)
        assertEquals(listOf("third", "second", "second", "first"), releases)
        assertEquals(PluginPhase.STOPPED, runtime.phase())
        assertTrue(runtime.shutdown())
    }

    @Test fun retainedDependentPreventsItsDependencyFromBeingReleased() = runTest {
        var releaseChild = false
        var parentReleases = 0
        val runtime = runtime(listOf(base, dependent), mapOf(
            base.id to { it.own("parent") { parentReleases++; true } },
            dependent.id to { it.own("child") { releaseChild } },
        ))
        runtime.initialize()
        runtime.setEnabled(base.id, false)
        assertEquals(0, parentReleases)
        assertEquals(PluginPhase.FAILED, runtime.phase())
        releaseChild = true
        runtime.setEnabled(base.id, false)
        assertEquals(1, parentReleases)
        assertEquals(PluginPhase.BLOCKED, runtime.phase(dependent.id))
        assertTrue(runtime.states.value.getValue(dependent.id).selected)
        runtime.setEnabled(base.id, true)
        assertEquals(PluginPhase.RUNNING, runtime.phase(dependent.id))
        assertTrue(runtime.shutdown())
    }

    @Test fun startupFailureCleansPartialResourcesAndDoesNotRetryOrFailUnrelatedPlugin() = runTest {
        var starts = 0
        var releases = 0
        val runtime = runtime(listOf(base, dependent, independent), mapOf(base.id to {
            starts++
            it.own("partial") { releases++; true }
            error("sensitive diagnostic must not enter status")
        }))
        runtime.initialize()
        runtime.initialize()
        assertEquals(1, starts)
        assertEquals(1, releases)
        assertEquals(PluginPhase.FAILED, runtime.phase())
        assertEquals(PluginPhase.BLOCKED, runtime.phase(dependent.id))
        assertEquals(PluginPhase.RUNNING, runtime.phase(independent.id))
        assertFalse(runtime.states.value.toString().contains("sensitive"))
        runtime.setEnabled(independent.id, false)
        assertEquals(1, starts)
        assertTrue(runtime.shutdown())
    }

    @Test fun startupTimeoutCancelsInitializationAndReleasesOwnedResources() = runTest {
        var releases = 0
        val runtime = runtime(actions = mapOf(base.id to {
            it.own("partial") { releases++; true }
            awaitCancellation()
        }))
        runtime.initialize()
        assertEquals(PluginPhase.FAILED, runtime.phase())
        assertEquals(1, releases)
        assertTrue(runtime.shutdown())
    }

    @Test fun backgroundFailureClosesDependentsWithoutRestartingFailedGeneration() = runTest {
        var starts = 0
        val runtime = runtime(listOf(base, dependent, independent), mapOf(base.id to {
            starts++
            it.scope.launch { error("background failure") }
        }))
        runtime.initialize()
        runCurrent()
        assertEquals(PluginPhase.FAILED, runtime.phase())
        assertEquals(PluginPhase.BLOCKED, runtime.phase(dependent.id))
        assertEquals(PluginPhase.RUNNING, runtime.phase(independent.id))
        assertEquals(1, starts)
        assertNull(runtime.acquire(dependent.id))
        assertTrue(runtime.shutdown())
    }

    @Test fun generationJobsAreCancelledBeforeOwnedResourcesAreReleased() = runTest {
        val events = mutableListOf<String>()
        val runtime = runtime(actions = mapOf(base.id to {
            it.scope.launch { try { awaitCancellation() } finally { events += "job" } }
            it.own("resource") { events += "resource"; true }
        }))
        runtime.initialize()
        runCurrent()
        runtime.setEnabled(base.id, false)
        assertEquals(listOf("job", "resource"), events)
        assertTrue(runtime.shutdown())
    }

    @Test fun releaseTimeoutRemainsRetryableAndShutdownNeverClaimsSuccessEarly() = runTest {
        var releaseNow = false
        val runtime = runtime(actions = mapOf(base.id to {
            it.own("process") { if (!releaseNow) delay(1_000); true }
        }))
        runtime.initialize()
        assertFalse(runtime.shutdown())
        assertEquals(PluginPhase.FAILED, runtime.phase())
        assertNull(runtime.acquire(base.id))
        releaseNow = true
        assertTrue(runtime.shutdown())
    }

    @Test fun coldRuntimeRestoresSelectionAndDoesNotActivateDisabledPluginOrDependent() = runTest {
        val store = Selection(setOf(base.id, "removed.plugin"))
        var starts = 0
        val first = runtime(listOf(base, dependent, independent), store = store)
        first.initialize()
        assertEquals(PluginPhase.STOPPED, first.phase())
        assertEquals(PluginPhase.BLOCKED, first.phase(dependent.id))
        first.setEnabled(independent.id, false)
        assertTrue(first.shutdown())
        val second = runtime(listOf(base, dependent, independent), listOf(base, dependent, independent).associate { it.id to { _: PluginActivation -> starts++; Unit } }, store)
        second.initialize()
        assertEquals(0, starts)
        assertEquals(setOf(base.id, independent.id, "removed.plugin"), store.disabled)
        assertTrue(second.shutdown())
    }

    @Test fun preferenceFailuresDoNotAssumeDefaultsOrChangeRunningSelection() = runTest {
        val store = Selection().apply { failRead = true }
        val runtime = runtime(store = store)
        runtime.initialize()
        assertFalse(runtime.initialized.value)
        assertTrue(runtime.preferenceFailure.value)
        assertNull(runtime.acquire(base.id))
        store.failRead = false
        runtime.initialize()
        store.failWrite = true
        runtime.setEnabled(base.id, false)
        assertEquals(PluginPhase.RUNNING, runtime.phase())
        assertTrue(runtime.states.value.getValue(base.id).selected)
        assertTrue(runtime.preferenceFailure.value)
        assertTrue(runtime.shutdown())
    }

    @Test fun nonCooperativeChildPreventsResourceReleaseUntilItsExitIsConfirmed() = runTest {
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        var released = false
        val runtime = runtime(actions = mapOf(base.id to {
            it.scope.launch {
                withContext(NonCancellable) { entered.complete(Unit); exit.await() }
            }
            it.own("process") { released = true; true }
        }))
        runtime.initialize()
        entered.await()
        assertFalse(runtime.shutdown())
        assertFalse(released)
        exit.complete(Unit)
        runCurrent()
        assertTrue(runtime.shutdown())
        assertTrue(released)
    }

    @Test fun cleanupSuccessAfterStartupFailureDoesNotReplayActivationWithoutExplicitRetry() = runTest {
        var starts = 0
        var canRelease = false
        var canStart = false
        val runtime = runtime(listOf(base, independent), mapOf(base.id to {
            starts++
            it.own("partial") { canRelease }
            check(canStart)
        }))
        runtime.initialize()
        canRelease = true
        runtime.setEnabled(independent.id, false)
        assertEquals(1, starts)
        assertEquals(PluginPhase.FAILED, runtime.phase())
        canStart = true
        runtime.setEnabled(base.id, true)
        assertEquals(2, starts)
        assertEquals(PluginPhase.RUNNING, runtime.phase())
        assertTrue(runtime.shutdown())
    }

    @Test fun callerCancellationDoesNotSplitPersistedSelectionFromLifecycleTransition() = runTest {
        val editing = CompletableDeferred<Unit>()
        val commit = CompletableDeferred<Unit>()
        var disabled = emptySet<String>()
        val runtime = PluginRuntime(PluginCatalog.create(listOf(base)), mapOf(base.id to {}), object : PluginSelectionStore {
            override suspend fun disabledPlugins() = disabled
            override suspend fun setEnabled(id: String, enabled: Boolean) {
                editing.complete(Unit)
                commit.await()
                disabled = if (enabled) disabled - id else disabled + id
            }
        }, StandardTestDispatcher(testScheduler), 100)
        runtime.initialize()
        val operation = launch { runtime.setEnabled(base.id, false) }
        editing.await()
        operation.cancel()
        commit.complete(Unit)
        operation.join()
        assertEquals(setOf(base.id), disabled)
        assertEquals(PluginPhase.STOPPED, runtime.phase())
        assertFalse(runtime.states.value.getValue(base.id).selected)
        assertTrue(runtime.shutdown())
    }

    @Test fun publishedStatesCannotBeMutatedByConsumers() = runTest {
        val runtime = runtime()
        runtime.initialize()
        assertThrows(UnsupportedOperationException::class.java) { (runtime.states.value as MutableMap<*, *>).clear() }
        assertEquals(PluginPhase.RUNNING, runtime.phase())
        assertTrue(runtime.shutdown())
    }
}
