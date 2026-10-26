package top.tianyan.app.harness.session

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTurnCoordinatorTest {

    @Test
    fun sameSessionRunsStrictlySequentially() = runBlocking {
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 4)
        val log = mutableListOf<String>()

        val job1 = async {
            coordinator.withSessionTurn("session-A") {
                log.add("A1-start")
                delay(50)
                log.add("A1-end")
            }
        }
        val job2 = async {
            coordinator.withSessionTurn("session-A") {
                log.add("A2-start")
                delay(10)
                log.add("A2-end")
            }
        }

        awaitAll(job1, job2)

        assertEquals(listOf("A1-start", "A1-end", "A2-start", "A2-end"), log)
    }

    @Test
    fun crossSessionConcurrencyRespectsGlobalLimit() = runBlocking {
        val maxConcurrent = 2
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = maxConcurrent)

        val concurrentGauge = AtomicInteger(0)
        val maxObservedConcurrent = AtomicInteger(0)

        val jobs = (1..6).map { idx ->
            async {
                coordinator.withSessionTurn("session-$idx") {
                    val current = concurrentGauge.incrementAndGet()
                    maxObservedConcurrent.updateAndGet { prev -> if (current > prev) current else prev }
                    delay(30)
                    concurrentGauge.decrementAndGet()
                }
            }
        }

        jobs.awaitAll()

        assertTrue(
            "Observed concurrency (${maxObservedConcurrent.get()}) exceeded global limit ($maxConcurrent)",
            maxObservedConcurrent.get() <= maxConcurrent,
        )
        assertEquals(0, coordinator.activeTurnCount.value)
    }

    @Test
    fun cancellationReleasesGlobalPermitAndSessionMutex() = runBlocking {
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 1)

        val job1 = launch {
            coordinator.withSessionTurn("session-X") {
                delay(500)
            }
        }

        delay(20) // 确保 job1 已经拿到锁和信号量
        assertEquals(1, coordinator.activeTurnCount.value)

        // 取消 job1
        job1.cancel()
        job1.join()

        assertEquals(0, coordinator.activeTurnCount.value)

        // job2 应该能顺利执行完成，不被挂死
        var job2Finished = false
        coordinator.withSessionTurn("session-X") {
            job2Finished = true
        }
        assertTrue(job2Finished)
    }

    @Test
    fun withSessionMutexDoesNotConsumeGlobalTurnPermit() = runBlocking {
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 1)

        // 先占用全局唯一的 Turn 槽位
        val turnJob = launch {
            coordinator.withSessionTurn("session-1") {
                delay(100)
            }
        }

        delay(20)
        assertEquals(1, coordinator.activeTurnCount.value)

        // 在 session-2 上执行轻量级 withSessionMutex，不应该被 globalLimit 阻塞
        var mutexExecuted = false
        coordinator.withSessionMutex("session-2") {
            mutexExecuted = true
        }

        assertTrue("withSessionMutex should execute without waiting for global turn permit", mutexExecuted)
        turnJob.join()
    }

    @Test
    fun sameSessionStateMutexIsNotBlockedByActiveTurnExecution() = runBlocking {
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 2)

        val turnRunning = launch {
            coordinator.withSessionTurn("session-dual") {
                delay(300)
            }
        }

        delay(30) // 确保 turn 已经开始持锁执行

        val startMs = System.currentTimeMillis()
        var mutexFinished = false
        // 关键验证：同一会话在 turn 执行期间调用 withSessionMutex，能够立即执行，绝不被卡住
        coordinator.withSessionMutex("session-dual") {
            mutexFinished = true
        }
        val elapsed = System.currentTimeMillis() - startMs

        assertTrue("withSessionMutex on same session must execute immediately", mutexFinished)
        assertTrue("withSessionMutex should not wait for active turn to finish (elapsed: ${elapsed}ms)", elapsed < 200)

        turnRunning.join()
    }

    @Test
    fun evictSessionDuringInFlightTurnPreservesSessionSerialization() = runBlocking {
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 2)
        val log = mutableListOf<String>()

        val inFlight = launch {
            coordinator.withSessionTurn("session-evict") {
                log.add("T1-start")
                delay(200)
                log.add("T1-end")
            }
        }
        delay(30) // 确保 T1 已持有会话 turnMutex

        // 会话删除/复活场景：在 T1 仍在执行时 evict
        coordinator.evictSession("session-evict")

        val revived = launch {
            coordinator.withSessionTurn("session-evict") {
                log.add("T2-start")
            }
        }

        revived.join()
        // T2 必须等 T1 释放同一把 turnMutex 后才开始：串行化未被 evict 打破
        assertEquals(listOf("T1-start", "T1-end", "T2-start"), log)
        inFlight.join()
    }

    @Test
    fun highPriorityTurnJumpsAheadOfNormalPriorityInGlobalQueue() = runBlocking {
        // 全局槽位只有 1 个
        val coordinator: SessionTurnCoordinator = SessionTurnCoordinatorImpl(initialMaxConcurrentTurns = 1)
        val executionOrder = mutableListOf<String>()
        val releaseSlot = CompletableDeferred<Unit>()

        // 同步执行到首个挂起点，保证槽位已占用、两个 waiter 都已注册后才释放。
        val occupyingJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withSessionTurn("occupying-session") {
                releaseSlot.await()
            }
        }

        // 普通优先级排队
        val normalJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withSessionTurn("normal-session", TurnPriority.NORMAL) {
                executionOrder.add("normal")
            }
        }

        // 高优先级排队（模拟审批续跑或用户打断）
        val highJob = launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.withSessionTurn("high-session", TurnPriority.HIGH) {
                executionOrder.add("high")
            }
        }

        releaseSlot.complete(Unit)
        occupyingJob.join()
        highJob.join()
        normalJob.join()

        // 验证：high 必须优先于 normal 执行完成
        assertEquals(listOf("high", "normal"), executionOrder)
    }
}

