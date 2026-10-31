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

    // ---------- 附件：正文保持干净，清单只在 API 层展开 ----------

    @Test
    fun `attachment manifest is rendered for the model but not baked into message text`() {
        val attachment = MessageAttachment(
            name = "scan.zip",
            isImage = false,
            sizeBytes = 2048,
            guestPath = "/attachments/scan.zip",
        )
        val message = UserMessage("u", 1L, "看看这个", attachments = listOf(attachment))

        // 存储侧：正文仍是用户原话，气泡据此渲染纯文本 + 文件卡片
        assertEquals("看看这个", message.text)

        // API 侧：清单被展开，模型才知道文件挂在哪
        val api = HarnessApiMapper.toApiMessage(message)
        assertEquals("user", api.role)
        assertTrue(api.content!!.startsWith("看看这个"))
        assertTrue(api.content!!.contains("scan.zip"))
        assertTrue(api.content!!.contains("/attachments/scan.zip"))
        assertTrue(api.content!!.contains("Linux 沙箱绝对路径"))
    }

    @Test
    fun `message without attachments keeps content byte identical`() {
        val plain = UserMessage("u", 1L, "hello")
        assertEquals("hello", HarnessApiMapper.toApiMessage(plain).content)
    }

    @Test
    fun `manifest rendering is a pure function for prefix stability`() {
        val attachments = listOf(
            MessageAttachment("a.txt", false, 10, "/attachments/a.txt"),
            MessageAttachment("b.png", true, 20, "/attachments/b.png"),
        )
        // 同样输入必须产出同样字节，否则前缀缓存全量失效
        assertEquals(
            HarnessApiMapper.renderAttachmentManifest(attachments),
            HarnessApiMapper.renderAttachmentManifest(attachments),
        )
    }

    @Test
    fun `blank guest path falls back to canonical attachments dir`() {
        val text = HarnessApiMapper.renderAttachmentManifest(
            listOf(MessageAttachment("x.log", false, 1, guestPath = "")),
        )
        assertTrue(text.contains("/attachments/x.log"))
    }
}
