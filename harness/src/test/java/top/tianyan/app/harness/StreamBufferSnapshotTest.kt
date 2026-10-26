package top.tianyan.app.harness

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.ChunkedText

class StreamBufferSnapshotTest {
    @Test fun `publishing shares blocks and final flush retains unpublished tail`() {
        val buffer = StreamBuffer()
        buffer.append("a".repeat(4_100))
        val first = buffer.publishSnapshotIfDue(100, 16)!!
        buffer.append("😀")
        assertNull(buffer.publishSnapshotIfDue(101, 16))
        val final = buffer.snapshot()
        assertTrue(final.isExtensionOf(first))
        assertEquals(4_100, first.length)
        assertEquals("a".repeat(4_100) + "😀", final.toString())
        buffer.clear()
        buffer.append("retry")
        val retried = buffer.publishSnapshotIfDue(102, 16)!!
        assertFalse(retried.isExtensionOf(final))
        assertEquals("retry", retried.toString())
    }

    @Test fun `reasoning character cap and legacy publication are unchanged`() {
        val buffer = StreamBuffer(maxChars = 5)
        buffer.append("abcdef")
        assertEquals("abcde", buffer.publishIfDue(100, 16))
        buffer.append("more")
        assertNull(buffer.publishIfDue(200, 16))
        assertEquals("abcde", buffer.toString())
    }

    @Test fun `assistant wire format and string copy remain compatible`() {
        val json = Json { encodeDefaults = true }
        val builder = ChunkedText.Builder()
        builder.append("hello\n😀")
        val message = AssistantText("id", 1, builder.snapshot(), reasoning = "thinking")
        val encoded = json.encodeToString<HarnessMessage>(message)
        assertTrue(encoded.contains("\"text\":\"hello\\n😀\""))
        assertFalse(encoded.contains("textContent"))
        assertEquals(message, json.decodeFromString<HarnessMessage>(encoded))
        assertEquals("changed", message.copy(text = "changed").text)
        assertSame(message.textContent, message.copy(reasoning = "new reasoning").textContent)
    }
}
