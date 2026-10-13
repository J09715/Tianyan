package top.tianyan.app.harness.prompt

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentSkill

/**
 * 技能渐进式披露的缓存安全守卫（对齐天枢 `skill-cache-safety.test.ts`）。
 *
 * ## 守的是什么
 *
 * 技能正文长度**没有上限**（用户可导入任意大小的 SKILL.md）。若逐轮把正文
 * 整段注入，会有两个后果：
 *
 * 1. 每轮都为当轮用不到的技能付 token —— 会话越长越贵；
 * 2. 一旦超出上下文预算被截断，模型拿到的是**残缺的规则**却不自知，
 *    比没有规则更危险（它会以为自己看到了完整流程）。
 *
 * 因此分两层：发现块只给 id + 摘要（Tier 1），正文用 `load_rule("skill:<id>")`
 * 按需读取（Tier 2）。天枢的原话是这套设计取代了旧的
 * "inject full body of every matched skill every turn"。
 *
 * ## 为什么必须用真实函数而不是源码断言
 *
 * 我先前在提示词分层的守卫上栽过一次：断言"某个名字有没有写在某个 list 里"
 * 是源码文本匹配，改错位置它照样全绿。这里改为直接调用
 * [SystemPromptBuilder.buildSkillDiscoverySection] 检查**产出内容**。
 */
class SkillProgressiveDisclosureTest {

    /** 一个正文很大的技能：若被整段注入，测试能明确抓到。 */
    private val bodyMarker = "UNIQUE_SKILL_BODY_MARKER_"
    private fun skill(
        id: String,
        name: String,
        desc: String,
        bodySize: Int = 4_000,
    ) = AgentSkill(
        id = id,
        name = name,
        description = desc,
        systemPrompt = bodyMarker + "x".repeat(bodySize),
        category = "测试",
    )

    private fun build(vararg skills: AgentSkill, hint: String = "") =
        SystemPromptBuilder.buildSkillDiscoverySection(skills.toList(), hint)

    @Test
    fun `discovery block carries the digest but never the skill body`() {
        val section = build(skill("deploy", "deployer", "如何部署到生产环境"), hint = "帮我 deploy")
        assertTrue("摘要应出现", section.contains("如何部署"))
        assertTrue("应带 id 供按需读取", section.contains("deploy"))
        assertFalse(
            "技能正文绝不能出现在发现块里 —— 正文由 load_rule 按需读取",
            section.contains(bodyMarker),
        )
    }

    @Test
    fun `discovery block teaches the model how to fetch the body`() {
        val section = build(skill("deploy", "deployer", "如何部署"))
        assertTrue(
            "必须告知用 load_rule 读正文，否则模型只会凭摘要臆测",
            section.contains("load_rule"),
        )
        assertTrue("必须给出 skill:<id> 这个 key 形态", section.contains("skill:"))
    }

    @Test
    fun `long descriptions are truncated so one skill cannot eat the whole budget`() {
        val hugeDesc = "很长的摘要".repeat(500)
        val section = build(skill("a", "alpha", hugeDesc))
        assertTrue(
            "单条摘要必须被截断（否则一个超长 description 吃掉全部预算）",
            section.length < 2_000,
        )
    }

    @Test
    fun `total discovery budget is bounded across many skills`() {
        val many = (1..50).map { skill("s$it", "skill$it", "摘要$it", bodySize = 100) }
        val section = SystemPromptBuilder.buildSkillDiscoverySection(many, "")
        assertTrue(
            "50 个技能的发现块必须受总预算约束，实际 ${section.length} 字符",
            section.length <= 2_000,
        )
    }

    @Test
    fun `mentioned skill is ordered first so budget pressure keeps it`() {
        val many = (1..40).map { skill("s$it", "skill$it", "摘要$it", bodySize = 100) }
        val target = skill("target", "needle", "关键技能")
        val section = SystemPromptBuilder.buildSkillDiscoverySection(many + target, "用 needle 处理")
        val needleAt = section.indexOf("needle")
        val otherAt = section.indexOf("skill20")
        assertTrue("被提及的技能必须出现在发现块里", needleAt >= 0)
        if (otherAt >= 0) {
            assertTrue("被提及的技能应排在前面（预算不足时先保住它）", needleAt < otherAt)
        }
        assertTrue("相关技能应带 relevant 标记", section.contains("relevant=\"true\""))
    }

    @Test
    fun `empty skill list produces no section`() {
        assertTrue(
            "没有技能时不应产生任何字节（否则每轮多出一段空标题）",
            SystemPromptBuilder.buildSkillDiscoverySection(emptyList(), "任意").isEmpty(),
        )
    }

    /**
     * 源码契约：load_rule 必须支持 skill: 前缀，否则策略披露会让技能**不可达**
     * —— 正文不注入又取不回来，等于把技能禁用了。
     */
    @Test
    fun `loadRule supports the skill prefix so bodies stay reachable`() {
        val router = File("src/main/java/top/tianyan/app/harness/prompt/PromptRouter.kt").readText()
        assertTrue("PromptRouter 必须定义 SKILL_PREFIX", router.contains("SKILL_PREFIX"))
        assertTrue(
            "loadRule 必须能按 skill: 前缀取技能正文，否则渐进披露会让技能不可达",
            router.contains("startsWith(SKILL_PREFIX"),
        )
    }

    /** 工具描述必须告诉模型 skill:<id> 这个用法，否则它不会去读正文。 */
    @Test
    fun `load_rule tool description advertises the skill key form`() {
        val provider = File("src/main/java/top/tianyan/app/harness/ProviderClient.kt").readText()
        val idx = provider.indexOf("name = \"load_rule\"")
        assertTrue("找不到 load_rule 的 schema", idx >= 0)
        val desc = provider.substring(idx, minOf(provider.length, idx + 900))
        assertTrue("load_rule 描述里必须写明 skill:<id> 用法", desc.contains("skill:<id>"))
    }
}
