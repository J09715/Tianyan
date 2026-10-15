package top.tianyan.app.harness.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun assistantMessageChunkIsParsed() {
        val params = json.parseToJsonElement(
            """
            {"sessionId":"s-1","update":{"sessionUpdate":"agent_message_chunk",
             "content":{"type":"text","text":"hello"}}}
            """.trimIndent(),
        )
        val update = AcpSessionUpdate.from(params)
        assertNotNull(update)
        assertEquals(AcpSessionUpdate.KIND_AGENT_MESSAGE_CHUNK, update!!.kind)
        assertEquals("hello", update.text)
        assertTrue(update.isAssistantText)
        assertFalse(update.isThought)
    }

    @Test
    fun contentArrayIsFlattenedForMessageUpdates() {
        val params = json.parseToJsonElement(
            """
            {"update":{"sessionUpdate":"agent_message",
             "content":[{"type":"text","text":"a"},{"type":"text","text":"b"}]}}
            """.trimIndent(),
        )
        assertEquals("ab", AcpSessionUpdate.from(params)?.text)
    }

    @Test
    fun thoughtChunkIsClassifiedSeparately() {
        val params = json.parseToJsonElement(
            """{"update":{"sessionUpdate":"agent_thought_chunk","content":{"type":"text","text":"think"}}}""",
        )
        val update = AcpSessionUpdate.from(params)!!
        assertTrue(update.isThought)
        assertFalse(update.isAssistantText)
    }

    @Test
    fun toolCallKindsAreDetected() {
        val params = json.parseToJsonElement(
            """{"update":{"sessionUpdate":"tool_call_update","title":"Edit file"}}""",
        )
        val update = AcpSessionUpdate.from(params)!!
        assertTrue(update.isToolCall)
        assertEquals("", update.text)
    }

    @Test
    fun unknownUpdateShapeIsIgnoredRatherThanCrashing() {
        assertNull(AcpSessionUpdate.from(json.parseToJsonElement("""{"update":{}}""")))
        assertNull(AcpSessionUpdate.from(json.parseToJsonElement("""{}""")))
        assertNull(AcpSessionUpdate.from(null))
    }

    @Test
    fun permissionRequestIsParsedFromInboundRequest() {
        val params = json.parseToJsonElement(
            """
            {"sessionId":"s-1","toolCall":{"title":"Run tests","toolCallId":"t-1"},
             "options":[{"optionId":"allow","name":"Allow","kind":"allow_once"},
                        {"optionId":"deny","name":"Deny","kind":"reject_once"}]}
            """.trimIndent(),
        )
        val request = AcpPermissionRequest.from(
            AcpInboundRequest(id = JsonPrimitive("req-1"), method = AcpProtocol.METHOD_REQUEST_PERMISSION, params = params),
        )
        assertNotNull(request)
        assertEquals("s-1", request!!.sessionId)
        assertEquals(2, request.options.size)
        assertTrue(request.options.first { it.optionId == "allow" }.isAllow)
        assertFalse(request.options.first { it.optionId == "deny" }.isAllow)
    }

    @Test
    fun permissionRequestWithoutSessionIdIsDropped() {
        val params = json.parseToJsonElement("""{"options":[]}""")
        assertNull(
            AcpPermissionRequest.from(
                AcpInboundRequest(id = JsonPrimitive("req-1"), method = "session/request_permission", params = params),
            ),
        )
    }

    @Test
    fun allowResponseSelectsAnAllowOption() {
        val request = AcpPermissionRequest(
            requestId = JsonPrimitive("req-1"),
            sessionId = "s-1",
            toolCall = null,
            options = listOf(
                AcpPermissionOption("deny", "Deny", "reject_once"),
                AcpPermissionOption("allow", "Allow", "allow_once"),
            ),
        )
        val payload = acpPermissionResponse(request.requestId, request.options.first { it.isAllow })
        val outcome = payload["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("selected", (outcome["outcome"] as JsonPrimitive).content)
        assertEquals("allow", (outcome["optionId"] as JsonPrimitive).content)
    }

    @Test
    fun rejectionWithoutAMatchingOptionReportsCancelled() {
        val payload = acpPermissionResponse(JsonPrimitive("req-1"), null)
        val outcome = payload["result"]!!.jsonObject["outcome"]!!.jsonObject
        assertEquals("cancelled", (outcome["outcome"] as JsonPrimitive).content)
        assertNull(outcome["optionId"])
    }

    @Test
    fun cancelNotificationCarriesTheEngineSessionId() {
        val payload = acpCancelNotification("engine-session")
        assertEquals(AcpProtocol.METHOD_SESSION_CANCEL, (payload["method"] as JsonPrimitive).content)
        assertEquals("engine-session", (payload["params"]!!.jsonObject["sessionId"] as JsonPrimitive).content)
    }

    @Test
    fun extensionMethodsAreDetectedByReservedPrefix() {
        assertTrue(AcpProtocol.isExtension("_custom/notify"))
        assertFalse(AcpProtocol.isExtension("session/prompt"))
    }

    @Test
    fun initializeRequestAlwaysCarriesTheNegotiatedProtocolVersion() {
        // 宿主 Json 关着 encodeDefaults；协议字段若依赖默认值策略会被静默丢掉。
        val strictJson = Json { ignoreUnknownKeys = true }
        val obj = AcpInitializeParams.toJsonElement(strictJson).jsonObject

        assertEquals(AcpProtocol.PROTOCOL_VERSION, (obj["protocolVersion"] as JsonPrimitive).content.toInt())
        assertTrue(obj.containsKey("clientCapabilities"))
        assertEquals("Tianyan", (obj["clientInfo"]!!.jsonObject["name"] as JsonPrimitive).content)
        assertEquals("天衍", (obj["clientInfo"]!!.jsonObject["title"] as JsonPrimitive).content)
    }

    @Test
    fun newSessionResultRequiresASessionId() {
        val decoded = json.decodeFromJsonElement(
            AcpNewSessionResult.serializer(),
            json.parseToJsonElement("""{"sessionId":"abc"}"""),
        )
        assertEquals("abc", decoded.sessionId)
    }

    @Test
    fun initializeResultToleratesUnknownAgentFields() {
        val decoded = json.decodeFromJsonElement(
            AcpInitializeResult.serializer(),
            json.parseToJsonElement(
                """{"protocolVersion":1,"agentCapabilities":{"loadSession":true},
                    "agentInfo":{"name":"codex","title":"Codex","version":"1.0"},
                    "authMethods":[],"extraField":123}""",
            ),
        )
        assertEquals(1, decoded.protocolVersion)
        assertEquals("codex", decoded.agentInfo?.name)
    }
}
