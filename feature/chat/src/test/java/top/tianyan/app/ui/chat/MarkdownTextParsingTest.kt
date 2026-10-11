package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTextParsingTest {

    @Test
    fun `parses headings and paragraphs`() {
        val blocks = parseMarkdownBlocks("# 标题\n段落一\n\n## 副标题")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdHeading)
        assertEquals("标题", (blocks[0] as MdHeading).text)
        assertTrue(blocks[1] is MdParagraph)
        assertEquals("段落一", (blocks[1] as MdParagraph).text)
        assertTrue(blocks[2] is MdHeading)
    }

    @Test
    fun `parses code blocks and preserves content`() {
        val blocks = parseMarkdownBlocks("```kotlin\nval x = 1\n```")
        assertEquals(1, blocks.size)
        val code = blocks[0] as MdCodeBlock
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun `parses ordered and unordered lists`() {
        val blocks = parseMarkdownBlocks("- a\n- b\n\n1. x\n2. y")
        assertEquals(2, blocks.size)
        val unordered = blocks[0] as MdList
        assertEquals(listOf("a", "b"), unordered.items)
        assertEquals(false, unordered.ordered)

        val ordered = blocks[1] as MdList
        assertEquals(listOf("x", "y"), ordered.items)
        assertEquals(true, ordered.ordered)
    }

    @Test
    fun `parses tables with headers and rows`() {
        val markdown = """| A | B |
| - | - |
| 1 | 2 |"""
        val blocks = parseMarkdownBlocks(markdown)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdTable
        assertEquals(listOf("A", "B"), table.headers)
        assertEquals(listOf(listOf("1", "2")), table.rows)
    }

    @Test
    fun `parses quotes`() {
        val blocks = parseMarkdownBlocks("> first\n> second")
        assertEquals(1, blocks.size)
        val quote = blocks[0] as MdQuote
        assertEquals(listOf("first", "second"), quote.lines)
    }

    @Test
    fun `standalone image url becomes remote media block`() {
        val blocks = parseMarkdownBlocks("https://example.com/image.png?x=1")
        assertEquals(1, blocks.size)
        val media = blocks[0] as MdRemoteMedia
        assertEquals("https://example.com/image.png?x=1", media.url)
    }

    @Test
    fun `standalone non-image url remains paragraph so inline link rendering can click it`() {
        val blocks = parseMarkdownBlocks("https://example.com/docs")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MdParagraph
        assertEquals("https://example.com/docs", paragraph.text)
    }

    @Test
    fun `inline image in paragraph is extracted as media block`() {
        val blocks = parseMarkdownBlocks("看图 ![alt](https://example.com/a.jpg) 后文")
        assertEquals(3, blocks.size)
        val paragraphBefore = blocks[0] as MdParagraph
        assertEquals("看图", paragraphBefore.text)
        val media = blocks[1] as MdRemoteMedia
        assertEquals("https://example.com/a.jpg", media.url)
        val paragraphAfter = blocks[2] as MdParagraph
        assertEquals("后文", paragraphAfter.text)
    }

    @Test
    fun `html image in paragraph is extracted as media block`() {
        val blocks = parseMarkdownBlocks("前文<img src=\"https://example.com/b.png\">后文")
        assertEquals(3, blocks.size)
        val media = blocks[1] as MdRemoteMedia
        assertEquals("https://example.com/b.png", media.url)
    }

    @Test
    fun `markdown link stays paragraph text for inline link annotation`() {
        val blocks = parseMarkdownBlocks("[文档](https://example.com/doc)")
        assertEquals(1, blocks.size)
        val paragraph = blocks[0] as MdParagraph
        assertEquals("[文档](https://example.com/doc)", paragraph.text)
    }

    @Test
    fun `trim url punctuation keeps balanced parentheses`() {
        assertEquals("https://example.com/a", trimUrlPunctuation("https://example.com/a."))
        assertEquals("https://example.com/a", trimUrlPunctuation("https://example.com/a,"))
        assertEquals("https://example.com/a(b)", trimUrlPunctuation("https://example.com/a(b))."))
        assertEquals("https://example.com/a", trimUrlPunctuation("https://example.com/a)"))
    }

    @Test
    fun `blank markdown produces no blocks`() {
        assertTrue(parseMarkdownBlocks("").isEmpty())
        assertTrue(parseMarkdownBlocks("   \n\n").isEmpty())
    }
}
