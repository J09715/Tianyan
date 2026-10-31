package top.tianyan.app.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class BaseCommandConcurrencyTest {
    @Test fun downloadDoesNotBlockAnotherSessionInSameWorkspace() = verifyIndependentCommands("/workspace/project")

    @Test fun sessionsWithoutWorkspaceDoNotShareCommandLock() = verifyIndependentCommands("")

    private fun verifyIndependentCommands(workspace: String) = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val releaseDownload = CompletableDeferred<Unit>()
            val downloadStarted = CompletableDeferred<Unit>()
            val download = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.dispatch(
                    listOf(HarnessTool.BASE), mutationScope = workspace,
                    isParallelSafe = ToolSchedulingPolicy::isParallelSafe,
                    needsMutationLock = ToolSchedulingPolicy::needsMutationLock,
                ) { _, _ -> downloadStarted.complete(Unit); releaseDownload.await() }
            }
            downloadStarted.await()
            val queryStarted = CompletableDeferred<Unit>()
            val query = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.dispatch(
                    listOf(HarnessTool.BASE), mutationScope = workspace,
                    isParallelSafe = ToolSchedulingPolicy::isParallelSafe,
                    needsMutationLock = ToolSchedulingPolicy::needsMutationLock,
                ) { _, _ -> queryStarted.complete(Unit) }
            }
            try {
                assertTrue("Another session's base must start before the download finishes", queryStarted.isCompleted)
                assertFalse(releaseDownload.isCompleted)
                query.join()
            } finally {
                download.cancelAndJoin()
                query.cancelAndJoin()
            }
        }
    }

    @Test fun approvedAndSubagentCommandsBypassWorkspaceMutationLock() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val releaseWrite = CompletableDeferred<Unit>()
            val write = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.WRITE, "/workspace/project") { releaseWrite.await() }
            }
            val query = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.BASE, "/workspace/project") { "pwd output" }
            }
            try {
                assertTrue("Approval and lane execution must use the same base policy", query.isCompleted)
                assertEquals("pwd output", query.await())
                assertFalse(releaseWrite.isCompleted)
            } finally {
                write.cancelAndJoin()
                query.cancelAndJoin()
            }
        }
    }

    @Test fun baseKeepsSameRoundBarrierAndCancellationDoesNotStartLaterCommands() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val events = mutableListOf<Int>()
            val releaseDownload = CompletableDeferred<Unit>()
            val round = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.dispatch(
                    listOf(1, 2), mutationScope = "/workspace/project",
                    isParallelSafe = { ToolSchedulingPolicy.isParallelSafe(HarnessTool.BASE) },
                    needsMutationLock = { ToolSchedulingPolicy.needsMutationLock(HarnessTool.BASE) },
                ) { item, _ ->
                    events += item
                    if (item == 1) releaseDownload.await()
                }
            }
            assertEquals(listOf(1), events)
            round.cancelAndJoin()
            assertEquals(listOf(1), events)
            assertEquals(0, dispatcher.retainedMutationScopeCount)
            val result = dispatcher.withToolLock(HarnessTool.BASE, "/workspace/project") { "next session" }
            assertEquals("next session", result)
        }
    }

    @Test fun baseRemainsOrderedWithinCompletedRound() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val events = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val round = launch(start = CoroutineStart.UNDISPATCHED) {
            dispatcher.dispatch(
                listOf(1, 2), isParallelSafe = { ToolSchedulingPolicy.isParallelSafe(HarnessTool.BASE) },
                needsMutationLock = { ToolSchedulingPolicy.needsMutationLock(HarnessTool.BASE) },
            ) { item, _ ->
                events += item
                if (item == 1) release.await()
            }
        }
        assertEquals(listOf(1), events)
        release.complete(Unit)
        round.join()
        assertEquals(listOf(1, 2), events)
    }

    @Test fun cancellingOneCommandLeavesOtherSessionRunning() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val release = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.BASE, "") { release.await() }
            }
            val secondStarted = CompletableDeferred<Unit>()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.BASE, "") {
                    secondStarted.complete(Unit)
                    release.await()
                    "download complete"
                }
            }
            try {
                assertTrue(secondStarted.isCompleted)
                first.cancelAndJoin()
                assertTrue(second.isActive)
                release.complete(Unit)
                assertEquals("download complete", second.await())
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
        }
    }

    @Test fun structuredWritesStillSerializeInSameWorkspace() = runBlocking {
        withTimeout(5_000) {
            val dispatcher = ToolRoundDispatcher()
            val release = CompletableDeferred<Unit>()
            val first = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.WRITE, "") { release.await() }
            }
            val secondStarted = CompletableDeferred<Unit>()
            val second = launch(start = CoroutineStart.UNDISPATCHED) {
                dispatcher.withToolLock(HarnessTool.WRITE, "") { secondStarted.complete(Unit) }
            }
            try {
                assertFalse(secondStarted.isCompleted)
                release.complete(Unit)
                first.join()
                second.join()
                assertTrue(secondStarted.isCompleted)
                assertEquals(0, dispatcher.retainedMutationScopeCount)
            } finally {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
        }
    }
}
