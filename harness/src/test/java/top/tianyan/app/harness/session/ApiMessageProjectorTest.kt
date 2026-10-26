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
}