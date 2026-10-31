package top.tianyan.app.lifecycle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class RuntimeLifecycleSupervisorTest {
    @Test fun activeForegroundLeaseRenewsPastTheOriginalEightHourTimeoutAndStillReleasesOnce() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val supervisor = RuntimeLifecycleSupervisor(app)
        val lease = supervisor.acquireLease("persistent-service")
        val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(4))
        supervisor.refreshActiveLeases()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofHours(5))
        assertTrue(lock.isHeld)
        lease.close()
        assertFalse(lock.isHeld)
        supervisor.refreshActiveLeases()
        assertFalse(lock.isHeld)
    }

    @Test
    fun leaseLifecycle_singleLease() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val supervisor = RuntimeLifecycleSupervisor(app)

        assertFalse(supervisor.isActive.value)
        assertEquals(0, supervisor.holderCount())

        val lease = supervisor.acquireLease("test-holder")
        assertTrue(supervisor.isActive.value)
        assertEquals(1, supervisor.holderCount())
        assertEquals("test-holder", lease.holderId)

        lease.close()
        assertFalse(supervisor.isActive.value)
        assertEquals(0, supervisor.holderCount())

        // 幂等关闭测试
        lease.close()
        assertFalse(supervisor.isActive.value)
        assertEquals(0, supervisor.holderCount())
    }

    @Test
    fun leaseLifecycle_multipleHolders() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val supervisor = RuntimeLifecycleSupervisor(app)

        val lease1 = supervisor.acquireLease("holder-1")
        val lease2 = supervisor.acquireLease("holder-2")

        assertTrue(supervisor.isActive.value)
        assertEquals(2, supervisor.holderCount())
        assertEquals(setOf("holder-1", "holder-2"), supervisor.holderSnapshot())

        lease1.close()
        assertTrue(supervisor.isActive.value)
        assertEquals(1, supervisor.holderCount())
        assertEquals(setOf("holder-2"), supervisor.holderSnapshot())

        lease2.close()
        assertFalse(supervisor.isActive.value)
        assertEquals(0, supervisor.holderCount())
    }

    @Test
    fun leaseLifecycle_concurrentAcquireAndRelease() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val supervisor = RuntimeLifecycleSupervisor(app)

        val threadCount = 8
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)

        for (i in 0 until threadCount) {
            executor.execute {
                try {
                    startLatch.await()
                    for (j in 0 until iterationsPerThread) {
                        val lease = supervisor.acquireLease("thread-$i-$j")
                        assertTrue(supervisor.holderCount() >= 1)
                        lease.close()
                    }
                } finally {
                    doneLatch.countDown()
                }
            }
        }

        startLatch.countDown()
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(0, supervisor.holderCount())
        assertFalse(supervisor.isActive.value)
    }
}
