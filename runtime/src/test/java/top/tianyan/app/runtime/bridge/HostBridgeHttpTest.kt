package top.tianyan.app.runtime.bridge

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class HostBridgeHttpTest {
    private fun parse(request: String) = HostBridgeHttp.parseHttpRequest(ByteArrayInputStream(request.toByteArray()))

    @Test fun preservesLegacyRequestsAndReadsMultibyteBodyByBytes() {
        assertEquals("GET", parse("GET /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n")!!.method)
        val body = "{\"command\":\"echo 天衍\"}"
        val request = parse("POST /api/shell HTTP/1.1\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")!!
        assertEquals(body, request.body)
    }

    @Test fun rejectsTruncatedDuplicateNegativeAndAmbiguousBodies() {
        listOf(
            "Content-Length: 2\r\n\r\na", "Content-Length: -1\r\n\r\n",
            "Content-Length: 1\r\nContent-Length: 1\r\n\r\na",
            "Transfer-Encoding: chunked\r\n\r\n", "Content-Length: nope\r\n\r\n",
            "Content-Length: 9999999999\r\n\r\n",
        ).forEach { assertNull(parse("POST /api/plugin/call HTTP/1.1\r\n$it")) }
    }

    @Test fun rejectsHeadersAndBodiesBeforeUnboundedAllocation() {
        assertNull(parse("POST /api/plugin/call HTTP/1.1\r\nContent-Length: 4097\r\n\r\n"))
        assertNull(parse("POST /api/shell HTTP/1.1\r\nContent-Length: 4194305\r\n\r\n"))
        assertNull(parse("GET /api/health HTTP/1.1\r\nX-Large: ${"x".repeat(8192)}\r\n\r\n"))
        assertNull(parse("GET /api/health HTTP/1.1\r\n" + "X-Header: a\r\n".repeat(3000)))
        assertNull(parse("GET /api/health HTTP/1.1\n\n"))
    }
}
