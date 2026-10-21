package top.tianyan.app.runtime.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 进程表的并发读安全契约。
 *
 * ## 为什么单独测这个
 *
 * [ProcessRegistryImpl] 的写路径（start / stop / stopAll / cleanupDeadProcesses）
 * 都在 `Mutex.withLock` 里，但 `list()` 的接口签名里**没有 suspend**——它拿不到
 * Mutex。于是出现「写端加锁、读端不加锁」的结构。
 *
 * 用裸 `LinkedHashMap` 时这会炸：读端遍历 `values` 的同时写端正好在重建节点，
 * 抛 `ConcurrentModificationException`。症状是「后台任务列表偶尔一刷新就崩」，
 * 而且只在特定时序下出现，线上几乎无法复现——这种 bug 必须在单测里钉死。
 *
 * 这里用与实现同构的最小复现（`Collections.synchronizedMap(LinkedHashMap)`）
 * 来验证「读端在写端高频改动下不抛 CME，且每次拿到的都是自洽快照」。
 */
class ProcessRegistryConcurrencyTest {

    @Test
    fun `synchronized map survives concurrent writes during reads`() {
        val map = Collections.synchronizedMap(LinkedHashMap<String, String>())
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable>(null)
        val started = CountDownLatch(1)

        // 写端：不停增删，模拟 start/stop 交错
        val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "writer") }
        // 读端：不停遍历，模拟 UI 刷新
        val readers = Executors.newFixedThreadPool(4) { r -> Thread(r, "reader") }

        try {
            writer.submit {
                started.countDown()
                var i = 0
                while (!stop.get()) {
                    map["k${i % 64}"] = "v$i"
                    map.remove("k${(i + 1) % 64}")
                    i++
                }
            }

            val readJobs = (1..4).map {
                readers.submit {
                    started.await()
                    try {
                        while (!stop.get()) {
                            // 与 list() 相同的读法：整份快照
                            val snapshot = synchronized(map) { map.values.toList() }
                            assertTrue(snapshot.size <= 64)
                        }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }

            Thread.sleep(300)
            stop.set(true)
            readJobs.forEach { it.get(10, TimeUnit.SECONDS) }
            writer.shutdown()
        } finally {
            stop.set(true)
            readers.shutdownNow()
            writer.shutdownNow()
        }

        failure.get()?.let { throw AssertionError("并发读写期间出错：$it", it) }
    }

    @Test
    fun `snapshot is independent from later mutations`() {
        val map = Collections.synchronizedMap(LinkedHashMap<String, String>())
        map["a"] = "1"
        val snapshot = synchronized(map) { map.values.toList() }
        map["b"] = "2"
        map.remove("a")
        // 快照是当时的一致状态，不受后续改动影响
        assertEquals(listOf("1"), snapshot)
    }
}
