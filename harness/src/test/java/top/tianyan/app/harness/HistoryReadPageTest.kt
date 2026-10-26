package top.tianyan.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.security.SecretRedactor

class HistoryReadPageTest {
    @Test fun `all pages reconstruct long arguments and related results without losing the tail`() {
        val args = buildJsonObject { put("path", "file"); put("content", "x".repeat(65_000) + "参数尾部") }
        val messages = listOf(
            ToolCall("call", 1, HarnessTool.WRITE, args),
            ToolResult("result", 2, "call", true, "y".repeat(65_000) + "唯一事实"),
        )
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = HistoryReadPage.render(messages, offset, HistoryReadPage.DEFAULT_LIMIT)
            assertTrue(page.length < HistoryReadPage.MAX_LIMIT)
            restored.append(page.substringAfter('\n'))
            val next = Regex("next_offset=(\\d+|null)").find(page)!!.groupValues[1]
            if (next == "null") {
                assertTrue(page.contains("truncated=false"))
                break
            }
            assertTrue(page.contains("truncated=true"))
            assertTrue(next.toInt() > offset)
            offset = next.toInt()
        } while (true)
        assertEquals("id=call\n工具调用 WRITE: $args\nreasoning:\n\n\nid=result\n工具结果：" +
            messages.filterIsInstance<ToolResult>().single().output, restored.toString())
        assertTrue(restored.endsWith("唯一事实"))
    }

    @Test fun `pages preserve surrogate pairs and allow exact end offset`() {
        val message = UserMessage("u", 1, "a😀b")
        val full = HistoryReadPage.render(listOf(message), 0, 100).substringAfter('\n')
        val pairOffset = full.indexOf("😀")
        val first = HistoryReadPage.render(listOf(message), 0, pairOffset + 1)
        assertEquals(full.take(pairOffset), first.substringAfter('\n'))
        assertTrue(first.contains("next_offset=$pairOffset"))
        assertEquals("😀b", HistoryReadPage.render(listOf(message), pairOffset, 3).substringAfter('\n'))
        assertTrue(HistoryReadPage.render(listOf(message), full.length, 10).contains("truncated=false next_offset=null"))
        assertTrue(runCatching { HistoryReadPage.render(listOf(message), pairOffset + 1, 2) }.isFailure)
        assertTrue(runCatching { HistoryReadPage.render(listOf(message), pairOffset, 1) }.isFailure)
        assertTrue(runCatching { HistoryReadPage.render(listOf(message), full.length + 1, 10) }.isFailure)
    }

    @Test fun `search preview is bounded even for huge tool arguments`() {
        val call = ToolCall("call", 1, HarnessTool.WRITE, buildJsonObject { put("content", "a".repeat(70_000)) })
        assertEquals(240, HistoryReadPage.preview(listOf(call), "call").length)
    }

    @Test fun `redaction precedes page and search preview boundaries`() {
        val secret = "sensitive-boundary-value"
        val message = UserMessage("u", 1, "x".repeat(230) + secret + "tail")
        val redact: (String) -> String = { it.replace(secret, "[redacted]") }
        val first = HistoryReadPage.render(listOf(message), 0, 245, redact)
        val next = Regex("next_offset=(\\d+)").find(first)!!.groupValues[1].toInt()
        val second = HistoryReadPage.render(listOf(message), next, 245, redact)
        val reconstructed = first.substringAfter('\n') + second.substringAfter('\n')
        assertEquals("id=u\n用户：" + "x".repeat(230) + "[redacted]tail", reconstructed)
        assertFalse(HistoryReadPage.preview(listOf(message), "u", redact).contains("sensitive"))
    }

    @Test fun `echoed labelled secrets are redacted across messages in one page pass`() {
        val secret = "s3cr3t-echo-123456"
        val call = ToolCall("call", 1, HarnessTool.WRITE,
            buildJsonObject { put("path", "a.txt"); put("password", secret) })
        val result = ToolResult("result", 2, "call", true, "stored with $secret done")
        val redact: (String) -> String = SecretRedactor()::redact
        val restored = StringBuilder()
        var offset = 0
        do {
            val page = HistoryReadPage.render(listOf(call, result), offset, 50, redact)
            restored.append(page.substringAfter('\n'))
            val next = Regex("next_offset=(\\d+|null)").find(page)!!.groupValues[1]
            if (next == "null") break
            offset = next.toInt()
        } while (true)
        assertTrue(restored.contains("[REDACTED]"))
        assertFalse(restored.toString().contains(secret))
    }

    @Test fun `search preview learns echoed labels from the related group`() {
        val secret = "s3cr3t-echo-123456"
        val call = ToolCall("call", 1, HarnessTool.WRITE,
            buildJsonObject { put("path", "a.txt"); put("password", secret) })
        val result = ToolResult("result", 2, "call", true, "stored with $secret done")
        val redact: (String) -> String = SecretRedactor()::redact
        val preview = HistoryReadPage.preview(listOf(call, result), "result", redact)
        assertTrue(preview.contains("[REDACTED]"))
        assertFalse(preview.contains(secret))
        assertTrue(HistoryReadPage.preview(listOf(call, result), "call", redact).startsWith("工具调用 WRITE"))
    }
}
