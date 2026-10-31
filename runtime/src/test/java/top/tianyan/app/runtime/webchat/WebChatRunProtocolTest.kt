package top.tianyan.app.runtime.webchat

import kotlinx.serialization.json.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class WebChatRunProtocolTest {
    @Test fun legacyRequestDefaultsToNextRun() {
        val input = WebChatRunProtocol.parse(buildJsonObject { put("userMessage", "hello") })
        assertEquals(WebChatInputMode.NEXT_RUN, input.mode)
        assertEquals("hello", input.text)
    }
    @Test fun explicitQueueModesUseStableWireNames() {
        for (mode in WebChatInputMode.entries) {
            val input = WebChatRunProtocol.parse(buildJsonObject { put("userMessage", "hello"); put("inputMode", mode.id) })
            assertEquals(mode, input.mode)
        }
    }
    @Test fun unknownModeIsRejected() {
        try {
            WebChatRunProtocol.parse(buildJsonObject { put("userMessage", "hello"); put("inputMode", "invalid") })
            fail("must reject")
        } catch (failure: IllegalArgumentException) { assertEquals("未知输入模式", failure.message) }
    }
    @Test fun imageOnlyRequestIsAcceptedButOtherAttachmentsAreNot() {
        val input = WebChatRunProtocol.parse(buildJsonObject { putJsonArray("attachments") {
            add(buildJsonObject { put("dataUrl", "data:image/png;base64,test") })
            add(buildJsonObject { put("dataUrl", "https://example.com/file") })
        } })
        assertEquals(listOf("data:image/png;base64,test"), input.images)
        try { WebChatRunProtocol.parse(buildJsonObject { put("userMessage", " ") }); fail("must reject") }
        catch (failure: IllegalArgumentException) { assertEquals("消息不能为空", failure.message) }
    }
    @Test fun acknowledgmentSeparatesDurableIdsFromTransportCorrelation() {
        val json = WebChatRunProtocol.receiptJson(WebChatInputReceipt("queued", "durable", "next_run", "item"))
        assertEquals("durable", json["durableTaskId"]?.jsonPrimitive?.content)
        assertFalse(json.containsKey("taskId"))
        assertEquals("item", json["queueItemId"]?.jsonPrimitive?.content)
        val steering = WebChatRunProtocol.receiptJson(WebChatInputReceipt("queued", null, "steer", "steer-item"))
        assertFalse(steering.containsKey("durableTaskId"))
    }
    @Test fun infrastructureFailureHasServerStatusAndDoesNotExposeRawDetails() {
        val failure = WebChatRunProtocol.failure(IOException("secret storage details"))
        assertEquals(500, failure.status)
        assertEquals("会话操作失败", failure.message)
        assertEquals(WebChatRunProtocol.Failure(400, "会话不存在"),
            WebChatRunProtocol.failure(IllegalArgumentException("会话不存在")))
    }
}
