package top.tianyan.app.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.runtime.terminal.AnsiTerminalBuffer

class AnsiTerminalBufferTest {
    @Test
    fun preservesSgrStyleAcrossChunkBoundaries() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("hello\u001B[3")
        val screen = buffer.append("1mred\u001B[0m!")

        val line = screen.first()
        assertEquals("hellored!", line.cells.joinToString("") { it.character })
        assertEquals(0xFFCC0000L, line.cells[5].foreground)
        assertEquals(null, line.cells.last().foreground)
    }

    @Test
    fun handlesCursorMovementAndEraseLine() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("hello")
        val screen = buffer.append("\u001B[1G\u001B[Kx")

        assertEquals("x", screen.first().cells.joinToString("") { it.character })
    }

    @Test
    fun scrollbackIsBounded() {
        // rows=3 会被实现的 MIN_ROWS=5 钳制（视口护栏），取最小合法视口 5 行；
        // maxRows=5 + 7 行输入 → 滚动两次，最早两行被滚回缓冲裁掉，总量仍有界。
        val buffer = AnsiTerminalBuffer(columns = 20, rows = 5, maxRows = 5)
        val screen = buffer.append("one\ntwo\nthree\nfour\nfive\nsix\nseven")

        assertTrue(screen.size <= 5)
        assertEquals("seven", screen.last().cells.joinToString("") { it.character })
    }

    @Test
    fun absoluteCursorUsesViewportNotScrollback() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 5, maxRows = 50)
        buffer.append("banner\nprompt> ")
        // Codex-style redraw: home + clear-down + multi-line paint inside the viewport.
        val screen = buffer.append("\u001B[H\u001B[Jline1\nline2\nline3")

        assertEquals("line1", screen[0].cells.joinToString("") { it.character })
        assertEquals("line2", screen[1].cells.joinToString("") { it.character })
        assertEquals("line3", screen[2].cells.joinToString("") { it.character })
        // line3 写在视口第 2 行（0-indexed），光标与写入行一致（同 cupCanAddress… 的语义）。
        assertEquals(2, buffer.cursor().row)
    }

    @Test
    fun cupCanAddressRowsBeyondCurrentContent() {
        val buffer = AnsiTerminalBuffer(columns = 40, rows = 8, maxRows = 50)
        buffer.append("a")
        val screen = buffer.append("\u001B[5;1Hhello")

        assertEquals("a", screen[0].cells.joinToString("") { it.character })
        assertEquals("hello", screen[4].cells.joinToString("") { it.character })
        assertEquals(4, buffer.cursor().row)
        assertEquals(5, buffer.cursor().column)
    }

    @Test
    fun preservesTrailingSpacesBeforeCursor() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val screen = buffer.append("echo  ")

        assertEquals("echo  ", screen.first().cells.joinToString("") { it.character })
        assertEquals(6, buffer.cursor().column)
    }

    @Test
    fun keepsEmojiSurrogatePairAsOneRenderableCell() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val emoji = "🙂"

        val first = emoji.substring(0, 1)
        val second = emoji.substring(1)
        buffer.append("ok $first")
        val screen = buffer.append(second)

        assertEquals("ok $emoji", screen.first().cells.joinToString("") { it.character })
        assertEquals(4, screen.first().cells.count { it.character.isNotEmpty() })
        assertEquals(5, buffer.cursor().column)
    }

    @Test
    fun supportsBackground256ColorAndStyles() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val line = buffer.append("\u001B[1;3;4;48;5;21;38;2;1;2;3mX").first()
        val cell = line.cells.first()
        assertEquals(0xFF010203L, cell.foreground)
        assertEquals(true, cell.bold)
        assertEquals(true, cell.italic)
        assertEquals(true, cell.underline)
        assertEquals(0xFF0000FFL, cell.background)
    }

    @Test
    fun wideCharactersOccupyTwoColumnsAndCombiningMarksAttach() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        val line = buffer.append("中e\u0301").first()
        assertEquals("中", line.cells.first().character)
        assertEquals(2, line.cells.first().width)
        assertEquals("e\u0301", line.cells[2].character)
        assertEquals(3, buffer.cursor().column)
    }

    @Test
    fun bracketedPasteModeIsTracked() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        buffer.append("\u001B[?2004h")
        assertEquals(true, buffer.isBracketedPasteEnabled())
        buffer.append("\u001B[?2004l")
        assertEquals(false, buffer.isBracketedPasteEnabled())
    }

    @Test
    fun wideCharactersOverwritingExistingSpacesDoNotLeaveSpaces() {
        val buffer = AnsiTerminalBuffer(columns = 20, maxRows = 10)
        // 模拟 readline/bash：先清行，再输入中文字符
        buffer.append("abcdefgh")
        val screen = buffer.append("\u001B[1G\u001B[K你好世界")

        val line = screen.first()
        assertEquals("你好世界", line.cells.joinToString("") { it.character })
        assertEquals(8, buffer.cursor().column)
        assertEquals("你", line.cells[0].character)
        assertEquals("", line.cells[1].character)
        assertEquals("好", line.cells[2].character)
        assertEquals("", line.cells[3].character)
        assertEquals("世", line.cells[4].character)
        assertEquals("", line.cells[5].character)
        assertEquals("界", line.cells[6].character)
        assertEquals("", line.cells[7].character)
    }
}
