package top.tianyan.app.plugin.ui.web

import kotlinx.coroutines.async
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.runtime.PluginCapabilityResult

class WebPluginMessagesTest {
    @Test fun serviceWritesKeepOpaqueJsonInsideTheBoundedTextField() {
        val call = WebPluginMessages.decode("""{"id":"write","action":"host.service-write","target":"service","text":"api/auth/token\n{\"token\":\"secret\"}"}""")
        assertEquals("api/auth/token\n{\"token\":\"secret\"}", call.text)
        assertTrue(runCatching { WebPluginMessages.decode("""{"id":"write","action":"host.service-write","target":"service","text":"../auth\n{}"}""") }.isFailure)
        assertTrue(runCatching { WebPluginMessages.decode("""{"id":"write","action":"host.service-write","target":"service","text":"api/auth\n{}","method":"POST"}""") }.isFailure)
    }
    @Test fun acceptsOnlyTheTypedClipboardTextEnvelope() {
        val call = WebPluginMessages.decode("""{"id":"copy","action":"host.clipboard-write","target":"clipboard","text":"天衍\n{[example]}"}""")
        assertEquals("天衍\n{[example]}", call.text)
        assertEquals("clipboard", call.capability.target)
    }

    @Test fun clipboardRejectsNullMissingOversizedAndDuplicateTextOrExtraParameters() {
        val prefix = """{"id":"copy","action":"host.clipboard-write","target":"clipboard"""
        listOf("$prefix}", "$prefix,\"text\":null}", "$prefix,\"text\":\"\"}",
            "$prefix,\"text\":\"a\",\"text\":\"b\"}", "$prefix,\"text\":\"a\",\"approved\":true}",
            "$prefix,\"text\":\"${"x".repeat(1025)}\"}", "$prefix,\"text\":\"a\\u0000b\"}",
        ).forEach { assertTrue(it, runCatching { WebPluginMessages.decode(it) }.isFailure) }
    }

    @Test fun existingReadOperationsCannotSmuggleClipboardInput() {
        assertTrue(runCatching {
            WebPluginMessages.decode("""{"id":"a","action":"host.app-info","target":"application","text":"payload"}""")
        }.isFailure)
    }
    @Test fun decodesOnlyStructuredActionAndTarget() {
        val call = WebPluginMessages.decode("""{"id":"a-1","action":"host.app-info","target":"application"}""")
        assertEquals("a-1", call.id)
        assertEquals("host.app-info", call.capability.action)
    }
    @Test fun rejectsForgedAuthorizationIdentityNestedAndOversizedMessages() {
        listOf(
            """{"id":"a","action":"host.app-info","target":"application","approved":true}""",
            """{"id":"a","action":"host.app-info","target":"application","pluginId":"tianyan.browser"}""",
            """{"id":"a","action":"host.app-info","target":"application","environment":"ssh"}""",
            """{"id":1,"action":"host.app-info","target":"application"}""",
            """{"id":"a","action":"shell","target":{"command":"id"}}""",
            """{"id":"a","action":"read","target":"first","target":"second"}""",
            """{"id":"a","action":"shell"}""", "[]", "{".repeat(4096),
        ).forEach { input ->
            assertTrue(input, runCatching { WebPluginMessages.decode(input) }.isFailure)
        }
    }
    @Test fun serviceRequestsCarryOnlyARelativeSubPathInThePayload() {
        val call = WebPluginMessages.decode(
            """{"id":"s1","action":"host.service-request","target":"service","text":"api/status.json"}""",
        )
        assertEquals("api/status.json", call.text)
        assertEquals("service", call.capability.target)
        val stream = WebPluginMessages.decode(
            """{"id":"s2","action":"host.service-stream","target":"service","text":"events.log"}""",
        )
        assertEquals("events.log", stream.text)
        listOf(
            """{"id":"s","action":"host.service-request","target":"service"}""",
            """{"id":"s","action":"host.service-request","target":"service","text":"/etc/passwd"}""",
            """{"id":"s","action":"host.service-request","target":"service","text":"../up"}""",
            """{"id":"s","action":"host.service-request","target":"service","text":"a/./b"}""",
            """{"id":"s","action":"host.service-stream","target":"service","text":"http://127.0.0.1:80/x"}""",
            """{"id":"s","action":"host.service-stream","target":"service","text":"a?b=1"}""",
        ).forEach { input -> assertTrue(input, runCatching { WebPluginMessages.decode(input) }.isFailure) }
    }

    @Test fun aChunkEnvelopeIsIndistinguishableFromADataReplyExceptForItsFlag() {
        val encoded = WebPluginMessages.encodeChunk("s1", "chunk")
        assertEquals("""{"id":"s1","success":true,"text":"chunk","more":true}""", encoded)
    }

    @Test fun outputEscapesPluginTextWithoutJavaScriptEvaluation() {
        val text = "</script>\"\\\n"
        val encoded = WebPluginMessages.encode(PluginCapabilityResult("id", true, text))
        assertEquals(text, kotlinx.serialization.json.Json.parseToJsonElement(encoded)
            .let { it as kotlinx.serialization.json.JsonObject }.getValue("text")
            .let { it as kotlinx.serialization.json.JsonPrimitive }.content)
    }

    @Test fun punctuationInScalarTargetsDoesNotBecomeNestedData() {
        val call = WebPluginMessages.decode("""{"id":"a","action":"read","target":"project:{[example]}"}""")
        assertEquals("project:{[example]}", call.capability.target)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun messageToNativePermissionToStructuredReplyExecutesExactlyOnce() = kotlinx.coroutines.test.runTest {
        val call = WebPluginMessages.decode("""{"id":"a-1","action":"host.app-info","target":"application"}""")
        val identity = top.tianyan.app.plugin.runtime.PluginCapabilityIdentity(
            "example.web", "1.0.0", 1, "https://example.plugin.tianyan.invalid", "android:local",
        )
        var executions = 0
        val session = top.tianyan.app.plugin.runtime.PluginCapabilitySession(identity, mapOf(call.capability to { executions++; "app-info" }))
        val result = async { session.call(call, identity.origin, true) }
        testScheduler.runCurrent()
        assertEquals(0, executions)
        val prompt = requireNotNull(session.prompt.value)
        session.decide(prompt.token, top.tianyan.app.plugin.runtime.PluginGrantChoice.ONCE)
        val reply = WebPluginMessages.encode(result.await())
        assertEquals("""{"id":"a-1","success":true,"text":"app-info"}""", reply)
        assertEquals(1, executions)
        assertFalse(session.call(call, identity.origin, true).success)
        assertEquals(1, executions)
        session.close()
    }
}
