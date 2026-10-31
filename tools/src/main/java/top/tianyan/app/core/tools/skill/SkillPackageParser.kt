package top.tianyan.app.core.tools.skill

import top.tianyan.app.core.model.skill.PromptTemplateBundle
import top.tianyan.app.core.model.skill.SkillManifest
import top.tianyan.app.core.model.skill.SkillPackage
import top.tianyan.app.core.model.skill.SkillPermission
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 技能包标准解构与解析器（对标 PalmClaw 标准目录与声明式模板规范）。
 */
class SkillPackageParser {

    /**
     * 从本地目录解析技能包。
     * 与 ZIP 路径共享单文件和总大小防护上限，目录来源不受信任程度相同，
     * 无上限的 walkTopDown + readBytes 会在超大目录上直接 OOM。
     */
    fun parseFromDirectory(dir: File): SkillPackage {
        require(dir.isDirectory) { "指定路径不是有效目录: ${dir.absolutePath}" }

        val files = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        dir.walkTopDown().maxDepth(4).filter { it.isFile }.forEach { file ->
            if (file.length() > MAX_SINGLE_ENTRY_BYTES) {
                throw SecurityException("技能目录内单个文件超过大小上限 $MAX_SINGLE_ENTRY_BYTES 字节: ${file.name}")
            }
            totalBytes += file.length()
            if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                throw SecurityException("技能目录总大小超过上限 $MAX_ZIP_TOTAL_BYTES 字节")
            }
            files[file.relativeTo(dir).invariantSeparatorsPath] = file.readBytes()
        }

        return parseFromFiles(files, fallbackId = dir.name)
    }

    /**
     * 从 ZIP 字节数组解析技能包（含 Zip Slip 路径穿越防御）。
     */
    fun parseFromZip(zipBytes: ByteArray, fallbackId: String = "skill_" + UUID.randomUUID().toString().take(8)): SkillPackage {
        return parseFromZipStream(ByteArrayInputStream(zipBytes), fallbackId)
    }

    /**
     * 从 ZIP 输入流解析技能包。
     */
    fun parseFromZipStream(inputStream: InputStream, fallbackId: String = "skill_" + UUID.randomUUID().toString().take(8)): SkillPackage {
        val files = mutableMapOf<String, ByteArray>()
        ZipInputStream(inputStream).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            var totalBytes = 0L

            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                // 防御 Zip Slip 路径穿越
                if (name.contains("../") || name.startsWith("/") || name.contains("/../")) {
                    throw SecurityException("检测到非法的 Zip Slip 路径穿越攻击: $name")
                }

                if (!entry.isDirectory) {
                    val entryBos = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var entryBytes = 0L

                    while (true) {
                        val remainingTotal = MAX_ZIP_TOTAL_BYTES - totalBytes
                        val remainingEntry = MAX_SINGLE_ENTRY_BYTES - entryBytes
                        if (remainingTotal <= 0L || remainingEntry <= 0L) {
                            throw SecurityException("技能解压超出大小限制 (单文件上限 $MAX_SINGLE_ENTRY_BYTES 字节，总大小上限 $MAX_ZIP_TOTAL_BYTES 字节)")
                        }
                        val toRead = minOf(buffer.size.toLong(), remainingTotal, remainingEntry).toInt()
                        val read = zip.read(buffer, 0, toRead)
                        if (read < 0) break

                        entryBytes += read
                        totalBytes += read
                        entryBos.write(buffer, 0, read)

                        if (entryBytes >= MAX_SINGLE_ENTRY_BYTES || totalBytes >= MAX_ZIP_TOTAL_BYTES) {
                            if (zip.read() != -1) {
                                throw SecurityException("技能解压超出大小限制 (单文件上限 $MAX_SINGLE_ENTRY_BYTES 字节，总大小上限 $MAX_ZIP_TOTAL_BYTES 字节)")
                            }
                            break
                        }
                    }
                    files[name] = entryBos.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        // 如果所有文件都被包裹在单个根目录中（例如 `my-skill/...`），去除该前缀
        val normalizedFiles = stripCommonRootDirectory(files)
        return parseFromFiles(normalizedFiles, fallbackId)
    }

    /**
     * 从扁平化路径映射解析出规范的技能包结构。
     */
    fun parseFromFiles(files: Map<String, ByteArray>, fallbackId: String): SkillPackage {
        // 查找 SKILL.md 或 prompt.md
        val skillEntry = files.entries.firstOrNull { (path, _) ->
            path.equals("SKILL.md", ignoreCase = true) || path.equals("prompt.md", ignoreCase = true)
        } ?: files.entries.firstOrNull { (path, _) ->
            path.endsWith("/SKILL.md", ignoreCase = true) || path.endsWith("/prompt.md", ignoreCase = true)
        }

        val rawSkillMarkdown = skillEntry?.value?.toString(StandardCharsets.UTF_8).orEmpty()
        val frontmatter = parseFrontmatter(rawSkillMarkdown)

        // 提取解构模板
        val agentMd = findTextContent(files, "AGENT.md")
        val soulMd = findTextContent(files, "SOUL.md")
        val toolsMd = findTextContent(files, "TOOLS.md")
        val userMd = findTextContent(files, "USER.md")
        val memoryMd = findTextContent(files, "MEMORY.md")

        val scripts = files.keys.filter { it.startsWith("scripts/", ignoreCase = true) }
        val references = files.keys.filter { it.startsWith("references/", ignoreCase = true) }

        // id 用于构造安装目录名，必须清洗为安全字符集，防止 "../"、"." 等注入路径
        val id = frontmatter["id"]
            ?.takeIf { it.isNotBlank() }
            ?.let(::sanitizeSkillId)
            // 社区 Agent Skills 通常只声明 name，它也是技能的稳定标识。
            ?: frontmatter["name"]?.takeIf { it.isNotBlank() }
                ?.let { runCatching { sanitizeSkillId(it) }.getOrNull() }
            ?: run {
                // fallback（目录名/调用方提供）清洗失败时不阻断解析，回退到生成的稳定 id
                runCatching { sanitizeSkillId(fallbackId) }.getOrElse {
                    "skill_" + UUID.randomUUID().toString().take(8)
                }
            }

        val name = frontmatter["name"]
            ?: extractFirstHeading(rawSkillMarkdown)
            ?: agentMd?.let(::extractFirstHeading)
            ?: fallbackId

        val description = frontmatter["description"]
            ?: "天衍声明式智能体技能包"

        val version = frontmatter["version"] ?: "1.0.0"
        val author = frontmatter["author"] ?: "Community"
        val license = frontmatter["license"]
        val icon = frontmatter["icon"] ?: "Sparkles"
        val category = frontmatter["category"] ?: "通用"
        val triggerCommand = frontmatter["trigger_command"] ?: frontmatter["triggercommand"]

        val tags = (frontmatter["tags"] ?: "")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'') }
            .filter { it.isNotEmpty() }

        val requiredTools = (frontmatter["required_tools"] ?: frontmatter["requiredtools"] ?: "")
            .removePrefix("[").removeSuffix("]")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }

        val permissions = parsePermissions(frontmatter["permissions"])

        val manifest = SkillManifest(
            id = id,
            name = name,
            version = version,
            description = description,
            author = author,
            license = license,
            icon = icon,
            category = category,
            tags = tags,
            permissions = permissions,
            requiredTools = requiredTools,
            triggerCommand = triggerCommand,
        )

        val bundle = PromptTemplateBundle(
            skillMd = stripFrontmatter(rawSkillMarkdown).takeIf { it.isNotBlank() },
            agentMd = agentMd,
            soulMd = soulMd,
            toolsMd = toolsMd,
            userMd = userMd,
            memoryMd = memoryMd,
            extraFiles = files.filterKeys { k ->
                !k.equals("SKILL.md", ignoreCase = true) &&
                    !k.equals("AGENT.md", ignoreCase = true) &&
                    !k.equals("SOUL.md", ignoreCase = true) &&
                    !k.equals("TOOLS.md", ignoreCase = true) &&
                    !k.equals("USER.md", ignoreCase = true) &&
                    !k.equals("MEMORY.md", ignoreCase = true)
            }.mapValues { it.value.toString(StandardCharsets.UTF_8) },
        )

        return SkillPackage(
            manifest = manifest,
            templates = bundle,
            scripts = scripts,
            references = references,
            rawFiles = files,
        )
    }

    private fun sanitizeSkillId(raw: String): String {
        val sanitized = raw.lowercase().replace(Regex("[^a-z0-9_-]"), "_")
        if (sanitized.isBlank() || sanitized.all { it == '_' }) {
            throw SecurityException("技能包声明的 id 非法，已拒绝解析: $raw")
        }
        return sanitized
    }

    private fun findTextContent(files: Map<String, ByteArray>, fileName: String): String? {
        val entry = files.entries.firstOrNull { (k, _) ->
            k.equals(fileName, ignoreCase = true) || k.endsWith("/$fileName", ignoreCase = true)
        } ?: return null
        return entry.value.toString(StandardCharsets.UTF_8).trim().takeIf { it.isNotBlank() }
    }

    private fun parsePermissions(raw: String?): List<SkillPermission> {
        if (raw.isNullOrBlank()) return emptyList()
        val tokens = raw.removePrefix("[").removeSuffix("]")
            .split(',', ';', ' ')
            .map { it.trim().trim('"', '\'', '[', ']') }
            .filter { it.isNotEmpty() }

        return tokens.mapNotNull { SkillPermission.fromIdOrNull(it) }
    }

    private fun extractFirstHeading(markdown: String): String? =
        markdown.lineSequence()
            .firstOrNull { it.trim().startsWith("# ") }
            ?.substringAfter("# ")
            ?.trim()

    /**
     * 解析 YAML Frontmatter。
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

            // 折叠块标量指示符 (>-, | 等)
            if (value.startsWith(">") || value.startsWith("|")) {
                val block = StringBuilder()
                while (index < lines.size && (lines[index].startsWith(" ") || lines[index].startsWith("\t") || lines[index].isBlank())) {
                    block.append(' ').append(lines[index].trim())
                    index++
                }
                value = block.toString().trim()
            } else {
                if (value.startsWith("\"") && value.contains("\" #")) {
                    val endQuote = value.indexOf('"', 1)
                    if (endQuote > 0) value = value.substring(1, endQuote)
                } else if (value.startsWith("'") && value.contains("' #")) {
                    val endQuote = value.indexOf('\'', 1)
                    if (endQuote > 0) value = value.substring(1, endQuote)
                } else {
                    val comment = value.indexOf(" #")
                    if (comment >= 0) value = value.substring(0, comment).trim()
                    value = value.trim('"', '\'')
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
            }
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    /**
     * 剥除 Markdown 中的 YAML Frontmatter 头部。
     */
    fun stripFrontmatter(markdown: String): String {
        var text = markdown
        if (text.startsWith("\uFEFF")) text = text.substring(1)
        if (!text.startsWith("---")) return text

        val lines = text.lines()
        val endIdx = lines.drop(1).indexOfFirst { it.trim() == "---" || it.trim() == "..." }
        return if (endIdx != -1) {
            lines.drop(endIdx + 2).joinToString("\n").trim()
        } else {
            text
        }
    }

    private fun stripCommonRootDirectory(files: Map<String, ByteArray>): Map<String, ByteArray> {
        val rootDirs = files.keys.mapNotNull { path ->
            val slash = path.indexOf('/')
            if (slash > 0) path.substring(0, slash) else null
        }.toSet()

        if (rootDirs.size == 1 && files.keys.all { it.contains('/') }) {
            val root = rootDirs.first() + "/"
            return files.mapKeys { (k, _) -> k.removePrefix(root) }
        }
        return files
    }

    companion object {
        const val MAX_SINGLE_ENTRY_BYTES = 10L * 1024 * 1024 // 10MB
        const val MAX_ZIP_TOTAL_BYTES = 50L * 1024 * 1024 // 50MB

        /**
         * 形如 `key:` / `key: value` 的映射键（YAML 中普通标量不能以该形态续行）。
         * 用于终止普通多行标量的折叠，避免把 metadata 下的子键并进上一条描述。
         */
        private val NESTED_KEY_BOUNDARY = Regex("""[A-Za-z0-9_.\-]+\s*:(\s|$)""")
    }
}
