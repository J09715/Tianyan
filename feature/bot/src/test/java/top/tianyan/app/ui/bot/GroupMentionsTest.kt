package top.tianyan.app.ui.bot

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class GroupMentionsTest {
    @Test fun `ordinary text and email do not trigger member suggestions`() {
        for (text in listOf("", "hello", "mail@host", "@member done")) {
            assertNull(activeGroupMention(TextFieldValue(text, TextRange(text.length))))
        }
    }

    @Test fun `query follows the caret and replaces the complete token`() {
        val text = "请 @测试员 检查"
        val value = TextFieldValue(text, TextRange(4))
        assertEquals("测", activeGroupMention(value)?.query)
        val inserted = insertGroupMention(value, "工程师")
        assertEquals("请 @工程师 检查", inserted.text)
        assertEquals(TextRange(7), inserted.selection)
    }

    @Test fun `selection does not replace unrelated content`() {
        val value = TextFieldValue("@测试", TextRange(0, 3))
        assertNull(activeGroupMention(value))
        assertEquals(value, insertGroupMention(value, "工程师"))
    }

    @Test fun `mention with following punctuation only replaces member token without deleting text`() {
        val text = "@测，检查结果"
        val value = TextFieldValue(text, TextRange(2))
        assertEquals("测", activeGroupMention(value)?.query)
        val inserted = insertGroupMention(value, "工程师")
        assertEquals("@工程师 ，检查结果", inserted.text)
        assertEquals(TextRange(5), inserted.selection)
    }

    @Test fun `cursor after punctuation does not trigger mention suggestion`() {
        val text = "@测，"
        val value = TextFieldValue(text, TextRange(3))
        assertNull(activeGroupMention(value))
    }
}
