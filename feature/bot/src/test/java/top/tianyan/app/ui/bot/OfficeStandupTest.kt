package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotAvatarState

class OfficeStandupTest {
    private val now = 100_000L
    private val host = bot("host", main = true)

    @Test
    fun `a close parallel dispatch becomes a standup with the host`() {
        val standup = officeStandup(
            listOf(
                assignment("a", now - 1_000),
                assignment("b", now - 2_000),
            ),
            listOf(host, bot("a"), bot("b")),
            now,
        )
        assertEquals(listOf("host", "a", "b"), standup.ids)
        assertEquals("host", standup.hostId)
        assertTrue(standup.speakerId in standup.ids)
        assertEquals("do a", standup.titles["a"])
        assertEquals("do b", standup.titles["b"])
    }

    @Test
    fun `slow spread or a single task does not trigger a standup`() {
        assertTrue(
            officeStandup(
                listOf(assignment("a", now - 1_000), assignment("b", now - 7_000)),
                listOf(host, bot("a"), bot("b")),
                now,
            ).ids.isEmpty(),
        )
        assertTrue(
            officeStandup(listOf(assignment("a", now - 1_000)), listOf(host, bot("a")), now).ids.isEmpty(),
        )
        assertTrue(
            officeStandup(
                listOf(assignment("a", now - 10_000), assignment("b", now - 11_000)),
                listOf(host, bot("a"), bot("b")),
                now,
            ).ids.isEmpty(),
        )
    }

    @Test
    fun `standup caps at the four meeting seats`() {
        val bots = listOf(host) + ('a'..'e').map { bot(it.toString()) }
        val standup = officeStandup(
            ('a'..'e').mapIndexed { index, id -> assignment(id.toString(), now - (index + 1) * 500L) },
            bots,
            now,
        )
        assertEquals(4, standup.ids.size)
        assertEquals("host", standup.ids.first())
        assertEquals(3, standup.titles.size)
    }

    @Test
    fun `standup actors sit at the meeting table before their desks`() {
        val members = listOf(host, bot("a"), bot("b"))
        val actors = PixelOfficeLayout.place(
            members,
            mapOf("a" to BotAvatarState.EXECUTING, "b" to BotAvatarState.EXECUTING),
            now = 1_000L,
            standup = setOf("host", "a", "b"),
        )
        assertEquals(3, actors.size)
        assertTrue(actors.all { it.pose == OfficePose.SIT && it.y == 138 })
        assertTrue(actors.map { it.x }.all { it in listOf(96, 114, 136, 158) })
    }

    private fun bot(id: String, main: Boolean = false) = Bot(
        id = id,
        name = id,
        role = "Engineer",
        systemPrompt = "prompt",
        avatarSeed = "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0",
        sessionId = "session-$id",
        isMainBot = main,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun assignment(botId: String, startedAt: Long) = BotAssignment(
        id = "task-$botId",
        groupId = "group",
        botId = botId,
        botName = botId,
        title = "do $botId",
        task = "do $botId",
        status = BotAssignmentStatus.RUNNING,
        createdAt = startedAt,
        startedAt = startedAt,
        reportToBotId = "host",
        independent = true,
    )
}
