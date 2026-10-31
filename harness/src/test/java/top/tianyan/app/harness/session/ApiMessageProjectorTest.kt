package top.tianyan.app.harness.session

import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolCallMode
import top.tianyan.app.harness.SkillSuggestion
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.UserMessage

class ApiMessageProjectorTest {

    private fun messages() = listOf(
        UserMessage(id = "u1", createdAt = 1, text = "看看这张图"),
        ToolCall(id = "c1", createdAt = 2, tool = HarnessTool.READ, args = buildJsonObject {}),
        ToolResult(
            id = "r1",
            createdAt = 3,
            toolCallId = "c1",
            success = true,
            output = "已读取图片文件 chart.png",
            imageDataUrl = "data:image/png;base64,AAAA",
        ),
    )

    @Test
    fun `ui only skill suggestion is not projected to the provider`() {
        val projected = ApiMessageProjector.project(
            listOf(
                UserMessage(id = "u1", createdAt = 1L, text = "hi"),
                SkillSuggestion(
                    id = "s1",
                    createdAt = 2L,
                    action = "create",
                    skillName = "demo",
                    description = "demo skill",
                    systemPrompt = "demo prompt",
                ),
            ),
            toolCallMode = ToolCallMode.NATIVE,
            visionEnabled = true,
        )
        assertEquals(listOf("user"), projected.map { it.role })
    }

    @Test
    fun `vision bridge appends image message when vision enabled`() {
        val projected = ApiMessageProjector.project(messages(), ToolCallMode.NATIVE, visionEnabled = true)
        val imageMessage = projected.last()
        assertEquals("user", imageMessage.role)
        assertEquals(listOf("data:image/png;base64,AAAA"), imageMessage.imageUrls)
        assertTrue(projected.any { it.role == "tool" })
    }

    @Test
    fun `vision bridge keeps only the most recent tool images`() {
        val older = (1..3).map { index ->
            ToolResult(
                id = "r$index",
                createdAt = index.toLong(),
                toolCallId = "c$index",
                success = true,
                output = "截图 $index",
                imageDataUrl = "data:image/png;base64,IMG$index",
            )
        }
        val projected = ApiMessageProjector.project(older, ToolCallMode.NATIVE, visionEnabled = true)
        val images = projected.filter { it.imageUrls.isNotEmpty() }
        assertEquals(listOf("data:image/png;base64,IMG2", "data:image/png;base64,IMG3"), images.flatMap { it.imageUrls })
        assertTrue(projected.first { it.role == "tool" && it.tool_call_id == "c1" }.content.orEmpty().contains("未再次附上"))
        assertTrue(projected.first { it.role == "tool" && it.tool_call_id == "c3" }.content.orEmpty().contains("截图 3"))
    }

    @Test
    fun `vision bridge is skipped when vision disabled`() {
        val projected = ApiMessageProjector.project(messages(), ToolCallMode.NATIVE, visionEnabled = false)
        assertTrue(projected.none { it.imageUrls.isNotEmpty() })
    }

    @Test
    fun `user image history respects the request byte budget`() {
        // 每张 ~3MB data URL：三张共 9MB，超出 8MB 预算，只有最新的两张能留下。
        val payload = "data:image/png;base64," + "A".repeat(3 * 1024 * 1024)
        val history = (1..3).map { index ->
            UserMessage(id = "u$index", createdAt = index.toLong(), text = "看图 $index", imageUrls = listOf(payload))
        }

        val projected = ApiMessageProjector.project(history, ToolCallMode.NATIVE, visionEnabled = true)
        val withImages = projected.filter { it.imageUrls.isNotEmpty() }

        assertEquals(2, withImages.size)
        assertEquals(listOf("看图 2", "看图 3"), withImages.map { it.content })
        // 被挤掉的旧图换成一行说明，模型不会以为用户没发过图。
        val dropped = projected.first { it.role == "user" && it.content.orEmpty().startsWith("看图 1") }
        assertTrue(dropped.imageUrls.isEmpty())
        assertTrue(dropped.content.orEmpty().contains("未重新附上 1 张历史图片"))
    }

    @Test
    fun `memory only message carries no image budget note`() {
        val projected = ApiMessageProjector.project(
            listOf(UserMessage(id = "u1", createdAt = 1L, text = "纯文本", imageUrls = listOf("data:image/png;base64,AAAA"))),
            ToolCallMode.NATIVE,
            visionEnabled = true,
        )
        val user = projected.first { it.role == "user" }
        assertEquals(listOf("data:image/png;base64,AAAA"), user.imageUrls)
        assertEquals("纯文本", user.content)
    }
}