package top.tianyan.app.runtime.shell

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ProcessLogStoreTest {
    @Test fun concurrentWritersKeepAllLinesForSharedTool() {
        val logs = ProcessLogStore()
        val workers = Executors.newFixedThreadPool(4)
        try {
            val tasks = (0 until 4).map { worker ->
                workers.submit { repeat(100) { logs.append("shared-tool", "$worker:$it") } }
            }
            tasks.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(400, logs.get("shared-tool").size)
            assertEquals(400, logs.get("shared-tool").toSet().size)
        } finally { workers.shutdownNow() }
    }

    @Test fun completedProcessLogsAreBoundedAndExpire() {
        var now = 0L
        val logs = ProcessLogStore(maxIdleEntries = 2, retentionMillis = 100, clock = { now })
        repeat(3) { id ->
            logs.retain("process:$id")
            logs.append("process:$id", "result:$id")
            logs.release("process:$id")
        }
        assertTrue(logs.get("process:0").isEmpty())
        assertEquals(listOf("result:2"), logs.get("process:2"))
        now = 101
        logs.prune()
        assertTrue(logs.get("process:1").isEmpty())
        assertTrue(logs.get("process:2").isEmpty())
    }

    @Test fun budgetsApplyEvenWhenAllProcessesAreActive() {
        val logs = ProcessLogStore(maxTotalChars = 20, maxEntryChars = 15, maxLineChars = 10)
        logs.retain("a")
        logs.retain("b")
        repeat(3) { logs.append("a", "a".repeat(1000)); logs.append("b", "b".repeat(1000)) }
        val a = logs.get("a")
        val b = logs.get("b")
        assertTrue(a.all { it.length <= 10 })
        assertTrue(b.all { it.length <= 10 })
        assertTrue(a.sumOf(String::length) <= 15)
        assertTrue((a + b).sumOf(String::length) <= 20)
    }

    @Test fun subscribedFlowSurvivesCachePruning() = runBlocking {
        val logs = ProcessLogStore(maxIdleEntries = 1)
        val observed = logs.observe("observed")
        var latest = emptyList<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { observed.collect { latest = it } }
        try {
            repeat(5) { logs.append("idle:$it", "idle") }
            logs.append("observed", "still connected")
            yield()
            assertEquals(listOf("still connected"), latest)
        } finally { collector.cancel() }
    }

    @Test fun observerCanSubscribeAfterIdleEntryWasEvicted() = runBlocking {
        val logs = ProcessLogStore(maxIdleEntries = 1)
        logs.append("observed", "old")
        val observed = logs.observe("observed")
        logs.append("other", "evict old entry")
        var latest = emptyList<String>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { observed.collect { latest = it } }
        try {
            logs.append("observed", "new")
            yield()
            assertEquals(listOf("new"), latest)
        } finally { collector.cancel() }
    }

    @Test fun onlyNewestFiveHundredLinesAreRetained() {
        val logs = ProcessLogStore()
        repeat(600) { logs.append("a", "$it") }
        assertEquals(500, logs.get("a").size)
        assertEquals("100", logs.get("a").first())
        assertEquals("599", logs.get("a").last())
    }
}
