package top.tianyan.app.runtime.service

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ManagedWebAssetsTest {
    private val html = "<!doctype html><p>天衍</p>".toByteArray()
    private val hashes get() = mapOf("index.html" to ManagedWebAssets.digest(html))
    private fun source(server: MockWebServer, hashes: Map<String, String> = this.hashes,
        available: () -> Boolean = { true }) = ManagedWebAssets(server.port, "/instance/", hashes,
        "Bearer native-instance-key", available, { true })

    @Test fun realOwnedServiceServesImmutableSnapshotAndStopsOnlyItsOwnInstance() = runBlocking {
        val original = html.copyOf()
        val first = LoopbackWebAssetService.start(mapOf("index.html" to original))
        val other = LoopbackWebAssetService.start(mapOf("index.html" to html))
        try {
            original.fill(0)
            assertArrayEquals(html, first.source.snapshot().getValue("index.html"))
            assertTrue(first.source.isAvailable())
            assertTrue(first.close())
            assertFalse(first.source.isAvailable())
            assertTrue(runCatching { first.source.snapshot() }.isFailure)
            assertArrayEquals(html, other.source.snapshot().getValue("index.html"))
            assertTrue(first.close())
        } finally { first.close(); other.close() }
    }

    @Test fun nativeTargetValidationRejectsUrlsTraversalUnknownEntryAndReservedTheme() {
        listOf("/../", "/http://evil/", "/instance/?query/", "/a//").forEach { prefix ->
            assertThrows(IllegalArgumentException::class.java) { ManagedWebAssets(1234, prefix, hashes, "secret", { true }, { true }) }
        }
        listOf("../secret", "a%2fb", "/index.html", "theme.css", "a?query", "a/".repeat(17) + "file").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { ManagedWebAssets(1234, "/instance/", hashes + (path to "a".repeat(64)), "secret", { true }, { true }) }
        }
        assertThrows(IllegalArgumentException::class.java) { ManagedWebAssets(0, "/instance/", hashes, "secret", { true }, { true }) }
        assertThrows(IllegalArgumentException::class.java) { ManagedWebAssets(1234, "/instance/", emptyMap(), "secret", { true }, { true }) }
    }

    @Test fun redirectsNeverReachAnotherServerOrForwardCredentials() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            other.start()
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/secret")))
            server.start()
            assertTrue(runCatching { source(server).snapshot() }.isFailure)
            assertEquals("Bearer native-instance-key", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertNull(other.takeRequest(100, TimeUnit.MILLISECONDS))
        } }
    }

    @Test fun mismatchedContentAndCompressedResponsesAreRejected() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("replacement"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)).addHeader("Content-Encoding", "gzip"))
            server.start()
            repeat(2) { assertTrue(runCatching { source(server).snapshot() }.isFailure) }
        }
    }

    @Test fun rejectsOversizedDeclaredAndStreamingPayloads() = runBlocking {
        MockWebServer().use { server ->
            val oversized = "x".repeat(2 * 1024 * 1024 + 1)
            server.enqueue(MockResponse().setBody(oversized))
            server.enqueue(MockResponse().setChunkedBody(oversized, 8192))
            server.start()
            repeat(2) { assertTrue(runCatching { source(server).snapshot() }.isFailure) }
        }
    }

    @Test fun enforcesCombinedQuotaAcrossFiles() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArray(1024 * 1024 + 1) { 65 }
            val expected = mapOf("index.html" to ManagedWebAssets.digest(bytes), "plugin.js" to ManagedWebAssets.digest(bytes))
            repeat(2) { server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes))) }
            server.start()
            assertTrue(runCatching { source(server, expected).snapshot() }.isFailure)
        }
    }

    @Test fun cookiesAreNotCarriedBetweenRequests() = runBlocking {
        MockWebServer().use { server ->
            val expected = hashes + ("plugin.js" to ManagedWebAssets.digest(html))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)).addHeader("Set-Cookie", "secret=from-service"))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.start()
            assertEquals(2, source(server, expected).snapshot().size)
            assertEquals("/instance/index.html", server.takeRequest().path)
            val second = server.takeRequest()
            assertEquals("/instance/plugin.js", second.path)
            assertNull(second.getHeader("Cookie"))
        }
    }

    @Test fun serviceDeathBeforeOrDuringAcquisitionPreventsPublishingSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.start()
            assertTrue(runCatching { source(server, available = { false }).snapshot() }.isFailure)
            assertEquals(0, server.requestCount)
            var checks = 0
            assertTrue(runCatching { source(server, available = { ++checks == 1 }).snapshot() }.isFailure)
        }
    }

    @Test fun cancellationInterruptsAnInFlightResponse() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("waiting-for-response").throttleBody(1, 100, TimeUnit.MILLISECONDS))
            server.start()
            val pending = async(Dispatchers.IO) { source(server).snapshot() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
        }
    }
}
