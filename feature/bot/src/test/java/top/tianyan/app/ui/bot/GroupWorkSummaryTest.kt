package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.bot.ActiveBotAssignment

class GroupWorkSummaryTest {
    private fun task(id: String, status: BotAssignmentStatus) = BotAssignment(
        id, "group", "bot", "工程师", "task", "task", status, createdAt = 0, startedAt = 0)
    private fun live(id: String) = ActiveBotAssignment(id, "group", "工程师", 0)
    private fun message(id: String, taskId: String?) = GroupMessage(
        id, "group", "bot", "工程师", GroupSenderType.BOT, "working", 0,
        isStreaming = true, delegatedTaskId = taskId)

    @Test fun `task tracker and stream count the same task once`() {
        assertEquals(GroupWorkSummary(1, 0, 0), summarizeGroupWork(
            listOf(task("1", BotAssignmentStatus.RUNNING)), listOf(live("1")),
            listOf(message("a", "1"), message("b", "1"))))
    }

    @Test fun `independent tasks by the same bot remain separate`() {
        assertEquals(GroupWorkSummary(2, 1, 0), summarizeGroupWork(
            listOf(task("3", BotAssignmentStatus.QUEUED)), listOf(live("1"), live("2")),
            listOf(message("a", "1"), message("b", "2"))))
    }

    @Test fun `terminal status wins over stale live projections`() {
        assertEquals(GroupWorkSummary(0, 0, 1), summarizeGroupWork(
            listOf(task("1", BotAssignmentStatus.FAILED), task("2", BotAssignmentStatus.COMPLETED)),
            listOf(live("1"), live("2")), listOf(message("a", "1"), message("b", "2"))))
    }

    @Test fun `ordinary streaming replies count once per bot`() {
        assertEquals(GroupWorkSummary(1, 0, 0), summarizeGroupWork(
            emptyList(), emptyList(), listOf(message("a", null), message("b", null))))
    }
}
