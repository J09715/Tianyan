package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus

class OfficeReportLinksTest {
    private val now = 100_000L
    private val chief = bot("chief")

    @Test
    fun `running and recently completed assignments become collaboration links`() {
        val links = officeReportLinks(
            listOf(
                assignment("run", "worker", BotAssignmentStatus.RUNNING, reportTo = "chief"),
                assignment("done", "tester", BotAssignmentStatus.COMPLETED, reportTo = "chief", finishedAt = now - 2_000),
                assignment("old", "writer", BotAssignmentStatus.COMPLETED, reportTo = "chief", finishedAt = now - 9_000),
            ),
            listOf(chief, bot("worker"), bot("tester"), bot("writer")),
            now,
        )
        assertEquals(2, links.size)
        assertTrue(links.first { it.taskId == "run" }.active)
        assertTrue(!links.first { it.taskId == "done" }.active)
        assertTrue(links.none { it.taskId == "old" })
    }

    @Test
    fun `links require two present members and cap fan-out`() {
        val members = listOf(chief) + (1..7).map { bot("worker-$it") }
        val links = officeReportLinks(
            (1..7).map { assignment("task-$it", "worker-$it", BotAssignmentStatus.RUNNING, reportTo = "chief") } +
                assignment("self", "chief", BotAssignmentStatus.RUNNING, reportTo = "chief") +
                assignment("stranger", "worker-1", BotAssignmentStatus.RUNNING, reportTo = "missing"),
            members,
            now,
        )
        assertEquals(5, links.size)
        assertTrue(links.none { it.fromBotId == "chief" })
    }

    @Test
    fun `completed prerequisites create dependency edges to dependents`() {
        val prerequisite = assignment("pre", "designer", BotAssignmentStatus.COMPLETED, reportTo = null, finishedAt = now - 1_000)
        val dependent = assignment(
            "next", "coder", BotAssignmentStatus.RUNNING, reportTo = "chief",
        ).copy(dependsOn = listOf("pre"), startedAt = now - 500)
        val assignments = listOf(prerequisite, dependent)
        val members = listOf(chief, bot("designer"), bot("coder"))

        val dependency = officeDependencyLinks(assignments, members, now).single()
        assertEquals("next", dependency.taskId)
        assertEquals("designer", dependency.fromBotId)
        assertEquals("coder", dependency.toBotId)
        assertTrue(dependency.active)
        assertTrue(dependency.dependency)

        val combined = officeCollaborationLinks(assignments, members, now)
        assertEquals("next", combined.first().taskId)
        assertTrue(combined.first().dependency)
    }

    @Test
    fun `failed handoffs keep the failure flag`() {
        val link = officeReportLinks(
            listOf(assignment("fail", "worker", BotAssignmentStatus.FAILED, reportTo = "chief", finishedAt = now - 500)),
            listOf(chief, bot("worker")),
            now,
        ).single()
        assertTrue(link.failed)
        assertTrue(!link.active)
    }

    private fun bot(id: String) = Bot(
        id = id,
        name = id,
        role = "Engineer",
        systemPrompt = "prompt",
        avatarSeed = "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0",
        sessionId = "session-$id",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun assignment(
        id: String,
        botId: String,
        status: BotAssignmentStatus,
        reportTo: String?,
        finishedAt: Long? = null,
    ) = BotAssignment(
        id = id,
        groupId = "group",
        botId = botId,
        botName = botId,
        title = id,
        task = id,
        status = status,
        createdAt = now - 10_000,
        startedAt = now - 9_000,
        finishedAt = finishedAt,
        reportToBotId = reportTo,
        independent = true,
    )
}
