package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

class PixelOfficeLayoutTest {
    @Test
    fun `compact scene preserves furniture pixels and opens on the blackboard`() {
        val scene = PixelOfficeScene.pixels()
        val detail = PixelOfficeDetail.pixels()
        val width = PixelOfficeDetail.WIDTH
        assertEquals(width * PixelOfficeScene.HEIGHT * 3, scene.size)
        for (worldY in listOf(20, 108, 214, 262, 352, 430)) {
            val sceneRow = (PixelOfficeScene.y(worldY.toFloat()) * 3).toInt()
            assertTrue(
                detail.copyOfRange(worldY * 3 * width, (worldY * 3 + 1) * width)
                    .contentEquals(scene.copyOfRange(sceneRow * width, (sceneRow + 1) * width)),
            )
        }
        assertEquals(-84f, openingOfficeCamera(384, 640, 2f).second, 0.001f)
    }

    @Test
    fun `overflow members use walkways instead of occupied seats`() {
        val actors = PixelOfficeLayout.place((0..23).map { bot("member-$it") }, emptyMap())
        assertEquals(actors.size, actors.map { it.x to it.y }.distinct().size)
        assertTrue(actors.drop(12).all { it.y in listOf(148, 288, 408) })
    }

    @Test
    fun `twelve active members fill eight desks and four meeting hot desks`() {
        val actors = PixelOfficeLayout.place(
            (0..11).map { bot("active-$it") },
            (0..11).associate { "active-$it" to BotAvatarState.EXECUTING },
        )
        assertEquals(12, actors.size)
        assertTrue(actors.all { it.pose == OfficePose.TYPE })
        assertEquals(8, actors.count { it.y in listOf(224, 272) })
        assertEquals(4, actors.count { it.x in listOf(96, 114, 136, 158) && it.y == 138 })
        assertTrue(actors.none { it.y in listOf(148, 288, 408) })
    }

    @Test
    fun `blocked members take the coffee bar`() {
        val actors = PixelOfficeLayout.place(
            listOf(bot("blocked-a"), bot("blocked-b")),
            mapOf("blocked-a" to BotAvatarState.BLOCKED, "blocked-b" to BotAvatarState.FAILED),
        )
        assertEquals(listOf(156, 178), actors.map { it.x })
        assertTrue(actors.all { it.pose == OfficePose.STAND })
    }

    @Test
    fun `idle actions rotate and roaming leaves the sofa`() {
        val actions = (0..5).map { slot ->
            PixelOfficeLayout.place(listOf(bot("idle")), emptyMap(), now = slot * 9_000L).single().idleAction
        }
        assertTrue(actions.any { it != OfficeIdleAction.NONE })
        val home = PixelOfficeLayout.place(listOf(bot("idle")), emptyMap(), now = 0L).single()
        val roamed = (1..48).any { second ->
            val actor = PixelOfficeLayout.place(listOf(bot("idle")), emptyMap(), now = second * 1_000L).single()
            actor.x != home.x || actor.y != home.y
        }
        assertTrue(roamed)
    }

    @Test
    fun `idle bots sit on different sofa seats and someone speaks`() {
        val actors = PixelOfficeLayout.place(listOf(bot("b"), bot("a")), emptyMap())
        assertEquals(listOf("a", "b"), actors.map { it.botId })
        assertEquals(OfficePose.SIT, actors[0].pose)
        assertEquals(OfficePose.SIT, actors[1].pose)
        assertNotEquals(actors[0].x, actors[1].x)
        assertEquals(listOf("a"), PixelOfficeLayout.speakerIds(actors, null))
    }

    @Test
    fun `running bots take the desk and the whiteboard before the floor`() {
        val actors = PixelOfficeLayout.place(
            listOf(bot("think"), bot("code")),
            mapOf("think" to BotAvatarState.THINKING, "code" to BotAvatarState.EXECUTING),
        )
        val thinker = actors.first { it.botId == "think" }
        val coder = actors.first { it.botId == "code" }
        assertEquals(OfficePose.THINK, thinker.pose)
        assertEquals(92, thinker.x)
        assertEquals(OfficePose.TYPE, coder.pose)
        assertEquals(28, coder.x)
        assertEquals(listOf("code", "think"), PixelOfficeLayout.speakerIds(actors, "code"))
    }

    @Test
    fun `room and sprite are painted instead of left blank`() {
        val room = PixelOfficeRoom.pixels()
        assertEquals(PixelOfficeRoom.WIDTH * PixelOfficeRoom.HEIGHT, room.size)
        assertEquals(PixelOfficeRoom.WALL, room[10 * PixelOfficeRoom.WIDTH + 4])
        val floor = room[80 * PixelOfficeRoom.WIDTH + 20]
        assertNotEquals(room[0], floor)
        val sprite = PixelOfficeSprite.cells(PixelAvatarRecipe(), OfficePose.STAND, BotAvatarState.IDLE, 0)
        assertEquals(0, sprite[0])
        assertTrue(sprite.any { it != 0 })
        val typing = PixelOfficeSprite.cells(PixelAvatarRecipe(), OfficePose.TYPE, BotAvatarState.EXECUTING, 0)
        val typingNext = PixelOfficeSprite.cells(PixelAvatarRecipe(), OfficePose.TYPE, BotAvatarState.EXECUTING, 1)
        assertFalse(typing.contentEquals(typingNext))
        val bulb = PixelOfficeMood.cells(BotAvatarState.THINKING, 0, waiting = false, yawn = false)
        val gear = PixelOfficeMood.cells(BotAvatarState.THINKING, 1, waiting = false, yawn = false)
        assertTrue(bulb.any { it != 0 })
        assertFalse(bulb.contentEquals(gear))
        assertTrue(PixelOfficeMood.cells(BotAvatarState.IDLE, 0, waiting = true, yawn = false).any { it != 0 })
        assertTrue(PixelOfficeMood.cells(BotAvatarState.IDLE, 0, waiting = false, yawn = true).any { it != 0 })
        assertEquals(20, PixelOfficeLayout.waitSeconds(OfficeWait("sess", 1_000, 31_000), 11_000))
        assertEquals(50, PixelOfficeLayout.waitSeconds(OfficeWait("sess", 0, Long.MAX_VALUE), 10_000))
        assertTrue(officeFrameOffset("a") in 0..2)
        val side = PixelOfficeSprite.cells(PixelAvatarRecipe(), OfficePose.TYPE, BotAvatarState.EXECUTING, 0)
        assertEquals(0, side[0])
        assertTrue(side.copyOfRange(15 * PixelOfficeSprite.WIDTH, side.size).any { it != 0 })
        val rest = PixelOfficeSprite.cells(PixelAvatarRecipe(), OfficePose.SIT, BotAvatarState.IDLE, 0)
        val sip = PixelOfficeSprite.cells(
            PixelAvatarRecipe(), OfficePose.SIT, BotAvatarState.IDLE, 0,
            idleAction = OfficeIdleAction.SIP,
        )
        val read = PixelOfficeSprite.cells(
            PixelAvatarRecipe(), OfficePose.SIT, BotAvatarState.IDLE, 0,
            idleAction = OfficeIdleAction.READ,
        )
        val stretch = PixelOfficeSprite.cells(
            PixelAvatarRecipe(), OfficePose.SIT, BotAvatarState.IDLE, 0,
            idleAction = OfficeIdleAction.STRETCH,
        )
        assertFalse(rest.contentEquals(sip))
        assertFalse(rest.contentEquals(read))
        assertFalse(rest.contentEquals(stretch))
    }

    private fun bot(id: String) = Bot(
        id = id,
        name = id,
        role = "工程师",
        systemPrompt = "prompt",
        avatarSeed = "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0",
        sessionId = "sess-$id",
        createdAt = 1L,
        updatedAt = 1L,
    )
}
