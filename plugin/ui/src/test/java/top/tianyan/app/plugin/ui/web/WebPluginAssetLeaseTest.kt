package top.tianyan.app.plugin.ui.web

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.runtime.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WebPluginAssetLeaseTest {
    @Test fun lazyLoadingOccursOnlyWhileOwnedAndCannotStartAfterClose() = runTest {
        var loads = 0
        val lease = WebPluginAssetLease(emptyMap(), load = { loads++; mapOf("index.html" to byteArrayOf(1)) })
        assertEquals(0, loads)
        assertArrayEquals(byteArrayOf(1), lease.loadAssets().getValue("index.html"))
        assertEquals(1, loads)
        assertTrue(lease.close())
        assertTrue(runCatching { lease.loadAssets() }.isFailure)
        assertEquals(1, loads)
    }

    @Test fun sourceClosedDuringLazyLoadCannotPublishReturnedBytes() = runTest {
        var alive = true
        val lease = WebPluginAssetLease(emptyMap(), available = { alive }, load = {
            alive = false; mapOf("index.html" to byteArrayOf(1))
        })
        assertTrue(runCatching { lease.loadAssets() }.isFailure)
        assertFalse(lease.isAvailable)
        assertTrue(lease.close())
    }
    @Test fun sourceOwnsAnImmutableSnapshotAndCloseBlocksFurtherAdmission() = runTest {
        val original = byteArrayOf(1, 2, 3)
        var releases = 0
        val lease = WebPluginAssetLease(mapOf("index.html" to original), release = { releases++; true })
        original.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), lease.assets().getValue("index.html"))
        assertTrue(lease.isAvailable)
        assertTrue(lease.close())
        assertFalse(lease.isAvailable)
        assertTrue(runCatching { lease.assets() }.isFailure)
        assertTrue(lease.close())
        assertEquals(1, releases)
    }

    @Test fun failedReleaseRemainsClosedAndCanBeRetried() = runTest {
        var releases = 0
        val lease = WebPluginAssetLease(emptyMap(), release = { ++releases > 1 })
        assertFalse(lease.close())
        assertFalse(lease.isAvailable)
        assertTrue(lease.close())
        assertTrue(lease.close())
        assertEquals(2, releases)
    }

    @Test fun thrownCleanupAndLivenessErrorsDoNotReopenOrCrashTheDocument() = runTest {
        val lease = WebPluginAssetLease(emptyMap(), available = { error("service failure") }, release = { error("stop failure") })
        assertFalse(lease.isAvailable)
        assertFalse(lease.close())
        assertFalse(lease.isAvailable)
    }

    @Test fun aFixedSnapshotLeaseOffersNoDynamicPathAndAClosedLeaseOffersNothing() = runTest {
        val fixed = WebPluginAssetLease(mapOf("index.html" to byteArrayOf(1)))
        assertNull(fixed.requestPath("api/status"))
        assertFalse(fixed.streamPath("events") { true })
        val live = WebPluginAssetLease(emptyMap(),
            request = { path -> if (path == "api/status") "ok" else null },
            stream = { path, emit -> emit("chunk") && path == "events" })
        assertEquals("ok", live.requestPath("api/status"))
        assertNull(live.requestPath("api/other"))
        assertTrue(live.streamPath("events") { true })
        assertFalse(live.streamPath("events") { false })
        assertTrue(live.close())
        assertNull(live.requestPath("api/status"))
        assertFalse(live.streamPath("events") { true })
    }

    @Test fun aDriftedLiveLeaseAnswersNoDynamicPath() = runTest {
        var alive = true
        val lease = WebPluginAssetLease(emptyMap(), available = { alive },
            request = { "ok" }, stream = { _, emit -> emit("chunk") })
        assertEquals("ok", lease.requestPath("api/status"))
        alive = false
        assertNull(lease.requestPath("api/status"))
        assertFalse(lease.streamPath("events") { true })
    }

    @Test fun aStreamLeaseRethrowsCancellationInsteadOfSwallowingIt() = runTest {
        val lease = WebPluginAssetLease(emptyMap(), stream = { _, _ -> throw kotlinx.coroutines.CancellationException("revoked") })
        assertTrue(runCatching { lease.streamPath("events") { true } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }

    @Test fun deadSourceCannotApprovePendingRequestsOrAdmitNewCalls() = runTest {
        var alive = true
        val lease = WebPluginAssetLease(emptyMap(), available = { alive })
        val identity = PluginCapabilityIdentity("example.service", "1", 1, "https://service.plugin.tianyan.invalid", "android:local")
        val capability = PluginCapability("host.app-info", "application")
        val session = PluginCapabilitySession(identity, mapOf(capability to { error("dead source executed") }), isAvailable = { lease.isAvailable })
        val first = async { session.call(PluginCapabilityCall("first", capability), identity.origin, true) }
        runCurrent()
        val token = session.prompt.value!!.token
        alive = false
        assertFalse(session.decide(token, PluginGrantChoice.ONCE))
        assertFalse(session.call(PluginCapabilityCall("next", capability), identity.origin, true).success)
        session.close()
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(lease.close())
    }
}
