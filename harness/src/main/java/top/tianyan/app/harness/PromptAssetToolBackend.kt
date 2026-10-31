package top.tianyan.app.harness

import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.harness.core.ToolBackend
import top.tianyan.app.harness.prompt.PromptRouter
import top.tianyan.app.harness.skill.SkillResourceReader

data class PromptAssetRequest(
    val tool: HarnessTool,       // LOAD_SKILL / LOAD_RULE
    val args: JsonObject,
)

/**
 * Agent 工具后端：按需加载提示资产（load_skill / load_rule）。
 *
 * 技能目录（元数据）常驻系统提示，命中后由模型主动拉取完整正文与子资源；
 * 规则块由 [PromptRouter] 按名加载。避免全部正文常驻撑爆上下文。
 */
class PromptAssetToolBackend(
    private val skillRepository: AgentSkillRepository? = null,
    private val promptRouter: PromptRouter? = null,
) : ToolBackend<PromptAssetRequest, Pair<Boolean, String>> {

    override suspend fun execute(request: PromptAssetRequest): Pair<Boolean, String> = when (request.tool) {
        HarnessTool.LOAD_SKILL -> loadSkill(request.args)
        HarnessTool.LOAD_RULE -> loadRule(request.args)
        else -> throw IllegalArgumentException("Unsupported tool: ${request.tool}")
    }

    private suspend fun loadSkill(args: JsonObject): Pair<Boolean, String> {
        // 按需技能加载：目录（元数据）常驻系统提示，命中后由模型主动拉取
        // 完整指导规则，避免全部正文常驻撑爆上下文。
        val query = JsonArgs.requireString(args, "name").trim().trimStart('/')
        val skills = skillRepository?.activeSkills?.first().orEmpty()
        if (skills.isEmpty()) {
            return false to "当前没有已启用的技能。请提示用户到「设置 → 智能体」启用技能后重试。"
        }
        val queryLower = query.lowercase()
        // 精确匹配（name / id / 去斜杠 triggerCommand）：大小写无关。
        val exact = skills.filter { skill ->
            val candidates = setOf(
                skill.name.lowercase(),
                skill.id.lowercase(),
                skill.triggerCommand?.removePrefix("/")?.lowercase().orEmpty(),
            )
            queryLower in candidates
        }
        // 模糊匹配仅作兜底，且必须唯一命中——若命中多条还静默取第一条，
        // 会出现「load_skill("Git") 却加载了 Git 敏捷工作流」这类选错技能的问题。
        val fuzzy = if (exact.isEmpty()) {
            skills.filter { skill ->
                skill.name.lowercase().contains(queryLower) ||
                    (skill.triggerCommand?.removePrefix("/")?.lowercase()?.contains(queryLower) == true)
            }
        } else {
            emptyList()
        }
        val pool = if (exact.isNotEmpty()) exact else fuzzy
        return when {
            pool.isEmpty() -> false to "未找到匹配的技能：$query。可用技能：" +
                skills.joinToString("、") { it.name }
            pool.size > 1 -> false to "技能名 $query 匹配到多个技能：" +
                pool.joinToString("、") { it.name } + "。请使用完整技能名或 id 重试。"
            else -> {
                val matched = pool.first()
                val subPath = JsonArgs.optionalString(args, "path")?.trim()
                if (subPath.isNullOrEmpty()) {
                    true to "【技能已加载：${matched.name}】(category=${matched.category})\n" +
                        SkillResourceReader.stripFrontmatter(matched.systemPrompt).trim()
                } else {
                    val resourceDir = matched.resourcePath?.takeIf { it.isNotBlank() }
                    if (resourceDir == null) {
                        false to "技能「${matched.name}」没有可读取的资源目录（resourcePath 为空），无法读取 $subPath。" +
                            "请改用包含 SKILL.md 的目录形式导入该技能后再读取子资源。"
                    } else {
                        val content = SkillResourceReader.readSubResource(File(resourceDir), subPath)
                        if (content == null) {
                            false to "技能资源不存在或路径越界：$subPath。" +
                                "路径须为技能目录内的相对路径（如 references/foo.md / scripts/bar.sh）。"
                        } else {
                            true to "【技能资源：${matched.name}/$subPath】\n$content"
                        }
                    }
                }
            }
        }
    }

    private fun loadRule(args: JsonObject): Pair<Boolean, String> {
        val rule = JsonArgs.requireString(args, "rule")
        val content = promptRouter?.loadRule(rule)
        return if (content != null) {
            true to "【规则块：$rule】\n$content"
        } else {
            // 名单从 PromptRouter 动态生成：硬编码清单会随规则块增删漂移（曾漏 image-delivery/browser-reverse）。
            val available = promptRouter?.availableRuleNames()
                ?: "workflow / code-navigation / security / memory / environment-proot / tools"
            false to "未知规则块：$rule。可用：$available"
        }
    }
}