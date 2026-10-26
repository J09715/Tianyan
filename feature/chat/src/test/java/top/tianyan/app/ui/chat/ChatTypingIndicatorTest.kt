package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*

class ChatTypingIndicatorTest {
    private val user = UserMessage("input", 1, "hello")
    @Test fun waitsForTheFirstTokenOfTheLatestUserMessage() {
        assertTrue(isWaitingForFirstToken(listOf(user), true))
        assertTrue(isWaitingForFirstToken(listOf(user, AssistantText("response", 2, "")), true))
        assertFalse(isWaitingForFirstToken(listOf(user, AssistantText("response", 2, "hi")), true))
        assertTrue(isWaitingForFirstToken(listOf(user, AssistantText("response", 2, "hi"), user.copy(id = "next")), true))
    }
    @Test fun reasoningAndToolOutputEndTheWaitAndStoppingClearsIt() {
        assertFalse(isWaitingForFirstToken(listOf(user, AssistantText("response", 2, "", reasoning = "thinking")), true))
        assertFalse(isWaitingForFirstToken(listOf(user, ToolCall("tool", 2, HarnessTool.BASE, buildJsonObject {})), true))
        assertFalse(isWaitingForFirstToken(listOf(user), false))
        assertFalse(isWaitingForFirstToken(emptyList(), true))
    }
}
