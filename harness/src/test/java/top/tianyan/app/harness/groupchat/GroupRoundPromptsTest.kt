package top.tianyan.app.harness.groupchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.core.model.bot.GroupRoundSlot

class GroupRoundPromptsTest {
    private val bot = Bot(
        id = "bot1",
        name = "Bot1",
        role = "member",
        systemPrompt = "",
        avatarSeed = "seed",
        sessionId = "session",
        createdAt = 1,
        updatedAt = 1,
    )
    private val group = GroupChat(id = "group", name = "Team", adminBotId = "bot1", createdAt = 1)

    @Test fun `dispatch announcement does not relay to the same executor but discussion still can`() {
        val planner = bot.copy(id = "planner", name = "Planner")
        val slots = mutableListOf(GroupRoundSlot(bot.id, bot.name))
        appendRoundMentions(slots, 0, "Assigned @Planner", listOf(bot, planner), emptyMap(), group, setOf(planner.id))
        assertEquals(1, slots.size)
        appendRoundMentions(slots, 0, "Please discuss @Planner", listOf(bot, planner), emptyMap(), group)
        assertEquals(listOf(bot.id, planner.id), slots.map { it.botId })
    }

    @Test fun `dispatch announcement preserves an explicit user addressed waiting slot`() {
        val planner = bot.copy(id = "planner", name = "Planner")
        val userSlot = GroupRoundSlot(planner.id, planner.name, trigger = "user correction")
        val slots = mutableListOf(GroupRoundSlot(bot.id, bot.name), userSlot)
        appendRoundMentions(slots, 0, "Assigned @Planner", listOf(bot, planner), emptyMap(), group, setOf(planner.id))
        assertEquals(2, slots.size)
        assertEquals("user correction", slots[1].trigger)
        assertEquals(null, slots[1].calledBy)
    }

    @Test fun `short speaking order lists every member`() {
        val slots = (1..3).map { GroupRoundSlot("bot$it", "Bot$it") }
        assertEquals("Bot1 → Bot2 → Bot3", formatSpeakingOrder(slots))
    }

    @Test fun `long speaking order lists a bounded preview and the total`() {
        val slots = (1..15).map { GroupRoundSlot("bot$it", "Bot$it") }
        val order = formatSpeakingOrder(slots)
        assertTrue(order.startsWith("Bot1"))
        assertTrue(order.contains("Bot12"))
        assertFalse(order.contains("Bot13"))
        assertTrue(order.endsWith("…（共 15 位）"))
    }

    @Test fun `trigger uses the bounded order`() {
        val slots = (1..15).map { GroupRoundSlot("bot$it", "Bot$it") }
        val trigger = buildRoundTrigger(group, bot, slots.first(), slots, 0, "task")
        assertTrue(trigger.contains("共 15 位"))
        assertFalse(trigger.contains("Bot13"))
    }

    private fun member(id: String, name: String) = Bot(
        id = id, name = name, role = "member", systemPrompt = "", avatarSeed = "seed",
        sessionId = "session-$id", createdAt = 1, updatedAt = 1,
    )

    @Test fun `admin all keyword wakes every other member`() {
        val members = listOf(member("admin", "主管"), member("a", "甲"), member("b", "乙"))
        val group = GroupChat(id = "group", name = "Team", adminBotId = "admin", createdAt = 1)
        val slots = mutableListOf(GroupRoundSlot("admin", "主管"))
        appendRoundMentions(slots, 0, "@所有人 把方案过一遍", members, emptyMap(), group)
        assertEquals(listOf("a", "b"), slots.drop(1).map { it.botId })
    }

    @Test fun `regular member all keyword wakes nobody`() {
        val members = listOf(member("admin", "主管"), member("a", "甲"), member("b", "乙"))
        val group = GroupChat(id = "group", name = "Team", adminBotId = "admin", createdAt = 1)
        val slots = mutableListOf(GroupRoundSlot("a", "甲"))
        appendRoundMentions(slots, 0, "@所有人 辛苦了", members, emptyMap(), group)
        assertEquals(1, slots.size)
    }

    @Test fun `named mention still beats all keyword`() {
        val members = listOf(member("admin", "主管"), member("a", "甲"), member("b", "乙"))
        val group = GroupChat(id = "group", name = "Team", adminBotId = "admin", createdAt = 1)
        val slots = mutableListOf(GroupRoundSlot("admin", "主管"))
        appendRoundMentions(slots, 0, "@甲 你来接手", members, emptyMap(), group)
        assertEquals(listOf("a"), slots.drop(1).map { it.botId })
    }

    @Test fun `mention inside code block does not relay`() {
        val members = listOf(member("admin", "主管"), member("a", "甲"))
        val group = GroupChat(id = "group", name = "Team", adminBotId = "admin", createdAt = 1)
        val slots = mutableListOf(GroupRoundSlot("admin", "主管"))
        appendRoundMentions(slots, 0, "命令是：\n```\ngit @甲\n```\n按这个跑", members, emptyMap(), group)
        assertEquals(1, slots.size)
    }
}
