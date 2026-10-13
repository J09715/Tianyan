package top.tianyan.app.harness.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentSkill

/**
 * 场景自动匹配（Skill Scene Matching）守卫。
 *
 * 背景：旧的技能加载只认「@ 提及」，不点名就完全不注入技能。这里改成
 * 根据当轮用户消息与技能名的关键词重叠度自动召回，让用户不必手动 @ 也能
 * 加载相关技能。匹配是纯函数（无 Android 依赖），可直接单测。
 */
class SkillSceneMatchingTest {

    private fun skill(id: String, name: String) = AgentSkill(
        id = id,
        name = name,
        description = "d",
        systemPrompt = "",
        category = "测试",
    )

    private fun match(vararg skills: AgentSkill, msg: String) =
        SystemPromptBuilder.matchByScene(skills.toList(), msg).map { it.id }

    // ── 关键词提取 ────────────────────────────────────────────────

    @Test
    fun `extract strips action prefix and connectors`() {
        assertEquals(
            listOf("android", "malware", "apktool"),
            SystemPromptBuilder.extractSceneKeywords("analyzing-android-malware-with-apktool"),
        )
    }

    @Test
    fun `extract skips consecutive action prefixes`() {
        // post-exploiting 里 exploiting 紧跟在后，也要一起跳过
        assertEquals(
            listOf("microsoft", "graph", "graphrunner"),
            SystemPromptBuilder.extractSceneKeywords("post-exploiting-microsoft-graph-with-graphrunner"),
        )
    }

    @Test
    fun `extract keeps mid-name action word as keyword`() {
        // reverse 在中间是实义词（nginx reverse proxy），不能当动作前缀剥掉
        assertEquals(
            listOf("nginx", "reverse", "proxy"),
            SystemPromptBuilder.extractSceneKeywords("configuring-nginx-reverse-proxy"),
        )
    }

    // ── 英文技能场景匹配 ─────────────────────────────────────────

    @Test
    fun `distinctive long word matches english skill`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "analyzing-android-malware-with-apktool"), msg = "用 apktool 分析恶意软件"),
        )
    }

    @Test
    fun `adjacent keyword pair matches english skill`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "analyzing-android-malware-with-apktool"), msg = "android malware analysis"),
        )
    }

    @Test
    fun `generic word alone does not match`() {
        assertTrue(
            match(skill("s1", "implementing-api-security-testing"), msg = "security").isEmpty(),
        )
    }

    @Test
    fun `short abbreviation matches via word boundary`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "exploiting-sql-injection-with-sqlmap"), msg = "sql"),
        )
        // 词边界：special 里的 "ci" 不应触发 ci 技能
        assertTrue(
            match(skill("ci", "building-ci-cd-pipeline"), msg = "special").isEmpty(),
        )
    }

    // ── 中文技能场景匹配 ─────────────────────────────────────────

    @Test
    fun `chinese skill matched by abbreviation plus cn bigram`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "ADB设备安全审计"), msg = "adb 审计"),
        )
    }

    @Test
    fun `chinese skill matched by cn bigram`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "附件消息崩溃修复"), msg = "崩溃"),
        )
    }

    // ── 中英别名 ─────────────────────────────────────────────────

    @Test
    fun `cn en alias recalls english skill`() {
        assertEquals(
            listOf("s1"),
            match(skill("s1", "exploiting-sql-injection-vulnerabilities"), msg = "帮我测试 SQL 注入"),
        )
    }

    // ── 相关度排序 / 边界 ────────────────────────────────────────

    @Test
    fun `matched skills ordered by relevance score`() {
        val skills = listOf(
            skill("weak", "analyzing-sql-injection-in-logs"),
            skill("strong", "exploiting-sql-injection-with-sqlmap"),
        )
        val matched = SystemPromptBuilder.matchByScene(skills, "sql injection sqlmap")
        assertEquals(listOf("strong", "weak"), matched.map { it.id })
    }

    @Test
    fun `blank message matches nothing`() {
        assertTrue(match(skill("s1", "analyzing-android-malware-with-apktool"), msg = "   ").isEmpty())
    }
}
