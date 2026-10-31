package top.tianyan.app.harness.effects

import org.junit.Assert.*
import org.junit.Test

class TerminalOutputCompressorTest {
    @Test fun `colors hyperlinks and progress refreshes disappear but unicode and CRLF survive`() {
        val raw = "\u001b[32m开始\u001b[0m\r\n10%\r20%\r完成\n" +
            "\u001b]8;;https://example.test\u001b\\报告\u001b]8;;\u0007\nabc\bX"
        assertEquals("开始\n完成\n报告\nabX", TerminalOutputCompressor.normalize(raw))
    }

    @Test fun `short plain output is untouched`() {
        val output = "build started\nerror: missing symbol\n"
        assertEquals(output, TerminalOutputCompressor.compress(output))
    }

    @Test fun `line limit retains setup and final failure even below old character limit`() {
        val output = (0..199).joinToString("\n") { "line-$it" }
        val folded = TerminalOutputCompressor.compress(output)
        assertTrue(folded.startsWith("line-0\n"))
        assertTrue(folded.endsWith("line-199"))
        assertTrue(folded.contains("省略中间 100 行"))
        assertFalse(folded.contains("line-100\n"))
    }

    @Test fun `character limit bounds sample size and retains both ends`() {
        val output = (0..99).joinToString("\n") { "line-$it:" + "x".repeat(1000) }
        val folded = TerminalOutputCompressor.compress(output)
        assertTrue(folded.length < 12_200)
        assertTrue(folded.startsWith("line-0:"))
        assertTrue(folded.contains("line-99:"))
    }

    @Test fun `long single line keeps its tail and truncated controls terminate safely`() {
        assertTrue(TerminalOutputCompressor.compress("x".repeat(100_000) + "failure").endsWith("failure"))
        assertEquals("safe", TerminalOutputCompressor.normalize("safe\u001b[12"))
        assertEquals("safe", TerminalOutputCompressor.normalize("safe\u001b]secret"))
        assertEquals("safe", TerminalOutputCompressor.normalize("safe\u001b"))
        assertEquals("ok", TerminalOutputCompressor.normalize("\u001b(Bok\u009b0m"))
    }
}
