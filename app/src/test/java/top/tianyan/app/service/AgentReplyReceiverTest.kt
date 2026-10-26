package top.tianyan.app.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus

class AgentReplyReceiverTest {

    @Test
    fun `reply to independent task routes to continueTask`() = runBlocking {
        var continuedTaskId: String? = null
        var continuedGroupId: String? = null
        var continuedInstruction: String? = null
        var fallbackSent = false

        val fakeAssignment = BotAssignment(
            id = "task_1",
            groupId = "group_1",
            botId = "bot_1",
            botName = "工程师",
            title = "优化代码",
            task = "重构模块",
            status = BotAssignmentStatus.COMPLETED,
            writePaths = emptyList(),
            createdAt = 0,
            startedAt = 0,
            independent = true,
            sessionId = "independent_session_1",
        )

        val receiver = AgentReplyReceiver()
        receiver.routeReply(
            reply = "请继续编写单元测试",
            targetSessionId = "independent_session_1",
            assignmentLookup = { if (it == "independent_session_1") fakeAssignment else null },
            continueTaskHandler = { id, gId, instruction ->
                continuedTaskId = id
                continuedGroupId = gId
                continuedInstruction = instruction
            },
            groupLookup = { null },
            groupSendHandler = { _, _ -> },
            fallbackSendHandler = { _, _ -> fallbackSent = true },
        )

        assertEquals("task_1", continuedTaskId)
        assertEquals("group_1", continuedGroupId)
        assertEquals("请继续编写单元测试", continuedInstruction)
        assertFalse(fallbackSent)
    }

    @Test
    fun `reply to group bot session routes to group dispatcher with mention prefix`() = runBlocking {
        var dispatchedGroupId: String? = null
        var dispatchedText: String? = null
        var fallbackSent = false

        val receiver = AgentReplyReceiver()
        receiver.routeReply(
            reply = "这个改动很好",
            targetSessionId = "group_bot_session_1",
            assignmentLookup = { null },
            continueTaskHandler = { _, _, _ -> },
            groupLookup = { if (it == "group_bot_session_1") "group_tech" to "开发者" else null },
            groupSendHandler = { gId, text ->
                dispatchedGroupId = gId
                dispatchedText = text
            },
            fallbackSendHandler = { _, _ -> fallbackSent = true },
        )

        assertEquals("group_tech", dispatchedGroupId)
        assertEquals("@开发者 这个改动很好", dispatchedText)
        assertFalse(fallbackSent)
    }

    @Test
    fun `reply to group bot session preserves existing mention prefix`() = runBlocking {
        var dispatchedText: String? = null

        val receiver = AgentReplyReceiver()
        receiver.routeReply(
            reply = "@专家 自定义提及内容",
            targetSessionId = "group_bot_session_1",
            assignmentLookup = { null },
            continueTaskHandler = { _, _, _ -> },
            groupLookup = { "group_tech" to "开发者" },
            groupSendHandler = { _, text -> dispatchedText = text },
            fallbackSendHandler = { _, _ -> },
        )

        assertEquals("@专家 自定义提及内容", dispatchedText)
    }

    @Test
    fun `reply to standalone session falls back to harnessLoop send`() = runBlocking {
        var sentSession: String? = null
        var sentText: String? = null

        val receiver = AgentReplyReceiver()
        receiver.routeReply(
            reply = "普通对话输入",
            targetSessionId = "standalone_chat_session",
            assignmentLookup = { null },
            continueTaskHandler = { _, _, _ -> },
            groupLookup = { null },
            groupSendHandler = { _, _ -> },
            fallbackSendHandler = { text, sessId ->
                sentText = text
                sentSession = sessId
            },
        )

        assertEquals("standalone_chat_session", sentSession)
        assertEquals("普通对话输入", sentText)
    }
}
