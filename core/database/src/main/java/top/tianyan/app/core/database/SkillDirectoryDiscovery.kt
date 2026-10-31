package top.tianyan.app.core.database

import java.io.File

/** Skill 目录是资源包边界；只有无入口的集合目录才继续发现下一级技能。 */
object SkillDirectoryDiscovery {
    fun promptFile(dir: File): File? = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.lowercase() in AgentSkillRepository.SKILL_PROMPT_FILE_NAMES }
        .let { files -> files.firstOrNull { it.name.equals("SKILL.md", ignoreCase = true) } ?: files.firstOrNull() }

    /** 返回各独立 Skill 的主入口，保留其下所有文档和脚本为子资源。 */
    fun discover(root: File): List<File> = root.walkTopDown()
        .maxDepth(6)
        .onEnter { dir -> dir == root || dir.parentFile?.let(::promptFile) == null }
        .filter { it.isDirectory }
        .mapNotNull(::promptFile)
        .toList()
}
