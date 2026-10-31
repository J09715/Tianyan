package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HarnessToolArgumentsTest {
    @Test
    fun `blank arguments remain a valid empty object`() {
        assertEquals("{}", HarnessToolRoundRunner.parseArguments(Json, " \n").toString())
    }

    @Test
    fun `complete command is preserved verbatim`() {
        val args = HarnessToolRoundRunner.parseArguments(Json, """{"command":"echo hello","cwd":"/workspace/project"}""")
        assertEquals("echo hello", args["command"]?.jsonPrimitive?.content)
        assertEquals("/workspace/project", args["cwd"]?.jsonPrimitive?.content)
    }

    @Test
    fun `truncated command must not be guessed and executed`() {
        listOf("""{"command":"echo incomplete""", """{"command":"echo hello"""", "[]", "null").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                HarnessToolRoundRunner.parseArguments(Json, raw)
            }
        }
    }
}
