package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotAvatarState

class OfficeWalkHandoffsTest {
    private val now = 100_000L
    private val chief = bot("chief")

    @Test
    fun `recent completions become walking handoffs`() {
        val handoffs = officeWalkHandoffs(
            listOf(
                assignment("done", "worker", BotAssignmentStatus.COMPLETED, finishedAt = now - 1_000),
                assignment("running", "runner", BotAssignmentStatus.RUNNING),
                assignment("old", "old", BotAssignmentStatus.COMPLETED, finishedAt = now - 9_000),
                assignment("failed", "fail", BotAssignmentStatus.FAILED, finishedAt = now - 500),
            ),
            listOf(chief, bot("worker"), bot("runner"), bot("old"), bot("fail")),
            now,
        )
        assertEquals(listOf("done"), handoffs.map { it.taskId })
    }

    @Test
    fun `handoff pairs require the target member and cap at two`() {
        val members = listOf(chief, bot("a"), bot("b"), bot("c"))
        val handoffs = officeWalkHandoffs(
            listOf(
                assignment("self", "chief", BotAssignmentStatus.COMPLETED, finishedAt = now - 500),
                assignment("missing", "a", BotAssignmentStatus.COMPLETED, finishedAt = now - 600, reportTo = "ghost"),
                assignment("a", "a", BotAssignmentStatus.COMPLETED, finishedAt = now - 700),
                assignment("b", "b", BotAssignmentStatus.COMPLETED, finishedAt = now - 800),
                assignment("c", "c", BotAssignmentStatus.COMPLETED, finishedAt = now - 900),
            ),
            members,
            now,
        )
        assertEquals(listOf("a", "b"), handoffs.map { it.taskId })
    }

    @Test
    fun `completed prerequisite hands off to the dependent task owner`() {
        val prerequisite = assignment("pre", "designer", BotAssignmentStatus.COMPLETED, finishedAt = now - 500)
        val dependent = assignment("next", "coder", BotAssignmentStatus.RUNNING, reportTo = "chief")
            .copy(dependsOn = listOf("pre"), startedAt = now - 300)
        val handoffs = officeWalkHandoffs(
            listOf(prerequisite, dependent),
            listOf(chief, bot("designer"), bot("coder")),
            now,
        )
        val handoff = handoffs.single()
        assertEquals("next", handoff.taskId)
        assertEquals("designer", handoff.fromBotId)
        assertEquals("coder", handoff.toBotId)
    }

    @Test
    fun `only same floor actors walk and the source is hidden`() {
        val sameFloor = OfficeWalkHandoff("done", "worker", "chief", now - 500)
        val crossFloor = OfficeWalkHandoff("cross", "worker", "other", now - 500)
        val actors = listOf(
            actor("chief", 40, 338),
            actor("worker", 116, 340),
            actor("other", 48, 150),
        )
        val state = officeHandoffState(listOf(sameFloor, crossFloor), actors)
        assertEquals(listOf(sameFloor), state.handoffs)
        assertEquals(setOf("worker"), state.courierIds)
        assertEquals(setOf("done"), state.taskIds)

        val cross = officeHandoffState(listOf(crossFloor), actors)
        assertTrue(cross.handoffs.isEmpty())
        assertTrue(cross.courierIds.isEmpty())
    }

    @Test
    fun `handoff floor groups come from the scene spec`() {
        val handoff = OfficeWalkHandoff("done", "worker", "chief", now - 500)
        val actors = listOf(
            actor("chief", 48, 150),
            actor("worker", 116, 340),
        )
        assertTrue(officeHandoffState(listOf(handoff), actors).handoffs.isEmpty())

        val merged = OfficeSceneSpec.Default.copy(
            floors = listOf(OfficeFloorSpec("all", 0, PixelOfficeRoom.HEIGHT)),
        )
        assertEquals(listOf(handoff), officeHandoffState(listOf(handoff), actors, merged).handoffs)
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

    private fun actor(id: String, x: Int, y: Int) = OfficeActor(
        botId = id,
        name = id,
        x = x,
        y = y,
        pose = OfficePose.SIT,
        state = BotAvatarState.SUCCESS,
    )

    private fun assignment(
        id: String,
        botId: String,
        status: BotAssignmentStatus,
        finishedAt: Long? = null,
        reportTo: String = "chief",
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
