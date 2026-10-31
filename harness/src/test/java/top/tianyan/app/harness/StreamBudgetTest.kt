package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamBudgetTest {

    @Test
    fun `budget throws once accumulated chars exceed the limit`() {
        val budget = StreamBudget(maxChars = 10)
        budget.add("12345")
        budget.add("67890") // 累计正好达到上限，未超限
        assertThrows(StreamBudgetExceededException::class.java) { budget.add("X") }
    }

    @Test
    fun `budget ignores empty and non-positive increments`() {
        val budget = StreamBudget(maxChars = 3)
        budget.add("")
        budget.add(-5)
        budget.add(0)
        budget.add("abc")
        assertThrows(StreamBudgetExceededException::class.java) { budget.add("d") }
    }

    @Test
    fun `demuxer charges streamed content against the budget`() {
        val budget = StreamBudget(maxChars = 10)
        val dest = StringBuilder()
        val demuxer = ThinkTagStreamDemuxer(onReasoning = {}, onDelta = { dest.append(it) }, budget = budget)
        demuxer.onContentChunk("1234567890")
        assertEquals("1234567890", dest.toString())
        assertThrows(StreamBudgetExceededException::class.java) { demuxer.onContentChunk("X") }
    }

    @Test
    fun `demuxer charges explicit reasoning against the budget`() {
        val budget = StreamBudget(maxChars = 5)
        val demuxer = ThinkTagStreamDemuxer(onReasoning = {}, onDelta = {}, budget = budget)
        demuxer.onExplicitReasoningChunk("abcde")
        assertThrows(StreamBudgetExceededException::class.java) { demuxer.onExplicitReasoningChunk("f") }
    }
}