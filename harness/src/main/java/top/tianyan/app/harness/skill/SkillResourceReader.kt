package top.tianyan.app.harness.skill

import java.io.File

/**
 * 标准 SKILL.md 目录级技能的受限资源读取（对标 RikkaHub `use_skill`）。
 *
 * 技能目录形态：根目录含 `SKILL.md`（YAML frontmatter + 正文），并可有
 * `references/` 参考资料、`scripts/` 可执行脚本等子资源。系统提示只挂轻量
 * catalog，模型命中后用 `load_skill(name, path)` 按需深读，削减常驻 Token。
 */
object SkillResourceReader {
    /** 单个技能子资源读取上限，避免把巨型资料灌进上下文。 */
    const val MAX_RESOURCE_BYTES = 512 * 1024L

    /**
     * 剔除 SKILL.md 顶部的 YAML frontmatter，只把正文交给模型（元数据由目录负责展示）。
     * 非 frontmatter 文本原样返回。
     */
    fun stripFrontmatter(markdown: String): String {
        val text = markdown.removePrefix("\uFEFF")
        if (!text.startsWith("---")) return markdown
        val lines = text.lines()
        val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
            ?: return markdown
        return lines.drop(end + 1).joinToString("\n").trimStart('\n')
    }

    /**
     * 读取技能目录内的受限相对路径资源：拒绝绝对路径、`..` 段与符号链接逃逸，
     * 限制单文件体积，避免模型借 load_skill 越权读取任意宿主文件。
     * 返回 null 表示路径非法/不存在；超大文件返回提示文本。
     */
    fun readSubResource(dir: File, relativePath: String): String? {
        val clean = relativePath.trim().replace('\\', '/').trimStart('/')
        if (clean.isEmpty() || clean.split('/').any { it == ".." }) return null
        val root = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(root, clean).canonicalFile }.getOrNull() ?: return null
        val inside = target.absolutePath == root.absolutePath ||
            target.absolutePath.startsWith(root.absolutePath + File.separator)
        if (!inside || !target.isFile) return null
        if (target.length() > MAX_RESOURCE_BYTES) {
            return "（资源过大：${target.length()} 字节，上限 $MAX_RESOURCE_BYTES 字节；请用 base 的 head/sed 分页查看）"
        }
        return runCatching { target.readText(Charsets.UTF_8) }.getOrNull()
    }
}