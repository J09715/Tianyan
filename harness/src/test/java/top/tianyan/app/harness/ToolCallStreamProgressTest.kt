package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallStreamProgressTest {

    @Test
    fun `counts newlines in an incomplete content string`() {
        val partial = """{"content":"first\nsecond\nthird"""

        assertEquals(3, countPartialJsonStringLines(partial, "content"))
    }

    @Test
    fun `does not count an escaped backslash followed by n`() {
        val partial = """{"content":"literal\\ntext"}"""

        assertEquals(1, countPartialJsonStringLines(partial, "content"))
    }

    @Test
    fun `counts edit fields independently while json is streaming`() {
        val partial = """{"path":"a.kt","oldText":"a\nb","newText":"a\nb\nc"""

        assertEquals(2, countPartialJsonStringLines(partial, "oldText"))
        assertEquals(3, countPartialJsonStringLines(partial, "newText"))
    }

    @Test
    fun `returns null before field value starts`() {
        assertNull(countPartialJsonStringLines("""{"path":"a.kt","content""", "content"))
    }

    @Test fun `all tool argument growth publishes progress including single line content`() {
        for (tool in listOf("base", "write", "edit", "use_capability")) {
            val accumulator = ToolCallAccumulator(name = tool)
            val updates = mutableListOf<ToolCallStreamProgress>()
            accumulator.arguments.append("{\"command\":\"echo first")
            accumulator.publishProgress(updates::add, force = true)
            accumulator.arguments.append(" more")
            accumulator.publishProgress(updates::add, force = true)
            assertEquals(2, updates.size)
            assertEquals(accumulator.arguments.length, updates.last().argumentChars)
            assertEquals(tool, updates.last().name)
        }
    }
}
