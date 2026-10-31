package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType

class GroupChatRowsTest {

    private val base = 1_700_000_000_000L

    private fun message(
        id: String,
        senderId: String,
        type: GroupSenderType = GroupSenderType.BOT,
        at: Long = base,
    ) = GroupMessage(
        id = id,
        groupId = "group",
        senderId = senderId,
        senderName = senderId,
        senderType = type,
        content = id,
        timestamp = at,
    )

    @Test
    fun `unseen counts only messages after the anchor and ignores user messages`() {
        val messages = listOf(
            message("m1", "bot-a"),
            message("m2", "bot-b", at = base + 1_000),
            message("m3", "user_me", GroupSenderType.USER, at = base + 2_000),
            message("m4", "bot-a", at = base + 3_000),
        )
        assertEquals(2, countUnseen(messages, "m1"))
        assertEquals(1, countUnseen(messages, "m3"))
        assertEquals(0, countUnseen(messages, "m4"))
    }

    @Test
    fun `unseen falls back to zero when the anchor no longer exists`() {
        val messages = listOf(message("m1", "bot-a"))
        assertEquals(0, countUnseen(messages, "missing"))
        assertEquals(0, countUnseen(messages, null))
    }

    @Test
    fun `same sender within five minutes forms one group`() {
        val messages = listOf(
            message("m1", "bot-a"),
            message("m2", "bot-a", at = base + 4 * 60_000),
        )
        val entries = buildChatEntries(messages)
        assertTrue(entries[0].firstInGroup)
        assertFalse(entries[0].lastInGroup)
        assertFalse(entries[1].firstInGroup)
        assertTrue(entries[1].lastInGroup)
        assertFalse(entries[1].showSeparator)
    }

    @Test
    fun `five minute gap starts a new group without a time separator`() {
        val messages = listOf(
            message("m1", "bot-a"),
            message("m2", "bot-a", at = base + 5 * 60_000),
        )
        val entries = buildChatEntries(messages)
        assertTrue(entries[0].lastInGroup)
        assertTrue(entries[1].firstInGroup)
        assertFalse(entries[1].showSeparator)
    }

    @Test
    fun `thirty minute gap inserts a time separator`() {
        val messages = listOf(
            message("m1", "bot-a"),
            message("m2", "bot-b", at = base + 31 * 60_000),
        )
        val entries = buildChatEntries(messages)
        assertTrue(entries[1].showSeparator)
        assertTrue(entries[1].firstInGroup)
    }

    @Test
    fun `different senders never join even inside the grouping window`() {
        val messages = listOf(
            message("m1", "bot-a"),
            message("m2", "bot-b", at = base + 1_000),
        )
        val entries = buildChatEntries(messages)
        assertTrue(entries[1].firstInGroup)
        assertTrue(entries[1].lastInGroup)
    }
}
