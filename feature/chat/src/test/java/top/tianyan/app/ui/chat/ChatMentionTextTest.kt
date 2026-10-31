package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMentionTextTest {

    @Test
    fun `known name with internal space matches as a whole`() {
        val regex = buildMentionRegex(listOf("Git 敏捷工作流", "Git"))
        val match = regex.find("@Git 敏捷工作流 帮我回退")
        assertEquals("@Git 敏捷工作流", match?.value)
    }

    @Test
    fun `generic fallback stops at chinese punctuation used by MentionExtractor`() {
        val regex = buildMentionRegex(emptyList())
        val names = regex.findAll("联系 @Skill，或 @x:y 再问 @trigger_cmd").map { it.groupValues[1] }.toList()
        assertEquals(listOf("Skill", "x", "trigger_cmd"), names)
    }

    @Test
    fun `email is not treated as mention`() {
        val regex = buildMentionRegex(listOf("host.com"))
        assertNull(regex.find("邮箱 user@host.com"))
        assertTrue(regex.findAll("邮箱 user@host.com").none())
    }
}
