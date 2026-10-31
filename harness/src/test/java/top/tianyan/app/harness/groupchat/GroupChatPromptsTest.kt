package top.tianyan.app.harness.groupchat

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.bot.*

class GroupChatPromptsTest {
    private val group = GroupChat("group", "Team", "chief", createdAt = 1)
    private fun message(id: String, type: GroupSenderType, text: String, task: String? = null) =
        GroupMessage(id, "group", "sender", "Sender", type, text, 1, delegatedTaskId = task)

    @Test fun `task reports replace duplicate member answers and latest corrections survive budget`() {
        val rows = (1..10).map { message("old$it", GroupSenderType.SYSTEM, "old".repeat(500), "task$it") } + listOf(
            message("answer", GroupSenderType.BOT, "duplicate game result", "game"),
            message("report", GroupSenderType.SYSTEM, "authoritative game report", "game"),
            message("user", GroupSenderType.USER, "latest correction"),
        )
        val history = formatGroupHistory(group, rows)
        assertFalse(history.contains("duplicate game result"))
        assertTrue(history.contains("authoritative game report"))
        assertTrue(history.endsWith("latest correction"))
        assertTrue(history.length <= 6_000)
    }

    @Test fun `routing names are exact and new tasks use workspace paths`() {
        val chief = Bot("chief", "Chief", "Lead", "persona", "seed", sessionId = "s", isMainBot = true, createdAt = 1, updatedAt = 1)
        val member = chief.copy(id = "member", name = "Worker", role = "Engineer", isMainBot = false)
        val prompt = buildGroupPrompt(group, chief, "build", "", listOf(chief, member))
        assertTrue(prompt.contains("bot_name=\"Worker\""))
        assertTrue(prompt.contains("/workspace/目标目录"))
        assertFalse(prompt.contains("/root/目标目录"))
        assertTrue(prompt.contains("select-model"))
        assertTrue(prompt.contains("同一轮多次 assign-task"))
        assertTrue(prompt.contains("禁止用 invoke_subagent"))
        assertTrue(prompt.contains("立会结论"))
        assertTrue(prompt.contains("assign-task(wait=false)"))
    }

    @Test fun `report from an earlier revision does not hide newer task output`() {
        val history = formatGroupHistory(group, listOf(
            message("old-report", GroupSenderType.SYSTEM, "first round result", "task"),
            message("next-reply", GroupSenderType.BOT, "second round result", "task").copy(timestamp = 2),
        ))
        assertTrue(history.contains("second round result"))
    }
}
