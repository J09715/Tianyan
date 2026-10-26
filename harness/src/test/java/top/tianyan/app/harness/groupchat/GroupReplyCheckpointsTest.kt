package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupReplyCheckpointsTest {
    @Test fun `slow writes do not block live updates and pending drafts are bounded`() = runTest {
        val releaseWrite = CompletableDeferred<Unit>()
        val finishObservation = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        var publish: (GroupReplyProgress) -> Unit = {}
        val result = async {
            withGroupReplyCheckpoints(
                persist = { saved += it.content; releaseWrite.await() },
                clock = { testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(testScheduler),
            ) { checkpoint ->
                publish = checkpoint
                finishObservation.await()
            }
        }
        runCurrent()
        advanceTimeBy(2_000)
        publish(progress("first"))
        runCurrent()
        assertEquals(listOf("first"), saved)
        advanceTimeBy(3_000)
        repeat(100) { publish(progress("update-$it")) }
        finishObservation.complete(Unit)
        runCurrent()
        assertFalse(result.isCompleted) // Final persistence waits for the active draft writer.
        releaseWrite.complete(Unit)
        runCurrent()
        result.await()
        assertEquals(listOf("first", "update-99"), saved)
    }

    @Test fun `cancellation drains writer before caller can settle the reply`() = runTest {
        val writing = CompletableDeferred<Unit>()
        var writerFinished = false
        var publish: (GroupReplyProgress) -> Unit = {}
        val result = async {
            withGroupReplyCheckpoints(
                persist = {
                    try { writing.complete(Unit); CompletableDeferred<Unit>().await() }
                    finally { writerFinished = true }
                },
                clock = { testScheduler.currentTime },
                dispatcher = StandardTestDispatcher(testScheduler),
            ) { checkpoint -> publish = checkpoint; CompletableDeferred<Unit>().await() }
        }
        runCurrent()
        advanceTimeBy(2_000)
        publish(progress("draft"))
        writing.await()
        result.cancelAndJoin()
        assertTrue(writerFinished)
    }

    @Test fun `short replies skip periodic writes`() = runTest {
        val saved = mutableListOf<String>()
        val result = withGroupReplyCheckpoints(
            persist = { saved += it.content },
            clock = { testScheduler.currentTime },
            dispatcher = StandardTestDispatcher(testScheduler),
        ) { checkpoint -> checkpoint(progress("answer")); "done" }
        assertEquals("done", result)
        assertTrue(saved.isEmpty())
    }

    private fun progress(text: String) = GroupReplyProgress(text, "", emptyList())
}
