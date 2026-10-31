package top.tianyan.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.core.model.BuiltinSkills
import java.io.File
import java.util.UUID

@Entity(tableName = "agent_skills")
data class AgentSkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val triggerCommand: String?,
    val iconName: String,
    val isEnabled: Boolean,
    val isBuiltin: Boolean,
    val isImmutable: Boolean,
    val category: String,
    val resourcePath: String?,
)

@Dao
interface AgentSkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY isBuiltin DESC, name ASC")
    fun observeAll(): Flow<List<AgentSkillEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(skills: List<AgentSkillEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET isEnabled = :enabled WHERE id = :id AND isImmutable = 0")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM agent_skills WHERE id = :id AND isBuiltin = 0")
    suspend fun deleteCustom(id: String)
}

/** 可自动发现 Skill 的宿主目录与其在 PRoot 沙箱内的挂载前缀（如 /attachments/skills）。 */
data class SkillScanRoot(
    val hostDir: File,
    val guestPrefix: String,
)

class AgentSkillRepository(
    private val dao: AgentSkillDao,
) {
    val allSkills: Flow<List<AgentSkill>> = dao.observeAll().map { rows -> rows.map(AgentSkillEntity::toModel) }
    val activeSkills: Flow<List<AgentSkill>> = allSkills.map { skills -> skills.filter { it.isEnabled } }

    private val directorySyncMutex = Mutex()

    suspend fun ensureInitialized() {
        dao.insertAll(BuiltinSkills.presets.map(AgentSkill::toEntity))
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        ensureInitialized()
        dao.setEnabled(id, enabled)
    }

    suspend fun addCustom(skill: AgentSkill) {
        ensureInitialized()
        dao.upsert(skill.toEntity())
    }

    suspend fun deleteCustom(id: String) {
        ensureInitialized()
        dao.deleteCustom(id)
    }

    /**
     * 递归扫描各集合目录，把直属包含 SKILL.md / prompt.md 且尚未入库的
     * 目录自动注册为自定义 Skill。用户手动复制 Skill 文件夹（或把其他工具的整个
     * skills 目录整体复制）到 attachments/skills 或工作区 skills 目录即可被发现，
     * 无需通过 ZIP 逐个导入。按 resourcePath 去重，重复调用安全。
     *
     * 发现 Skill 入口后即停止向其内部扫描，子文档由主 Skill 按需读取。
     * 无主入口的集合目录（如 rikkahub / aicode 的整个 skills 目录）仍递归发现独立技能。
     * 同步移除旧版误注册的子资源条目，仅清理数据库记录，保留资源文件。
     *
     * @return 本次新注册的 Skill 列表
     */
    suspend fun syncFromDirectories(roots: List<SkillScanRoot>): List<AgentSkill> = directorySyncMutex.withLock {
        ensureInitialized()
        val existing = dao.observeAll().first()
        val knownPaths = existing.mapNotNull { it.resourcePath?.takeIf(String::isNotBlank) }
            .mapTo(mutableSetOf()) { stored ->
                runCatching { File(stored).canonicalPath }.getOrDefault(stored)
            }
        val imported = mutableListOf<AgentSkill>()
        // 重叠扫描根也按父包优先处理；已有 ZIP 包的包装目录同样作为资源边界。
        roots.flatMap { root -> SkillDirectoryDiscovery.discover(root.hostDir).map { root to it } }
            .sortedBy { (_, prompt) -> prompt.canonicalFile.toPath().nameCount }
            .forEach { (root, promptFile) ->
                val dir = promptFile.parentFile ?: return@forEach
                val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: return@forEach
                if (knownPaths.any { canonical == it || isNestedPath(canonical, it) && File(it).isDirectory }) return@forEach
                val relative = dir.relativeTo(root.hostDir).invariantSeparatorsPath
                val guestPath = root.guestPrefix.trimEnd('/') + if (relative.isEmpty()) "" else "/$relative"
                registerDir(dir, promptFile, guestPath, knownPaths, imported)
            }
        val scannedPaths = roots.map { it.hostDir.canonicalPath }
        existing.filterNot { it.isBuiltin }.forEach { skill ->
            val path = skill.resourcePath?.let { File(it).canonicalPath } ?: return@forEach
            if (scannedPaths.any { path == it || isNestedPath(path, it) } &&
                knownPaths.any { isNestedPath(path, it) && File(it).isDirectory }
            ) {
                dao.deleteCustom(skill.id)
            }
        }
        imported
    }

    private fun isNestedPath(path: String, parent: String) = path.startsWith(parent + File.separator)

    private suspend fun registerDir(dir: File, promptFile: File, guestPath: String, knownPaths: MutableSet<String>, imported: MutableList<AgentSkill>) {
        val markdown = runCatching { promptFile.readText().trim() }.getOrNull()
        if (markdown.isNullOrBlank()) return
        val metadata = parseFrontmatter(markdown)
        val skill = AgentSkill(
            id = "custom_" + UUID.randomUUID().toString().take(8),
            name = metadata["name"]?.let(::normalizeSkillName)
                ?: markdown.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: dir.name,
            description = metadata["description"]?.takeIf { it.isNotBlank() }
                ?.take(MAX_DESCRIPTION_CHARS) ?: "从目录自动发现的 Skill",
            systemPrompt = markdown + "\n\n【Skill 资源目录】$guestPath\n如需执行该 Skill 附带的脚本，请先检查脚本内容与参数，再从此目录调用。",
            isBuiltin = false,
            category = "自定义",
            resourcePath = dir.absolutePath,
        )
        dao.upsert(skill.toEntity())
        knownPaths += runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        imported += skill
    }

    companion object {
        /** Skill 目录的提示词文件名（小写），供导入与扫描逻辑统一判定。 */
        val SKILL_PROMPT_FILE_NAMES = setOf("skill.md", "prompt.md")

        /**
         * 标准 Skill 发现根（开箱即用兼容开源社区 Agent Skills 规范）：
         * - 共享附件区 `attachments/skills`（全局内置 / 手动导入）；
         * - 工作区根 `workspace/skills`；
         * - 工作区内项目根目录的 `.agents/skills/`（Claude Code / OpenMinis 等规范）。
         *
         * `.agents/skills` 最多在项目树中下探 [AGENT_SKILL_SCAN_DEPTH] 层，并跳过
         * `.git` / `node_modules` / `build` 等重型目录，避免在大型 monorepo 上做全树遍历。
         */
        fun standardScanRoots(attachmentsDir: File, workspaceDir: File): List<SkillScanRoot> = buildList {
            add(SkillScanRoot(File(attachmentsDir, "skills"), "/attachments/skills"))
            add(SkillScanRoot(File(workspaceDir, "skills"), "/workspace/skills"))
            findAgentSkillRoots(workspaceDir).forEach { (dir, guestPath) ->
                add(SkillScanRoot(dir, guestPath))
            }
        }

        private fun findAgentSkillRoots(workspaceDir: File): List<Pair<File, String>> {
            val roots = mutableListOf<Pair<File, String>>()
            fun visit(dir: File, depth: Int) {
                if (depth > AGENT_SKILL_SCAN_DEPTH) return
                val relative = dir.relativeTo(workspaceDir).invariantSeparatorsPath.takeIf { it != "." }.orEmpty()
                val guestBase = if (relative.isEmpty()) "/workspace" else "/workspace/$relative"
                val agentSkills = File(dir, ".agents/skills")
                if (agentSkills.isDirectory) {
                    roots += agentSkills to "$guestBase/.agents/skills"
                }
                dir.listFiles().orEmpty()
                    .filter { it.isDirectory && !it.name.startsWith(".") && it.name !in IGNORED_SCAN_DIRS }
                    .forEach { visit(it, depth + 1) }
            }
            if (workspaceDir.isDirectory) visit(workspaceDir, 1)
            return roots
        }

        private val IGNORED_SCAN_DIRS = setOf(
            "node_modules", "build", "dist", "out", "target", "vendor", "DerivedData",
        )
        private const val AGENT_SKILL_SCAN_DEPTH = 3

        private const val MAX_DESCRIPTION_CHARS = 1024

        /** 从 SKILL.md 的 YAML frontmatter 中提取 name / description 等元数据。 */
        fun extractSkillMetadata(markdown: String, key: String): String? =
            parseFrontmatter(markdown)[key.lowercase()]?.takeIf { it.isNotBlank() }

        /**
         * 形如 `key:` / `key: value` 的映射键（YAML 中普通标量不能以该形态续行）。
         * 用于终止普通多行标量的折叠，避免把 metadata 下的子键并进上一条描述。
         */
        private val NESTED_KEY_BOUNDARY = Regex("""[A-Za-z0-9_.\-]+\s*:(\s|$)""")

        /**
         * 解析 SKILL.md 的 YAML frontmatter 为扁平 key→value 映射。
         *
         * 对齐 Claude Code / OpenMinis 生态 SKILL.md 的常见形态：
         * - UTF-8 BOM 与 CRLF 行尾容错（Windows 记事本保存的文件此前整体解析失败）；
         * - key 大小写不敏感，值可带单/双引号；
         * - `description: >` / `|-` 等多行折叠块（官方技能模板常见写法，此前解析为空）；
         * - 未加引号值尾部的 `# 注释` 剥离；
         * - 未知字段（allowed-tools / license / metadata 等）安全忽略。
         */
        fun parseFrontmatter(markdown: String): Map<String, String> {
            var text = markdown
            if (text.startsWith("\uFEFF")) text = text.substring(1)
            if (!text.startsWith("---")) return emptyMap()
            val lines = text.lineSequence().drop(1)
                .takeWhile { it.trim() != "---" && it.trim() != "..." }
                .toList()
            val result = mutableMapOf<String, String>()
            var index = 0
            while (index < lines.size) {
                val trimmed = lines[index].trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains(':')) {
                    index++
                    continue
                }
                val key = trimmed.substringBefore(':').trim().lowercase()
                var value = trimmed.substringAfter(':').trim()
                index++
                // 折叠/字面块指示符（含 chomping 修饰：>-、|-、>+ 等）
                if (value.startsWith(">") || value.startsWith("|")) {
                    // 折叠/字面块标量：取后续缩进行并折叠为单行
                    val block = StringBuilder()
                    while (index < lines.size && (lines[index].startsWith(" ") || lines[index].startsWith("\t") || lines[index].isBlank())) {
                        block.append(' ').append(lines[index].trim())
                        index++
                    }
                    value = block.toString().trim()
                } else {
                    if (!value.startsWith("\"") && !value.startsWith("'")) {
                        val comment = value.indexOf(" #")
                        if (comment >= 0) value = value.substring(0, comment).trim()
                        // 普通多行标量（官方 Android skills 的 `description:` 常见写法）：
                        // 首行非空时，继续吸收随后的缩进续行，直到空行、未缩进行，
                        // 或下一级映射键（如 metadata 下的 author / keywords）。
                        if (value.isNotEmpty()) {
                            val folded = StringBuilder(value)
                            while (index < lines.size) {
                                val continuation = lines[index]
                                val trimmedContinuation = continuation.trim()
                                if (continuation.isBlank() ||
                                    !(continuation.startsWith(" ") || continuation.startsWith("\t")) ||
                                    NESTED_KEY_BOUNDARY.matches(trimmedContinuation)
                                ) {
                                    break
                                }
                                folded.append(' ').append(trimmedContinuation)
                                index++
                            }
                            value = folded.toString()
                        }
                    }
                    value = value.trim('"', '\'')
                }
                if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
            }
            return result
        }

        /** Claude/OpenMinis 技能名规范：小写、空白折叠为连字符、上限 64 字符。 */
        private fun normalizeSkillName(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), "-").take(64)
    }
}

private fun AgentSkillEntity.toModel() = AgentSkill(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)

private fun AgentSkill.toEntity() = AgentSkillEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    triggerCommand = triggerCommand,
    iconName = iconName,
    isEnabled = isEnabled,
    isBuiltin = isBuiltin,
    isImmutable = isImmutable,
    category = category,
    resourcePath = resourcePath,
)
