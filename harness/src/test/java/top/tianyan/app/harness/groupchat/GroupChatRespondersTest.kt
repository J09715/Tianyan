package top.tianyan.app.harness.groupchat

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.GroupReplyMode

class GroupChatRespondersTest {
    private val admin = bot("admin", "Chief")
    private val a = bot("a", "A")
    private val b = bot("b", "B")
    private val members = listOf(admin, a, b)

    private fun bot(id: String, name: String) = Bot(
        id = id,
        name = name,
        role = "",
        systemPrompt = "",
        avatarSeed = "",
        sessionId = "$id-dm",
        isMainBot = false,
        createdAt = 1,
        updatedAt = 1,
    )

    @Test fun `admin first answers alone when nobody is mentioned`() {
        assertEquals(
            listOf(admin),
            openingResponders(members, emptySet(), admin.id, GroupReplyMode.ADMIN_FIRST),
        )
    }

    @Test fun `all members speak in order with the admin first`() {
        assertEquals(
            listOf(admin, a, b),
            openingResponders(members, emptySet(), admin.id, GroupReplyMode.ALL_MEMBERS),
        )
    }

    @Test fun `mentions always win over the reply mode`() {
        assertEquals(
            listOf(a),
            openingResponders(members, setOf("a"), admin.id, GroupReplyMode.ALL_MEMBERS),
        )
        assertEquals(
            listOf(a, b),
            openingResponders(members, setOf("a", "b"), admin.id, GroupReplyMode.ADMIN_FIRST),
        )
    }

    @Test fun `unknown mention falls back to the configured mode`() {
        assertEquals(
            listOf(admin),
            openingResponders(members, setOf("missing"), admin.id, GroupReplyMode.ADMIN_FIRST),
        )
        assertEquals(
            listOf(admin, a, b),
            openingResponders(members, setOf("missing"), admin.id, GroupReplyMode.ALL_MEMBERS),
        )
    }

    @Test fun `user all keyword wakes every member with the admin first`() {
        assertEquals(
            listOf(admin, a, b),
            openingResponders(members, setOf("所有人"), admin.id, GroupReplyMode.ADMIN_FIRST),
        )
        assertEquals(
            listOf(admin, a, b),
            openingResponders(members, setOf("all"), admin.id, GroupReplyMode.ADMIN_FIRST),
        )
    }

    @Test fun `all members still works when the admin is gone`() {
        assertEquals(
            listOf(a, b),
            openingResponders(listOf(a, b), emptySet(), admin.id, GroupReplyMode.ALL_MEMBERS),
        )
    }

    @Test fun `all members is capped to the round limit`() {
        val many = (1..25).map { bot("m$it", "M$it") }
        val responders = openingResponders(many, emptySet(), many.first().id, GroupReplyMode.ALL_MEMBERS)
        assertEquals(20, responders.size)
        assertEquals(many.first(), responders.first())
    }
}
