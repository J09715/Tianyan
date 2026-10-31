package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 分享快捷指令的 prompt 包装规则（模板结构 + 原文保真）。 */
class SharePromptWrapTest {

    @Test
    fun `summarize wraps text with instruction and fences`() {
        val prompt = wrapSharePrompt(ShareQuickAction.SUMMARIZE, "hello world")
        assertTrue(prompt.contains("总结"))
        assertTrue(prompt.contains("---\nhello world\n---"))
        assertTrue(prompt.indexOf("总结") < prompt.indexOf("hello world"))
    }

    @Test
    fun `translate wraps text and keeps original body intact`() {
        val text = "Keep `code` intact: a=b"
        val prompt = wrapSharePrompt(ShareQuickAction.TRANSLATE_ZH, text)
        assertTrue(prompt.contains("翻译成中文"))
        assertTrue(prompt.endsWith("---\n$text\n---"))
    }

    @Test
    fun `save to sandbox asks for workspace path without altering content`() {
        val prompt = wrapSharePrompt(ShareQuickAction.SAVE_TO_SANDBOX, "# Title")
        assertTrue(prompt.contains("/workspace/shared/"))
        assertTrue(prompt.contains("---\n# Title\n---"))
    }

    @Test
    fun `multiline shared text is preserved verbatim inside fences`() {
        val text = "第一行\n第二行 with 123\n\ttab indent"
        val prompt = wrapSharePrompt(ShareQuickAction.SUMMARIZE, text)
        assertTrue(prompt.contains("\n$text\n---"))
    }

    @Test
    fun `each action yields a distinct prompt`() {
        val text = "shared"
        val prompts = ShareQuickAction.entries.map { wrapSharePrompt(it, text) }
        assertEquals(prompts.size, prompts.distinct().size)
    }
}
