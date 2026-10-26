package top.tianyan.app.runtime.service

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ToolServiceWebSourceTest {
    private val html = "<!doctype html><p>已安装工具服务</p>".toByteArray()

    private fun descriptor(port: Int, basePath: String = "/") =
        ToolServiceDescriptor(port = port, basePath = basePath, ownerPluginId = "sample-tool", distroId = "ubuntu")

    @Test fun rejectsARemoteBackendBeforeAnySourceIsHandedOut() {
        assertThrows(IllegalStateException::class.java) {
            ToolServiceWebSource(descriptor = descriptor(8000), isLocal = { false })
        }
    }

    @Test fun descriptorRejectsForeignPortsPathsAndOwners() {
        assertThrows(IllegalArgumentException::class.java) { descriptor(0) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(70000) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, "/ui") }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, "/ui\u0000/") }
        assertThrows(IllegalArgumentException::class.java) {
            ToolServiceDescriptor(8000, "/", ownerPluginId = "", distroId = "ubuntu")
        }
    }

    @Test fun probesTheEntryThenServesPinnedPathsAndClosesWithoutTouchingTheService() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.start()
            val source = ToolServiceWebSource(descriptor = descriptor(server.port, "/ui/"), isLocal = { true })
            try {
                assertArrayEquals(html, source.snapshot().getValue("index.html"))
                assertEquals("/ui/index.html", server.takeRequest().path)
                assertArrayEquals(html, source.resolve("index.html"))
                assertTrue(source.isAvailable)
            } finally { assertTrue(source.close()) }
            assertFalse(source.isAvailable)
            assertNull(source.resolve("index.html"))
        }
    }

    @Test fun aDeadServiceFailsTheProbeInsteadOfPublishingADocument() {
        MockWebServer().use { server ->
            server.start()
            val port = server.port
            server.shutdown()
            val source = ToolServiceWebSource(descriptor = descriptor(port), isLocal = { true }, startupTimeoutMillis = 250)
            assertTrue(runCatching { runBlocking { source.snapshot() } }.isFailure)
            assertNull(source.resolve("index.html"))
        }
    }
}
