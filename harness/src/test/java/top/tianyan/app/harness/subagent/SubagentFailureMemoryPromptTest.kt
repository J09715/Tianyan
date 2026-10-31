package top.tianyan.app.harness.subagent

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.*
import top.tianyan.app.harness.operation.FailureMemory

class SubagentFailureMemoryPromptTest {
    @Test fun scopedBackgroundRemainsAvailableAfterLaneHistoryFoldsInBothProtocolsAndFinalRound() {
        val call = ToolCall("call", 2, HarnessTool.BASE, buildJsonObject { })
        val state = FailureMemory.update(null, "child-op", call, ToolResult("result", 3, call.id, false, "failure"), 10)
        val memory = FailureMemory.prompt(state, "child-op", 11)
        val messages = listOf(UserMessage("task", 1, "child goal"), call, ToolResult("result", 3, call.id, false, "x".repeat(30_000)),
            AssistantText("answer", 4, "continue"))
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            val request = isolatedProviderMessages(messages, "system", true, mode, historyBudgetTokens = 100, failureMemory = memory)
            assertEquals("system", request.first().role)
            assertTrue(request.first().content.orEmpty().contains("禁止继续调用工具"))
            assertFalse(request.first().content.orEmpty().contains("失败记忆"))
            assertEquals("user", request.last().role)
            assertEquals(memory, request.last().content)
        }
        assertTrue(FailureMemory.prompt(state, "parent-op", 11).isEmpty())
    }
}
