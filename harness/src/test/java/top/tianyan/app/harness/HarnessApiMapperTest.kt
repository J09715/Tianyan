package top.tianyan.app.harness

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessApiMapperTest {

    @Test
    fun `user message maps to user role`() {
        val api = HarnessApiMapper.toApiMessage(UserMessage("u", 1L, "hello"))
        assertEquals("user", api.role)
        assertEquals("hello", api.content)
    }

    @Test
    fun `assistant text maps to assistant role`() {
        val api = HarnessApiMapper.toApiMessage(AssistantText("a", 1L, "done"))
        assertEquals("assistant", api.role)
        assertEquals("done", api.content)
    }

    @Test
    fun `tool call maps to assistant tool_calls with function name`() {
        val call = ToolCall(
            id = "c1",
            createdAt = 1L,
            tool = HarnessTool.READ,
            args = buildJsonObject { put("path", "x.txt") },
        )
        val api = HarnessApiMapper.toApiMessage(call)
        assertEquals("assistant", api.role)
        val toolCall = api.tool_calls!!.single()
        assertEquals("c1", toolCall.id)
        assertEquals("read", toolCall.function.name)
        assertEquals("""{"path":"x.txt"}""", toolCall.function.arguments)
    }

    @Test
    fun `tool result maps to tool role with tool_call_id`() {
        val result = ToolResult("r", 1L, "c1", success = false, output = "boom")
        val api = HarnessApiMapper.toApiMessage(result)
        assertEquals("tool", api.role)
        assertEquals("c1", api.tool_call_id)
        assertEquals("boom", api.content)
    }

    @Test
    fun `tool name mapping`() {
        assertEquals(HarnessTool.READ, HarnessApiMapper.toolByName("read"))
        assertEquals(HarnessTool.WRITE, HarnessApiMapper.toolByName("Write"))
        assertEquals(HarnessTool.EDIT, HarnessApiMapper.toolByName("edit"))
        assertEquals(HarnessTool.DOWNLOAD, HarnessApiMapper.toolByName("download"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("base"))
        assertEquals(HarnessTool.PROCESS, HarnessApiMapper.toolByName("process"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("execute"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("invoke_subagent"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("subagent"))
        assertEquals(HarnessTool.SUBAGENT, HarnessApiMapper.toolByName("invoke_dual_agent"))
        assertEquals(HarnessTool.BUILD_SCRIPT, HarnessApiMapper.toolByName("build_script"))
        assertEquals(HarnessTool.MCP, HarnessApiMapper.toolByName("mcp__sqlite__read_query"))
        assertEquals(HarnessTool.BASE, HarnessApiMapper.toolByName("unknown_tool"))
    }

    @Test
    fun `user message with attachments renders mount manifest for the model`() {
        val message = UserMessage(
            id = "u",
            createdAt = 1L,
            text = "看看这个",
            attachments = listOf(
                MessageAttachment(
                    name = "a.pdf",
                    isImage = false,
                    sizeBytes = 593 * 1024,
                    guestPath = "/attachments/1_a.pdf",
                ),
                MessageAttachment(
                    name = "b.jpg",
                    isImage = true,
                    sizeBytes = 900,
                    guestPath = "/attachments/2_b.jpg",
                ),
            ),
        )
        val content = requireNotNull(HarnessApiMapper.toApiMessage(message).content)
        assertTrue(content.startsWith("看看这个"))
        assertTrue(content.contains("[附件：已复制并挂载到 Linux 沙箱]"))
        assertTrue(content.contains("1. [文件] a.pdf (593 KB) -> Linux 沙箱绝对路径: /attachments/1_a.pdf"))
        assertTrue(content.contains("2. [图片] b.jpg (900 B) -> Linux 沙箱绝对路径: /attachments/2_b.jpg"))
        assertTrue(content.contains("请使用 base 在上述绝对路径读取"))
    }

    @Test
    fun `attachment without guest path falls back to name based path`() {
        val manifest = HarnessApiMapper.renderAttachmentManifest(
            listOf(MessageAttachment(name = "x.zip", isImage = false, sizeBytes = 2_000_000)),
        )
        assertTrue(manifest.contains("/attachments/x.zip"))
        assertTrue(manifest.contains("(1.9 MB)"))
    }

    @Test
    fun `message without attachments keeps byte identical content`() {
        assertEquals("hi", HarnessApiMapper.renderUserContent("hi", ""))
        assertEquals("hi\n\ntail", HarnessApiMapper.renderUserContent("hi", "tail"))
    }

    @Test
    fun `attachment manifest is byte stable across renders`() {
        val attachments = listOf(
            MessageAttachment(name = "a.pdf", isImage = false, sizeBytes = 1024, guestPath = "/attachments/a.pdf"),
        )
        val first = HarnessApiMapper.renderUserContent("hi", "", attachments)
        val second = HarnessApiMapper.renderUserContent("hi", "", attachments)
        assertEquals(first, second)
    }

    @Test
    fun `image-only pending message gets a durable prompt`() {
        val description = durableTaskDescription(
            PendingMessage(
                text = "",
                imageUrls = listOf("data:image/png;base64,abc"),
            ),
        )

        assertTrue(description.isNotBlank())
        assertEquals("请分析附带的图片。", description)
    }

    @Test
    fun `attachment-only pending message keeps a recoverable manifest`() {
        val description = durableTaskDescription(
            PendingMessage(
                text = " ",
                attachments = listOf(
                    MessageAttachment(
                        name = "photo.jpg",
                        isImage = true,
                        sizeBytes = 12,
                        guestPath = "/attachments/photo.jpg",
                    ),
                ),
            ),
        )

        assertTrue(description.contains("photo.jpg"))
        assertTrue(description.isNotBlank())
    }
}
