package top.tianyan.app.harness.compaction

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

/** ConversationText 序列化与文件操作提取（对齐 pi serializeConversation 契约）。 */
class ConversationTextTest {

    @Test
    fun `serializes conversation into labeled narrative lines`() {
        val messages: List<HarnessMessage> = listOf(
            UserMessage("u1", 1, "请检查这个文件"),
            AssistantText("a1", 2, "好的，我先读取", reasoning = "需要先看文件内容"),
            ToolCall("t1", 3, HarnessTool.READ, buildJsonObject { put("path", "/workspace/Main.kt") }),
            ToolResult("r1", 4, "t1", true, "fun main() {}"),
            AssistantText("a2", 5, "文件内容正常"),
        )

        val text = ConversationText.serialize(messages)

        assertTrue(text.contains("[User]: 请检查这个文件"))
        assertTrue(text.contains("[Assistant thinking]: 需要先看文件内容"))
        assertTrue(text.contains("[Assistant]: 好的，我先读取"))
        assertTrue(text.contains("""[Assistant tool calls]: read(path="/workspace/Main.kt")"""))
        assertTrue(text.contains("[Tool result](read): fun main() {}"))
        assertTrue(text.contains("[Assistant]: 文件内容正常"))
    }

    @Test
    fun `serialize honors the per-model maxChars cap`() {
        val messages: List<HarnessMessage> = (0 until 200).map { index ->
            UserMessage("u-$index", index.toLong(), "用户消息内容-$index。" + "x".repeat(500))
        }

        val capped = ConversationText.serialize(messages, maxChars = 5_000)

        assertTrue("超上限必须触发头尾截断", capped.contains("…[中段"))
        assertTrue(capped.length <= 5_000 + 200)
        assertTrue("头部（初始目标）保留", capped.contains("用户消息内容-0。"))
        assertTrue("尾部（最新进展）保留", capped.contains("用户消息内容-199。"))
        // 不传上限时保持全量口径，不触发截断
        val full = ConversationText.serialize(messages)
        assertTrue(full.length > 5_000)
        assertFalse(full.contains("…[中段"))
    }

    @Test
    fun `merges consecutive tool calls into one line`() {
        val messages: List<HarnessMessage> = listOf(
            UserMessage("u1", 1, "go"),
            ToolCall("t1", 2, HarnessTool.READ, buildJsonObject { put("path", "a.kt") }),
            ToolCall("t2", 3, HarnessTool.WRITE, buildJsonObject { put("path", "b.kt") }),
            ToolResult("r1", 4, "t1", true, "ok1"),
            ToolResult("r2", 5, "t2", true, "ok2"),
        )

        val text = ConversationText.serialize(messages)

        assertTrue(text.contains("""read(path="a.kt"); write(path="b.kt")"""))
    }

    @Test
    fun `tool results are truncated with an omission marker`() {
        val longOutput = "x".repeat(5_000)
        val messages: List<HarnessMessage> = listOf(
            ToolCall("t1", 1, HarnessTool.BASE, buildJsonObject { put("command", "cat big.log") }),
            ToolResult("r1", 2, "t1", true, longOutput),
        )

        val text = ConversationText.serialize(messages)

        assertFalse(text.contains(longOutput))
        assertTrue(text.contains("已截断，省略 ${5_000 - ConversationText.TOOL_RESULT_CHAR_LIMIT} 字符"))
    }

    @Test
    fun `raw tool names take precedence in serialization`() {
        val messages: List<HarnessMessage> = listOf(
            ToolCall("t1", 1, HarnessTool.MCP, buildJsonObject { put("path", "x") }, rawToolName = "mcp__browser__screenshot"),
            ToolResult("r1", 2, "t1", true, "png"),
        )

        val text = ConversationText.serialize(messages)

        assertTrue(text.contains("mcp__browser__screenshot"))
    }

    @Test
    fun `extracts cumulative file operations from tool calls`() {
        val messages: List<HarnessMessage> = listOf(
            ToolCall("t1", 1, HarnessTool.READ, buildJsonObject { put("path", "/w/a.kt") }),
            ToolCall("t2", 2, HarnessTool.WRITE, buildJsonObject { put("path", "/w/b.kt") }),
            ToolCall("t3", 3, HarnessTool.EDIT, buildJsonObject { put("path", "/w/b.kt") }),
            ToolCall("t4", 4, HarnessTool.READ, buildJsonObject { put("path", "/w/a.kt") }),
        )

        val ops = FileOperations.extractFrom(messages)

        assertEquals(listOf("/w/a.kt"), ops.readFiles)
        assertEquals(listOf("/w/b.kt"), ops.modifiedFiles)
        assertTrue(ops.renderTags().contains("<read-files>"))
        assertTrue(ops.renderTags().contains("<modified-files>"))
    }

    @Test
    fun `parses previous summary file tags and merges cumulatively`() {
        val previous = """
            ## 目标
            完成
            <read-files>
            /w/old.kt
            </read-files>
            <modified-files>
            /w/old2.kt
            </modified-files>
        """.trimIndent()
        val previousOps = FileOperations.parseFromSummary(previous)
        val freshOps = FileOperations.extractFrom(
            listOf(ToolCall("t1", 1, HarnessTool.READ, buildJsonObject { put("path", "/w/new.kt") })),
        )

        val merged = previousOps.mergedWith(freshOps)

        assertEquals(listOf("/w/old.kt", "/w/new.kt"), merged.readFiles)
        assertEquals(listOf("/w/old2.kt"), merged.modifiedFiles)
    }

    @Test
    fun `empty file operations render no tags`() {
        assertTrue(FileOperations().renderTags().isEmpty())
    }

    @Test
    fun `oversized serialization keeps head and tail with omission marker`() {
        val messages = buildList<HarnessMessage> {
            repeat(400) { index ->
                add(UserMessage("u-$index", index.toLong(), "msg-$index " + "y".repeat(1_000)))
            }
        }

        val text = ConversationText.serialize(messages)

        assertTrue(text.length <= ConversationText.MAX_SERIALIZED_CHARS + 200)
        assertTrue(text.contains("已省略"))
        assertTrue(text.contains("msg-0"))
        assertTrue(text.contains("msg-399"))
    }
}
