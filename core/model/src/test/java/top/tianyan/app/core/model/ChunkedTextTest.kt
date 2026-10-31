package top.tianyan.app.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType

class ChunkedTextTest {
    @Test fun `snapshots share sealed blocks and do not change after append or reset`() {
        val builder = ChunkedText.Builder()
        builder.append("a".repeat(2_048) + "tail")
        val first = builder.snapshot()
        var sealed: String? = null
        first.forEachChunk { if (sealed == null) sealed = it }
        builder.append("😀\n下一段")
        val second = builder.snapshot()
        second.forEachChunk(0, 2_048) { assertSame(sealed, it) }
        assertTrue(second.isExtensionOf(first))
        assertEquals("a".repeat(2_048) + "tail", first.toString())
        builder.clear()
        builder.append("replacement")
        assertFalse(builder.snapshot().isExtensionOf(second))
        assertTrue(second.toString().endsWith("tail😀\n下一段"))
    }

    @Test fun `all ranges survive boundaries unicode and mixed chunk sizes`() {
        val source = "头😀\r\n".repeat(1_000)
        val builder = ChunkedText.Builder()
        source.chunked(7).forEach(builder::append)
        val snapshot = builder.snapshot()
        assertEquals(source.length, snapshot.length)
        for (offset in source.indices step 37) {
            val end = minOf(source.length, offset + 2_100)
            assertEquals(source.substring(offset, end), snapshot.subSequence(offset, end))
            assertEquals(source[offset], snapshot[offset])
        }
        assertEquals(ChunkedText.of(source), snapshot)
        assertEquals(ChunkedText.of(source).hashCode(), snapshot.hashCode())
    }

    @Test fun `wire format remains a string and old group records still roundtrip`() {
        val text = ChunkedText.of("hello\n😀")
        assertEquals(Json.encodeToString(text.toString()), Json.encodeToString(text))
        assertEquals(text, Json.decodeFromString<ChunkedText>(Json.encodeToString(text)))
        val message = GroupMessage("m", "g", "b", "bot", GroupSenderType.BOT, "hello", 1)
        val encoded = Json.encodeToString(message)
        assertTrue(encoded.contains("\"content\":\"hello\""))
        assertFalse(encoded.contains("contentText"))
        assertEquals(message, Json.decodeFromString<GroupMessage>(encoded))
        assertEquals("changed", message.copy(content = "changed").content)
        assertEquals("hello", message.copy(isStreaming = true).content)
    }

    @Test fun `blank tracking does not require flattening`() {
        val builder = ChunkedText.Builder()
        builder.append(" \t\r\n")
        assertFalse(builder.snapshot().hasText)
        builder.append("字")
        assertTrue(builder.snapshot().hasText)
    }
}
