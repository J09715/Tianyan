package top.tianyan.app.runtime.browser.cdp

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class CdpTargetMatcherTest {
    @Test fun `websocket target path retains devtools prefix and encoded query`() {
        assertEquals("/devtools/page/id%20one?token=a%2Fb", WsHandshake.targetPath(
            "ws://127.0.0.1/devtools/page/id%20one?token=a%2Fb",
        ))
    }

    @Test fun `same URL targets are selected using a live marker probe`() = runBlocking {
        val server = MockWebServer()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""[
                {"id":"one","type":"page","url":"about:blank","webSocketDebuggerUrl":"ws://127.0.0.1/devtools/page/one"},
                {"id":"two","type":"page","url":"about:blank","webSocketDebuggerUrl":"ws://127.0.0.1/devtools/page/two"}
            ]"""))
            for (tab in listOf("t:other", "t:wanted")) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val id = Json.parseToJsonElement(text).jsonObject["id"]!!.jsonPrimitive.content
                        webSocket.send("""{"id":$id,"result":{"result":{"type":"string","value":"$tab"}}}""")
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                }))
            }
            val matcher = CdpTargetMatcher(TcpCdpTransport(server.hostName, server.port), scope)
            val match = matcher.matchPageTarget("t:wanted", "about:blank")
            assertTrue(match is CdpTargetMatcher.TargetMatch.Matched)
            assertEquals("two", (match as CdpTargetMatcher.TargetMatch.Matched).target.id)
            assertEquals("/json", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals("/devtools/page/one", server.takeRequest(1, TimeUnit.SECONDS)?.path)
            assertEquals("/devtools/page/two", server.takeRequest(1, TimeUnit.SECONDS)?.path)
        } finally {
            scope.cancel()
            server.shutdown()
        }
    }
}
