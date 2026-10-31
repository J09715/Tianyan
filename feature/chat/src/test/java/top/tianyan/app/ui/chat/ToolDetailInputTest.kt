package top.tianyan.app.ui.chat

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

class ToolDetailInputTest {
    @Test fun commandDetailPreservesQuotesWhitespaceAndLongInputForCopy() {
        val command = "printf '%s\\n' \"quoted value\"\n" + "# context\n".repeat(10_000)
        val call = ToolCall("c", 1, HarnessTool.BASE, buildJsonObject { put("command", command) })
        assertEquals(command, toolDetailInput(call))
    }

    @Test fun nonCommandArgumentsRemainValidAndCompleteJson() {
        val args = buildJsonObject { put("path", "/workspace/a.kt"); put("content", "first\nsecond") }
        assertEquals(args, Json.parseToJsonElement(toolDetailInput(ToolCall("c", 1, HarnessTool.WRITE, args))))
    }
}
