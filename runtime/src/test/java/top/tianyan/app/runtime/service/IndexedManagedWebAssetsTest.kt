package top.tianyan.app.runtime.service

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class IndexedManagedWebAssetsTest {
    private val html = "<!doctype html><p>工具服务</p>".toByteArray()
    private val script = "console.log('indexed')".toByteArray()

    private fun source(server: MockWebServer, available: () -> Boolean = { true }, authorization: String? = null) =
        IndexedManagedWebAssets(server.port, "/instance/", authorization, available)

    @Test fun snapshotPinsTheEntryAndLaterPathsResolveAgainstTheirFirstBytes() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(body(html))
            server.enqueue(body(script))
            server.enqueue(body(script))
            server.start()
            val assets = IndexedManagedWebAssets(server.port, "/instance/", null, { true })
            assertArrayEquals(html, assets.snapshot().getValue("index.html"))
            assertArrayEquals(script, assets.resolve("plugin.js"))
            assertArrayEquals(script, assets.resolve("plugin.js"))
            assertEquals(3, server.requestCount)
            assertTrue(assets.isAvailable)
            assertTrue(assets.close())
        }
    }

    @Test fun aServedPathThatChangesAfterPinningClosesTheWholeSource() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(body(html))
            server.enqueue(body(script))
            server.enqueue(body("replacement".toByteArray()))
            server.start()
            val assets = source(server)
            assertArrayEquals(html, assets.snapshot().getValue("index.html"))
            assertArrayEquals(script, assets.resolve("plugin.js"))
            assertNull(assets.resolve("plugin.js"))
            assertFalse(assets.isAvailable)
            assertNull(assets.resolve("index.html"))
        }
    }

    @Test fun invalidOrTraversalPathsAreNeverRequested() {
        MockWebServer().use { server ->
            server.start()
            val assets = source(server)
            listOf("../secret", "/index.html", "theme.css", "a?query", "a%2fb").forEach { assertNull(assets.resolve(it)) }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun combinedByteBudgetClosesTheSourceBeforeAnotherPathIsServed() {
        MockWebServer().use { server ->
            server.enqueue(body(ByteArray(2 * 1024 * 1024) { 65 }))
            server.enqueue(body(byteArrayOf(1)))
            server.start()
            val assets = source(server)
            assertNotNull(assets.resolve("index.html"))
            assertNull(assets.resolve("plugin.js"))
            assertFalse(assets.isAvailable)
        }
    }

    @Test fun unavailableOrClosedSourceAnswersNothing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(body(html))
            server.start()
            val offline = source(server, available = { false })
            assertTrue(runCatching { offline.snapshot() }.isFailure)
            assertNull(offline.resolve("index.html"))
            assertEquals(0, server.requestCount)
            val assets = source(server)
            assertNotNull(assets.snapshot())
            assertTrue(assets.close())
            assertNull(assets.resolve("index.html"))
            assertFalse(assets.isAvailable)
        }
    }

    @Test fun authorizationIsSentWhenNativelyConfigured() {
        MockWebServer().use { server ->
            server.enqueue(body(html))
            server.start()
            val assets = source(server, authorization = "Bearer native-instance-key")
            runBlocking { assertNotNull(assets.resolve("index.html")) }
            val request = server.takeRequest()
            assertEquals("/instance/index.html", request.path)
            assertEquals("Bearer native-instance-key", request.getHeader("Authorization"))
        }
    }

    @Test fun rejectsInvalidNativeTargetsAndEntryPaths() {
        assertThrows(IllegalArgumentException::class.java) { IndexedManagedWebAssets(0, "/instance/", null, { true }) }
        listOf("/instance", "instance/", "/../", "/http://evil/", "/instance/?q/").forEach { base ->
            assertThrows(IllegalArgumentException::class.java) { IndexedManagedWebAssets(1234, base, null, { true }) }
        }
        listOf("../secret", "/index.html", "theme.css").forEach { entry ->
            assertThrows(IllegalArgumentException::class.java) {
                IndexedManagedWebAssets(1234, "/instance/", null, { true }, entry)
            }
        }
    }

    private fun body(bytes: ByteArray) = MockResponse().setBody(okio.Buffer().write(bytes))
}
