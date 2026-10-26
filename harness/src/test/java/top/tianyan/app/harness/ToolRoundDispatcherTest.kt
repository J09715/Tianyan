package top.tianyan.app.harness

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException

class ToolRoundDispatcherTest {

    @Test
    fun `cancelled waiter retains shared lock until remaining callers finish`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            dispatcher.withMutationLock("workspace") {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val cancelled = launch { dispatcher.withMutationLock("workspace") { error("cancelled waiter ran") } }
        yield()
        cancelled.cancel()
        cancelled.join()
        var ran = false
        val remaining = launch { dispatcher.withMutationLock("workspace") { ran = true } }
        yield()
        assertEquals(1, dispatcher.retainedMutationScopeCount)
        assertTrue(!ran)
        release.complete(Unit)
        holder.join()
        remaining.join()
        assertTrue(ran)
        assertEquals(0, dispatcher.retainedMutationScopeCount)
    }

    @Test
    fun `completed workspace rounds reclaim locks and read-only rounds allocate none`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        repeat(100) { index ->
            dispatcher.dispatch(listOf(index), mutationScope = "workspace-$index", isParallelSafe = { false }) { _, _ -> }
        }
        dispatcher.dispatch(listOf(1, 2), isParallelSafe = { true }) { _, _ ->
            assertEquals(0, dispatcher.retainedMutationScopeCount)
        }
        assertEquals(0, dispatcher.retainedMutationScopeCount)
    }

    @Test
    fun `mutations across independent single and parallel rounds do not overlap`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<Int>()
        val first = launch {
            dispatcher.dispatch(listOf(1), isParallelSafe = { false }) { item, _ ->
                events += item
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val second = launch {
            dispatcher.dispatch(listOf(2, 3), isParallelSafe = { false }) { item, _ -> events += item }
        }
        repeat(5) { yield() }
        assertEquals(listOf(1), events)
        release.complete(Unit)
        first.join()
        second.join()
        assertEquals(setOf(1, 2, 3), events.toSet())
    }

    @Test
    fun `approval abort skips mutations already waiting for lock`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val executed = mutableListOf<Int>()
        dispatcher.dispatch(listOf(1, 2, 3), isParallelSafe = { false }) { item, pause ->
            executed += item
            yield()
            pause.abort()
        }
        assertEquals(1, executed.size)
    }

    private data class Item(val id: Int, val parallelSafe: Boolean)

    @Test
    fun `runs all items and preserves count`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val executed = AtomicInteger()
        val items = (1..10).map { Item(it, parallelSafe = true) }

        dispatcher.dispatch(items, parallelism = 4, isParallelSafe = { it.parallelSafe }) { item, _ ->
            delay(10)
            executed.incrementAndGet()
        }

        assertEquals(10, executed.get())
    }

    @Test
    fun `parallel safe items overlap in time`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        var concurrent = 0
        var maxConcurrent = 0
        val items = (1..4).map { Item(it, parallelSafe = true) }

        dispatcher.dispatch(items, parallelism = 4, isParallelSafe = { it.parallelSafe }) { _, _ ->
            val current = synchronized(this) {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
                concurrent
            }
            delay(100)
            synchronized(this) { concurrent-- }
            current
        }

        assertTrue("expected overlap but max concurrency was $maxConcurrent", maxConcurrent > 1)
    }

    @Test
    fun `mutating items never overlap`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        var concurrent = 0
        var maxConcurrent = 0
        val items = (1..4).map { Item(it, parallelSafe = false) }

        dispatcher.dispatch(items, parallelism = 4, isParallelSafe = { it.parallelSafe }) { _, _ ->
            synchronized(this) {
                concurrent++
                maxConcurrent = maxOf(maxConcurrent, concurrent)
            }
            delay(50)
            synchronized(this) { concurrent-- }
        }

        assertEquals(1, maxConcurrent)
    }

    @Test
    fun `pause abort stops later items from starting`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val executed = mutableListOf<Int>()
        val items = (1..4).map { Item(it, parallelSafe = false) }

        dispatcher.dispatch(items, parallelism = 1, isParallelSafe = { it.parallelSafe }) { item, pause ->
            executed += item.id
            if (item.id == 2) pause.abort()
        }

        assertEquals(listOf(1, 2), executed)
    }

    @Test
    fun `single item and parallelism one run serially`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val order = mutableListOf<Int>()

        dispatcher.dispatch(listOf(Item(1, true)), parallelism = 4, isParallelSafe = { it.parallelSafe }) { item, _ ->
            order += item.id
        }
        dispatcher.dispatch(emptyList<Item>(), parallelism = 4, isParallelSafe = { it.parallelSafe }) { _, _ ->
            error("should not run")
        }

        assertEquals(listOf(1), order)
    }

    @Test
    fun `cancellation propagates from outside scope`() = runBlocking {
        val dispatcher = ToolRoundDispatcher()
        val items = (1..4).map { Item(it, parallelSafe = true) }
        val job = async {
            dispatcher.dispatch(items, parallelism = 2, isParallelSafe = { it.parallelSafe }) { _, _ ->
                delay(10_000)
            }
        }
        job.cancel()
        var cancelled = false
        try {
            job.await()
        } catch (cancellation: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }
}
