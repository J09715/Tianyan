package top.tianyan.app.harness

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.directory.NestedCalls

class NestedCallRecordTest {
    @Test fun `bounded log keeps unique increasing IDs after repeated eviction and serialization`() {
        var metadata = mutableMapOf<String, String>()
        repeat(600) {
            NestedCalls.append(metadata, "parent", "host.tool", NestedCalls.STATUS_OK, 1)
            metadata = metadata.toMutableMap()
        }
        val log = NestedCalls.read(metadata)!!
        assertFalse(log.complete)
        assertEquals(600L, log.totalCalls)
        assertEquals(NestedCalls.MAX_RECORDS, log.calls.size)
        assertEquals(log.calls.size, log.calls.map { it.toolCallId }.distinct().size)
        assertEquals("parent/345", log.calls.first().toolCallId)
        assertEquals("parent/600", log.calls.last().toolCallId)
    }

    @Test fun `legacy logs without counter continue from last retained ID`() {
        val metadata = mutableMapOf(NestedCalls.METADATA_KEY to
            """{"complete":false,"calls":[{"toolCallId":"parent/300","name":"tool","status":"ok","durationMs":1}]}""")
        NestedCalls.append(metadata, "parent", "tool", NestedCalls.STATUS_OK, 1)
        val log = NestedCalls.read(metadata)!!
        assertEquals("parent/301", log.calls.last().toolCallId)
        assertEquals(301L, log.totalCalls)
        assertFalse(log.complete)
    }

    @Test fun `argument and error truncation each mark log incomplete permanently`() {
        for (isArgument in listOf(true, false)) {
            val metadata = mutableMapOf<String, String>()
            NestedCalls.append(metadata, "parent", "tool", NestedCalls.STATUS_ERROR, 1,
                arguments = if (isArgument) "a".repeat(NestedCalls.MAX_ARGUMENT_CHARS + 1) else null,
                error = if (isArgument) null else "e".repeat(NestedCalls.MAX_ERROR_CHARS + 1))
            val log = NestedCalls.read(metadata)!!
            assertFalse(log.complete)
            assertEquals(if (isArgument) NestedCalls.MAX_ARGUMENT_CHARS else NestedCalls.MAX_ERROR_CHARS,
                (if (isArgument) log.calls.single().argumentsPreview else log.calls.single().error)!!.length)
            NestedCalls.append(metadata, "parent", "tool", NestedCalls.STATUS_OK, 1)
            assertFalse(NestedCalls.read(metadata)!!.complete)
        }
    }

    @Test fun `redaction occurs before deciding whether the stored preview is truncated`() {
        val metadata = mutableMapOf<String, String>()
        NestedCalls.append(metadata, "parent", "tool", NestedCalls.STATUS_OK, 1,
            arguments = "s".repeat(10_000), redact = { "[REDACTED]" })
        assertTrue(NestedCalls.read(metadata)!!.complete)
        assertEquals("[REDACTED]", NestedCalls.read(metadata)!!.calls.single().argumentsPreview)
    }
}
