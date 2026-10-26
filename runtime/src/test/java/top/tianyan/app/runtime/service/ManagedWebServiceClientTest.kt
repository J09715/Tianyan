package top.tianyan.app.runtime.service

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ManagedWebServiceClientTest {
    @Test fun readsBoundedTextFromItsOwnBasePathWithItsNativeCredential() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            server.start()
            val client = ManagedWebServiceClient(server.port, "/api/", authorization = "Basic secret", available = { true })
            try {
                assertEquals("""{"ok":true}""", client.read("status"))
                val recorded = server.takeRequest()
                assertEquals("/api/status", recorded.path)
                assertEquals("Basic secret", recorded.getHeader("Authorization"))
                assertEquals("identity", recorded.getHeader("Accept-Encoding"))
            } finally { client.close() }
        }
    }

    @Test fun rejectsForeignPathsBeforeAnyRequestIsSent() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = ManagedWebServiceClient(server.port, "/", available = { true })
            try {
                for (path in listOf("", "/absolute", "../up", "a/../b", "a//b", "theme.css", "a b", "a?b", "a".repeat(301))) {
                    assertNull(path, client.read(path))
                    assertFalse(path, client.stream(path) { true })
                }
                assertEquals(0, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun deadOrClosedSourcesAnswerNothing() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            var alive = true
            val client = ManagedWebServiceClient(server.port, "/", available = { alive })
            alive = false
            assertNull(client.read("status"))
            assertFalse(client.stream("events") { true })
            alive = true
            client.close()
            assertNull(client.read("status"))
            assertFalse(client.stream("events") { true })
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun oversizedAndRejectedAnswersAreRedactedInsteadOfThrown() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(4096)))
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
            server.start()
            val client = ManagedWebServiceClient(server.port, "/", available = { true }, maxResponseChars = 1024)
            try {
                assertNull(client.read("big"))
                assertNull(client.read("broken"))
            } finally { client.close() }
        }
    }

    @Test fun streamsBoundedChunksUntilTheServiceEnds() = runBlocking {
        MockWebServer().use { server ->
            val body = "重要数据".repeat(40)
            server.enqueue(MockResponse().setBody(body))
            server.start()
            val client = ManagedWebServiceClient(server.port, "/", available = { true }, chunkChars = 64)
            try {
                val chunks = mutableListOf<String>()
                assertTrue(client.stream("events") { chunks += it; true })
                assertEquals(body, chunks.joinToString(""))
                assertTrue(chunks.size > 1)
                assertTrue(chunks.all { it.length <= 64 })
            } finally { client.close() }
        }
    }

    @Test fun aSpentBudgetOrAStoppedConsumerEndsTheStream() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("y".repeat(500)))
            server.enqueue(MockResponse().setBody("y".repeat(500)))
            server.start()
            val client = ManagedWebServiceClient(server.port, "/", available = { true }, maxStreamChars = 128, chunkChars = 64)
            try {
                val spent = mutableListOf<String>()
                assertFalse(client.stream("events") { spent += it; true })
                assertEquals(128, spent.sumOf { it.length })
                var accepted = 0
                assertFalse(client.stream("events") { accepted += it.length; accepted < 64 })
                assertEquals(64, accepted)
            } finally { client.close() }
        }
    }

    @Test fun budgetsOutsideTheDocumentedBoundsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ManagedWebServiceClient(8000, "/", available = { true }, maxResponseChars = 32_769)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagedWebServiceClient(8000, "/ui", available = { true })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ManagedWebServiceClient(8000, "/", available = { true }, maxStreamChars = 64, chunkChars = 128)
        }
    }
}
