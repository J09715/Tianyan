package top.tianyan.app.harness.redteam

/**
 * 技能库的读写接缝：控制台「技能库」页签要能列出 / 读 / 存 / 删技能。
 *
 * 为什么不直接在协调器里用 `AgentSkillRepository`：协调器的单测不该被迫构造整套
 * Room + Hilt 装配。默认实现是 [Unsupported]，写操作会明确报「技能库未初始化」，
 * 而不是静默成功——静默成功的保存最恶劣，用户以为存上了，重启后什么都没有。
 *
 * 技能的「何时使用 / 角色」按上游 SKILL.md 的 frontmatter 语义存在正文头部，
 * 与目录自动发现（`AgentSkillEntity.registerDir`）读的是同一套元数据键。
 */
interface RedTeamSkillStore {

    data class Skill(
        val id: String,
        val name: String,
        val role: String?,
        val enabled: Boolean,
        val description: String,
        val whenToUse: String?,
        val body: String,
        val path: String?,
        val builtin: Boolean,
    )

    suspend fun list(): List<Skill>

    suspend fun read(id: String): Skill?

    suspend fun save(skill: Skill): Skill

    suspend fun delete(id: String): Boolean

    object Unsupported : RedTeamSkillStore {
        override suspend fun list(): List<Skill> = emptyList()

        override suspend fun read(id: String): Skill? = null

        override suspend fun save(skill: Skill): Skill = error("技能库未初始化，无法保存技能")

        override suspend fun delete(id: String): Boolean = false
    }

    companion object {
        /** 上游 frontmatter 的元数据键；读写共用，避免存进去的键读的时候对不上。 */
        const val KEY_NAME = "name"
        const val KEY_DESCRIPTION = "description"
        const val KEY_WHEN_TO_USE = "whenToUse"
        const val KEY_ROLE = "role"
        const val KEY_ENABLED = "enabled"

        /**
         * 把技能渲染成上游格式的 SKILL.md。
         *
         * 值里有换行会破坏 frontmatter（后面的键会被当成正文），所以压成单行；
         * 正文本身原样保留。
         */
        fun renderMarkdown(skill: Skill): String {
            fun line(key: String, value: String?) =
                value?.takeIf { it.isNotBlank() }?.replace(Regex("\\s*\\n\\s*"), " ")?.let { "$key: $it" }

            val head = listOfNotNull(
                line(KEY_NAME, skill.name),
                line(KEY_DESCRIPTION, skill.description),
                line(KEY_WHEN_TO_USE, skill.whenToUse),
                line(KEY_ROLE, skill.role),
                "${KEY_ENABLED}: ${skill.enabled}",
            )
            return buildString {
                appendLine("---")
                head.forEach { appendLine(it) }
                appendLine("---")
                appendLine()
                append(skill.body.trim())
            }
        }

        /** 从 SKILL.md 拆出 frontmatter 与正文；没有 frontmatter 时整篇当正文。 */
        fun splitMarkdown(markdown: String): Pair<Map<String, String>, String> {
            if (!markdown.startsWith("---")) return emptyMap<String, String>() to markdown
            val lines = markdown.lines()
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end < 0) return emptyMap<String, String>() to markdown
            val meta = lines.drop(1).take(end).mapNotNull { row ->
                val key = row.substringBefore(':', "").trim()
                if (key.isEmpty()) null else key to row.substringAfter(':', "").trim().trim('"', '\'')
            }.toMap()
            return meta to lines.drop(end + 2).joinToString("\n").trim()
        }
    }
}
