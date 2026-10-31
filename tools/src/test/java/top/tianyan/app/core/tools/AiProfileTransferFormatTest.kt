package top.tianyan.app.core.tools

import org.junit.Assert.*
import org.junit.Test

class AiProfileTransferFormatTest {
    private val single = """{"name":"Example","model":"model","baseUrl":"https://example.com/v1"}"""
    @Test fun `legacy single array and bundle remain readable`() {
        for (raw in listOf(single, "[$single]", """{"schemaVersion":1,"profiles":[$single]}""")) {
            assertEquals("model", AiProfileTransferFormat.parse(raw).single().model)
        }
    }
    @Test fun `empty and future bundles cannot fall through to a bogus single profile`() {
        for (raw in listOf("{}", "[]", """{"profiles":[],"name":"decoy"}""",
            """{"schemaVersion":99,"profiles":[$single]}""",
            """{"schemaVersion":99,"name":"decoy","model":"model"}""")) {
            assertFails(raw)
        }
    }
    @Test fun `bundle version applies to profile semantics`() {
        assertEquals(2, AiProfileTransferFormat.parse("""{"schemaVersion":2,"profiles":[$single]}""").single().schemaVersion)
    }
    @Test fun `invalid row duplicates or field types invalidate the whole batch`() {
        for (raw in listOf("[$single,{}]", """[{"id":"same","model":"a"},{"id":" same ","model":"b"}]""",
            """{"model":"a","temperature":"secret-value"}""",
            """{"model":"a","temperature":3}""", """{"model":"a","topP":-1}""",
            """{"model":"a","compactionKeepRecentTokens":0}""", """{"model":"a","maxTokens":-1}""")) {
            assertFails(raw)
        }
    }
    @Test fun `local http endpoints are accepted and remote cleartext is rejected`() {
        for (url in listOf("http://127.0.0.1:8000/v1", "http://localhost:8000", "http://[::1]:8000")) {
            assertEquals(url, AiProfileTransferFormat.parse("""{"model":"local","baseUrl":"$url"}""").single().baseUrl)
        }
        for (url in listOf("http://remote.example.com", "file:///etc/passwd", "javascript:alert(1)", "not a url")) {
            assertFails("""{"model":"a","baseUrl":"$url"}""")
        }
    }
    @Test fun `credential omission cannot be contradicted by secret content`() {
        assertFails("""{"schemaVersion":2,"model":"a","credentialsIncluded":false,"apiKey":"hidden-secret"}""")
        assertFails("""{"schemaVersion":2,"model":"a","credentialsIncluded":false,"customHeaders":"Authorization: hidden-secret"}""")
    }
    @Test fun `malformed input errors do not expose source credentials`() {
        val result = runCatching { AiProfileTransferFormat.parse("""{"model":"a","apiKey":"hidden-secret","temperature":"private"}""") }
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("hidden-secret"))
        assertFalse(result.exceptionOrNull()!!.message.orEmpty().contains("private"))
    }
    @Test fun `file reader stops at the byte limit before unbounded allocation`() {
        val raw = "x".repeat(AiProfileTransferFormat.MAX_BYTES + 1)
        assertTrue(runCatching { AiProfileTransferFormat.read(raw.byteInputStream()) }.isFailure)
        assertFails(raw)
        assertEquals(single, AiProfileTransferFormat.read(single.byteInputStream()))
    }
    @Test fun `multibyte source uses bytes and profile count is bounded`() {
        assertFails("汉".repeat(AiProfileTransferFormat.MAX_BYTES / 2))
        assertFails(List(AiProfileTransferFormat.MAX_PROFILES + 1) { single }.joinToString(",", "[", "]"))
    }
    private fun assertFails(raw: String) {
        assertTrue("Expected invalid import", runCatching { AiProfileTransferFormat.parse(raw) }.isFailure)
    }
}
