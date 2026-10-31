package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText

class ChatSpeechGateTest {
    private fun reply(id: String, text: String = id) = AssistantText(id, Long.MAX_VALUE, text)

    @Test fun `history is silent and streaming reply is read once after completion`() {
        val gate = ChatSpeechGate()
        val history = reply("old")
        gate.update("a", listOf(history), false, true)
        assertTrue(gate.drain().isEmpty())
        gate.update("a", listOf(history, reply("new", "partial")), true, true)
        assertTrue(gate.drain().isEmpty())
        gate.update("a", listOf(history, reply("new", "complete")), false, true)
        assertEquals(listOf("complete"), gate.drain())
        gate.update("a", listOf(history, reply("new", "complete")), false, true)
        assertTrue(gate.drain().isEmpty())
    }

    @Test fun `turning off drops queued speech and reenable does not replay it`() {
        val gate = ChatSpeechGate()
        gate.update("a", emptyList(), false, true)
        gate.update("a", listOf(reply("new")), false, true)
        gate.update("a", listOf(reply("new")), false, false)
        assertTrue(gate.drain().isEmpty())
        gate.update("a", listOf(reply("new")), false, true)
        assertTrue(gate.drain().isEmpty())
    }

    @Test fun `switching session drops pending speech and baselines history`() {
        val gate = ChatSpeechGate()
        gate.update("a", emptyList(), false, true)
        gate.update("a", listOf(reply("new")), false, true)
        gate.update("b", listOf(reply("history")), false, true)
        assertTrue(gate.drain().isEmpty())
        gate.update("b", listOf(reply("history"), reply("next")), false, true)
        assertEquals(listOf("next"), gate.drain())
    }

    @Test fun `delayed history loads remain silent`() {
        val gate = ChatSpeechGate()
        gate.update("b", emptyList(), false, true)
        gate.update("b", listOf(AssistantText("old", 0, "history")), false, true)
        assertTrue(gate.drain().isEmpty())
    }

    @Test fun `reasoning is never spoken including reasoning only responses`() {
        val gate = ChatSpeechGate()
        gate.update("a", emptyList(), false, true)
        gate.update("a", listOf(reply("answer", "正文").copy(reasoning = "内部思考"),
            reply("thinking", "").copy(reasoning = "只有思考")), false, true)
        assertEquals(listOf("正文"), gate.drain())
    }

    @Test fun `spoken prose omits code and image urls`() {
        assertEquals("标题\n正文 链接", speechText("# 标题\n正文 [链接](https://example.com)\n```kotlin\nsecret()\n```\n![图](https://example.com/image.png)"))
    }

    @Test fun `inline thinking tags are silent even when unclosed`() {
        assertEquals("正文", speechText("<think>不朗读的思考</think>正文"))
        assertEquals("", speechText("<think>尚未结束的思考"))
    }
}
