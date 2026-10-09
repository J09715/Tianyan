package top.tianyan.app.harness.skill

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.UserMessage

/**
 * SkillDistillationManager 纯函数单测：
 * 提炼素材组装 / JSON 围栏剥离 / 触发命令规范化 / 候选数据模型序列化。
 * （distill 完整链路依赖 ProviderClient（final class，LLM 网络 IO），不在 JVM 单测范围。）
 */
class SkillDistillationManagerTest {

    private fun user(id: String, text: String) = UserMessage(id = id, createdAt = 1L, text = text)

    private fun assistant(id: String, text: String) = AssistantText(id = id, createdAt = 1L, text = text)

    private fun toolCall(id: String, tool: HarnessTool = HarnessTool.READ, rawToolName: String? = null) =
        ToolCall(id = id, createdAt = 1L, tool = tool, args = buildJsonObject {}, rawToolName = rawToolName)

    private fun String.countMatches(needle: String): Int {
        var count = 0
        var index = indexOf(needle)
        while (index >= 0) {
            count++
            index = indexOf(needle, index + needle.length)
        }
        return count
    }

    // ==================== buildDistillMaterial ====================

    @Test
    fun `material is blank when no user messages`() {
        val material = SkillDistillationManager.buildDistillMaterial(
            listOf(assistant("a1", "只有助手消息"), toolCall("t1")),
        )
        assertTrue("无用户消息时素材应为空串", material.isEmpty())
    }

    @Test
    fun `material contains user text assistant text and tool names`() {
        val material = SkillDistillationManager.buildDistillMaterial(
            listOf(
                user("u1", "帮我部署服务"),
                toolCall("t1", HarnessTool.BASE),
                toolCall("t2", HarnessTool.MCP, rawToolName = "mcp__browser__navigate"),
                assistant("a1", "部署完成"),
            ),
        )
        assertTrue("素材应包含用户消息原文", material.contains("帮我部署服务"))
        assertTrue("素材应包含助手回复原文", material.contains("部署完成"))
        assertTrue("内置工具应按枚举名小写呈现", material.contains("base"))
        assertTrue("MCP 工具应优先用原始名", material.contains("mcp__browser__navigate"))
        assertTrue("工具列表应去重", material.countMatches("mcp__browser__navigate") == 1)
    }

    @Test
    fun `long user message is truncated to snippet limit`() {
        val longText = "x".repeat(2000)
        val material = SkillDistillationManager.buildDistillMaterial(
            listOf(user("u1", longText)),
        )
        // 用户消息正文截断到 600 字符（素材总长含固定标题文案，必然小于 2000 + 标题）
        assertTrue("超长消息应被截断", material.length < 800)
        assertTrue("截断后仍应保留素材标题", material.contains("【用户消息"))
    }

    // ==================== extractJson ====================

    @Test
    fun `extractJson strips markdown code fence`() {
        val raw = "```json\n{\"worthLearning\": true, \"skills\": []}\n```"
        assertEquals("{\"worthLearning\": true, \"skills\": []}", SkillDistillationManager.extractJson(raw))
    }

    @Test
    fun `extractJson extracts body from noisy surrounding text`() {
        val raw = "好的，分析结果如下：{\"worthLearning\": false, \"reason\": \"闲聊\"} 以上。"
        assertEquals("{\"worthLearning\": false, \"reason\": \"闲聊\"}", SkillDistillationManager.extractJson(raw))
    }

    @Test(expected = IllegalStateException::class)
    fun `extractJson throws when no json body present`() {
        SkillDistillationManager.extractJson("抱歉，我无法输出 JSON。")
    }

    // ==================== normalizeCommand ====================

    @Test
    fun `normalizeCommand handles blank slash and bare word`() {
        assertNull(SkillDistillationManager.normalizeCommand(""))
        assertNull(SkillDistillationManager.normalizeCommand("   "))
        assertEquals("/deploy", SkillDistillationManager.normalizeCommand("deploy"))
        assertEquals("/deploy", SkillDistillationManager.normalizeCommand(" /deploy "))
        assertEquals("/git-push", SkillDistillationManager.normalizeCommand("/git-push"))
    }

    // ==================== PendingSkillCandidate 序列化 ====================

    @Test
    fun `pending skill candidate survives json roundtrip`() {
        val json = Json { ignoreUnknownKeys = true }
        val candidate = PendingSkillCandidate(
            name = "发布技能",
            description = "一键发布版本",
            systemPrompt = "按以下步骤发布：1. 跑测试 2. 打 tag 3. 推送",
            triggerCommand = "/release",
            sessionId = "sess-1",
            createdAt = 42L,
        )
        val decoded = json.decodeFromString<PendingSkillCandidate>(json.encodeToString(PendingSkillCandidate.serializer(), candidate))
        assertEquals(candidate, decoded)
    }
}
