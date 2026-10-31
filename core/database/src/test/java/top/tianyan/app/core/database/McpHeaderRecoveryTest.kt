package top.tianyan.app.core.database

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class McpHeaderRecoveryTest {
    private fun row(ciphertext: String = "broken") = McpServerEntity(
        id = "remote", name = "Remote", description = "", transportType = "SSE",
        command = "", argsCiphertext = "", envCiphertext = "", serverUrl = "https://example.test/mcp",
        headersCiphertext = ciphertext, isEnabled = true, isBuiltin = false,
    )

    @Test fun `unreadable credentials disable only affected configuration`() {
        val configs = listOf(row(), row("").copy(id = "healthy")).map { it.toModel(Json) { null } }
        assertFalse(configs[0].isEnabled)
        assertNotNull(configs[0].headersReadError)
        assertTrue(configs[0].headers.isEmpty())
        assertTrue(configs[1].isEnabled)
        assertNull(configs[1].headersReadError)
    }

    @Test fun `malformed decrypted json produces safe recoverable error`() {
        val config = row().toModel(Json) { "secret-invalid-json" }
        assertFalse(config.isEnabled)
        assertFalse(config.headersReadError!!.contains("secret-invalid-json"))
    }

    @Test fun `valid credentials recover on subsequent read`() {
        val config = row().toModel(Json) { """{"Authorization":"Bearer test"}""" }
        assertTrue(config.isEnabled)
        assertNull(config.headersReadError)
        assertEquals("Bearer test", config.headers["Authorization"])
    }

    @Test fun `invalid header content also blocks connection`() {
        val config = row().toModel(Json) { """{"Host":"example.test"}""" }
        assertFalse(config.isEnabled)
        assertNotNull(config.headersReadError)
    }
}
