package top.tianyan.app.ui.components

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.ChunkedText

class IncrementalMarkdownTest {
    @Test fun `long multi paragraph streams parse each completed region once`() {
        var parsedChars = 0L
        val parser = IncrementalMarkdown { source ->
            parsedChars += source.length
            if (source.isBlank()) emptyList() else listOf(source)
        }
        val builder = ChunkedText.Builder()
        repeat(1_000) { index ->
            builder.append("paragraph $index\n\n")
            parser.update(builder.snapshot())
        }
        assertEquals(builder.length.toLong(), parser.scannedChars)
        assertEquals(builder.length.toLong(), parsedChars)
        assertEquals(1_000, parser.update(builder.snapshot()).size)
    }

    @Test fun `every streamed prefix matches the complete parser across markdown and unicode boundaries`() {
        val fixtures = listOf(
            "# 标题😀\n\nnormal **bold** and [link](https://example.com)\nnext line\n\nlast",
            "before\n\n```kotlin\nval a = 1\n\nprintln(a)\n```\n\nend",
            "| col A | col B |\n| --- | --- |\n| 中文 | 😀 |\n| next | row |\n\nend",
            "- first\n- second\n\n1. third\n2. fourth\n\n> quote\n> next\n\n---\n",
            "![pic](https://example.com/a.png)\n\ntext <img src=\"/workspace/a.png\"> after\n\n",
            "a\r\n\r\nb\rc\n\n```\r\nx\r\n\r\ny\r\n```\r\n",
        )
        fixtures.forEach { source ->
            for (size in listOf(1, 2, 7, 31)) {
                val builder = ChunkedText.Builder()
                val parser = IncrementalMarkdown { parseMarkdownBlocks(it, cache = false) }
                var prefix = ""
                source.chunked(size).forEach { delta ->
                    prefix += delta
                    builder.append(delta)
                    assertEquals("chunk size $size, prefix $prefix",
                        parseMarkdownBlocks(prefix, cache = false), parser.update(builder.snapshot()))
                }
                assertEquals(source.length.toLong(), parser.scannedChars)
            }
        }
    }

    @Test fun `only new source is scanned and committed paragraphs are not reparsed`() {
        val parsed = mutableListOf<String>()
        val parser = IncrementalMarkdown { text -> parsed += text; listOf(text) }
        val builder = ChunkedText.Builder()
        builder.append("stable\n\n")
        val first = parser.update(builder.snapshot()).first()
        parsed.clear()
        builder.append("tail")
        assertSame(first, parser.update(builder.snapshot()).first())
        assertEquals(listOf("tail"), parsed)
        assertEquals(builder.length.toLong(), parser.scannedChars)
        val snapshot = builder.snapshot()
        parser.update(snapshot)
        parsed.clear()
        parser.update(snapshot)
        assertTrue(parsed.isEmpty())
    }

    @Test fun `blank lines inside fences remain mutable until the block closes`() {
        val parsed = mutableListOf<String>()
        val parser = IncrementalMarkdown { text -> parsed += text; listOf(text) }
        val builder = ChunkedText.Builder()
        for (part in listOf("``", "`kt\ncode\n\n", "more\n```\n\n", "end")) {
            builder.append(part)
            parser.update(builder.snapshot())
        }
        assertEquals("```kt\ncode\n\nmore\n```\n\n", parser.update(builder.snapshot()).first())
        assertEquals("end", parsed.last())
    }

    @Test fun `replacement shrink and unrelated same length snapshots reset old blocks`() {
        val parser = IncrementalMarkdown { text -> listOf(text) }
        parser.update(ChunkedText.of("old\n\ntail"))
        assertEquals(listOf("new"), parser.update(ChunkedText.of("new")))
        assertEquals(listOf("fix"), parser.update(ChunkedText.of("fix")))
        val builder = ChunkedText.Builder()
        builder.append("one")
        val old = builder.snapshot()
        builder.append("two")
        parser.update(builder.snapshot())
        assertEquals(listOf("one"), parser.update(old))
    }

    @Test fun `CR LF split across network events is normalized exactly once`() {
        val parser = IncrementalMarkdown { text -> if (text.isEmpty()) emptyList() else listOf(text) }
        val builder = ChunkedText.Builder()
        listOf("first\r", "\n\r", "\nsecond\r", "last").forEach {
            builder.append(it)
            parser.update(builder.snapshot())
        }
        assertEquals(listOf("first\n\n", "second\nlast"), parser.update(builder.snapshot()))
    }
}
