package top.tianyan.app.harness.groupchat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroupReplyQueueTest {
    @Test fun `stop cancels the active reply and every queued reply`() = runTest {
        val queue = GroupReplyQueue(this)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val active = queue.launch("group", "bot:group") { events += "active"; gate.await() }!!
        val queued = queue.launch("group", "bot:group") { events += "queued" }!!
        runCurrent()
        assertEquals(listOf("active"), events)
        queue.cancel("group")
        gate.complete(Unit)
        runCurrent()
        assertTrue(active.isCancelled)
        assertTrue(queued.isCancelled)
        assertEquals(listOf("active"), events)
        queue.launch("group", "bot:group") { events += "new" }!!.join()
        assertEquals(listOf("active", "new"), events)
    }

    @Test fun `stopping one group does not cancel another group`() = runTest {
        val queue = GroupReplyQueue(this)
        val gate = CompletableDeferred<Unit>()
        val first = queue.launch("a", "bot:a") { gate.await() }!!
        val second = queue.launch("b", "bot:b") { gate.await() }!!
        runCurrent()
        queue.cancel("a")
        assertTrue(first.isCancelled)
        assertTrue(second.isActive)
        gate.complete(Unit)
        second.join()
        assertFalse(second.isCancelled)
    }
}
