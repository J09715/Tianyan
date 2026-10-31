package top.tianyan.app.core.tools.skill

import top.tianyan.app.core.model.skill.AuditFinding
import top.tianyan.app.core.model.skill.AuditLevel
import top.tianyan.app.core.model.skill.SecurityAuditReport
import top.tianyan.app.core.model.skill.SkillPackage
import top.tianyan.app.core.model.skill.SkillPermission
import java.nio.charset.StandardCharsets

/**
 * 技能端侧静态安全审计器（对标 PalmClaw 端侧权限与安全审查体系）。
 *
 * 审查维度：
 * 1. 结构与打包防御：Zip Slip 路径穿透、解压炸弹、异常 ELF 原生二进制；
 * 2. Prompt 注入与越狱指令审查：针对 LLM 的 System Override、DAN 模式、隐蔽指令与提示词窃取探针；
 * 3. Linux 破坏性指令与恶意提权：rm -rf /、mkfs、dd 磁盘覆写、恶意反弹 Shell、curl | bash；
 * 4. 私密信息嗅探探针：探测 id_rsa 密钥、/etc/shadow、环境变量 API_KEY；
 * 5. 权限边界一致性推断：检查声明权限是否覆盖实际脚本与行为。
 */
class SkillPackageInspector {

    /**
     * 对给定的技能包执行全量离线静态安全审查。
     */
    fun inspect(pkg: SkillPackage): SecurityAuditReport {
        val findings = mutableListOf<AuditFinding>()

        // 1. 结构与打包安全性检查
        inspectArchiveStructure(pkg, findings)

        // 2. 原生二进制可执行文件检查
        inspectBinaryPayloads(pkg, findings)

        // 3. 提示词与脚本的静态内容审查
        inspectTextContents(pkg, findings)

        // 4. 权限边界推断与一致性审计
        val declaredPermissions = pkg.manifest.permissions.toSet()
        val detectedPermissions = inferRequiredPermissions(pkg)
        val undeclaredPermissions = detectedPermissions - declaredPermissions

        if (undeclaredPermissions.isNotEmpty()) {
            undeclaredPermissions.forEach { missing ->
                val level = if (missing.isHighRisk) AuditLevel.WARNING else AuditLevel.INFO
                findings.add(
                    AuditFinding(
                        level = level,
                        ruleId = "PERM-001",
                        title = "未声明的敏感权限: ${missing.label}",
                        detail = "技能包内包含涉权操作（${missing.description}），但 manifest.permissions 中未声明该权限",
                    ),
                )
            }
        }

        // 计算最高风险等级
        val overallLevel = when {
            findings.any { it.level == AuditLevel.BLOCKED } -> AuditLevel.BLOCKED
            findings.any { it.level == AuditLevel.DANGER } -> AuditLevel.DANGER
            findings.any { it.level == AuditLevel.WARNING } -> AuditLevel.WARNING
            findings.any { it.level == AuditLevel.INFO } -> AuditLevel.INFO
            else -> AuditLevel.SAFE
        }

        return SecurityAuditReport(
            level = overallLevel,
            findings = findings.sortedByDescending { it.level.severity },
            declaredPermissions = declaredPermissions,
            detectedPermissions = detectedPermissions,
            undeclaredPermissions = undeclaredPermissions,
        )
    }

    private fun inspectArchiveStructure(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        pkg.rawFiles.keys.forEach { path ->
            if (path.contains("../") || path.startsWith("/") || path.contains("..\\")) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.BLOCKED,
                        ruleId = "SEC-001",
                        title = "检测到 Zip Slip 路径穿越",
                        detail = "文件路径试图越过目标目录: $path",
                        targetFile = path,
                    ),
                )
            }
        }

        val totalSize = pkg.rawFiles.values.sumOf { it.size.toLong() }
        if (totalSize > SkillPackageParser.MAX_ZIP_TOTAL_BYTES) {
            findings.add(
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "SEC-003",
                    title = "解压体积过大 (疑似 Zip Bomb)",
                    detail = "解压体积达到 $totalSize 字节，超过安全阈值 ${SkillPackageParser.MAX_ZIP_TOTAL_BYTES} 字节",
                ),
            )
        }
    }

    private fun inspectBinaryPayloads(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        pkg.rawFiles.forEach { (path, bytes) ->
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext in DANGEROUS_EXTENSIONS) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.DANGER,
                        ruleId = "BIN-001",
                        title = "包含潜在危险的可执行或库文件",
                        detail = "检测到具有原生执行风险的扩展名: .$ext",
                        targetFile = path,
                    ),
                )
            }

            // 检查 ELF 魔数 0x7F 'E' 'L' 'F'
            if (bytes.size >= 4 &&
                bytes[0] == 0x7F.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
            ) {
                findings.add(
                    AuditFinding(
                        level = AuditLevel.DANGER,
                        ruleId = "BIN-002",
                        title = "包含未签名的 Linux ELF 二进制文件",
                        detail = "技能包应当以提示词和通用脚本为主，内置 ELF 二进制无法在 Android/PRoot 跨架构安全保障",
                        targetFile = path,
                    ),
                )
            }
        }
    }

    private fun inspectTextContents(pkg: SkillPackage, findings: MutableList<AuditFinding>) {
        val textsToCheck = buildMap {
            pkg.templates.skillMd?.let { put("SKILL.md", it) }
            pkg.templates.agentMd?.let { put("AGENT.md", it) }
            pkg.templates.soulMd?.let { put("SOUL.md", it) }
            pkg.templates.toolsMd?.let { put("TOOLS.md", it) }
            pkg.templates.userMd?.let { put("USER.md", it) }
            pkg.templates.memoryMd?.let { put("MEMORY.md", it) }

            pkg.rawFiles.forEach { (path, bytes) ->
                if (path.startsWith("scripts/", ignoreCase = true) || path.endsWith(".sh") || path.endsWith(".py") || path.endsWith(".js")) {
                    runCatching { put(path, bytes.toString(StandardCharsets.UTF_8)) }
                }
            }
        }

        textsToCheck.forEach { (filename, text) ->
            // 1. 越狱与 Prompt 注入
            checkPromptInjections(filename, text, findings)

            // 2. 隐蔽零宽字符
            checkZeroWidthChars(filename, text, findings)

            // 3. 破坏性 Linux 指令
            checkDestructiveCommands(filename, text, findings)

            // 4. 反弹 Shell 与外部代码直接执行管道
            checkReverseShellAndPipes(filename, text, findings)

            // 5. 敏感隐私嗅探
            checkSensitiveFileProbes(filename, text, findings)
        }
    }

    private fun checkPromptInjections(filename: String, text: String, findings: MutableList<AuditFinding>) {
        PROMPT_INJECTION_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "INJ-001",
                    title = "检测到恶意 Prompt 注入或越狱指令",
                    detail = "命中高危注入模式: \"${match.value.take(40)}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }

        PROMPT_LEAK_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.DANGER,
                    ruleId = "INJ-002",
                    title = "可疑的系统提示词泄露探针",
                    detail = "检测到尝试提取模型 System Prompt 或隐藏指令的诱导语句",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkZeroWidthChars(filename: String, text: String, findings: MutableList<AuditFinding>) {
        val zeroWidthCount = text.count { it in ZERO_WIDTH_CHARS }
        if (zeroWidthCount > 10) {
            findings.add(
                AuditFinding(
                    level = AuditLevel.WARNING,
                    ruleId = "INJ-003",
                    title = "检测到异常零宽不可见字符 ($zeroWidthCount 处)",
                    detail = "文本中存在大量零宽空格/隐藏 Unicode 字符，可能用于绕过静态过滤或实施隐写攻击",
                    targetFile = filename,
                ),
            )
        }
    }

    private fun checkDestructiveCommands(filename: String, text: String, findings: MutableList<AuditFinding>) {
        DESTRUCTIVE_COMMAND_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "CMD-001",
                    title = "破坏性沙箱或系统命令",
                    detail = "检测到可能导致系统/沙箱损坏的危险指令: \"${match.value.trim()}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }

        // 静态混淆样本不直接阻断，但必须显著提示人工复核
        OBFUSCATION_HINT_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.WARNING,
                    ruleId = "CMD-004",
                    title = "检测到可疑的指令混淆或编码变体",
                    detail = "文本包含常见混淆执行模式（base64 解码管道 / eval 嵌套 / \${IFS} 拼接 / 十六进制编码 / 引号拆分），静态审计无法判定其真实意图: \"${match.value.trim().take(60)}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkReverseShellAndPipes(filename: String, text: String, findings: MutableList<AuditFinding>) {
        REVERSE_SHELL_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.BLOCKED,
                    ruleId = "CMD-002",
                    title = "恶意反弹 Shell 或外部脚本静默管道",
                    detail = "检测到高危网络远程执行指令: \"${match.value.trim()}\"",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun checkSensitiveFileProbes(filename: String, text: String, findings: MutableList<AuditFinding>) {
        SENSITIVE_PROBE_PATTERNS.forEach { pattern ->
            reportMatches(filename, text, pattern, findings) { match ->
                AuditFinding(
                    level = AuditLevel.DANGER,
                    ruleId = "CMD-003",
                    title = "宿主/沙箱私密敏感文件嗅探",
                    detail = "检测到尝试读取 SSH 私钥、凭证或系统敏感密码文件的指令",
                    targetFile = filename,
                    snippet = extractSnippet(text, match.range.first),
                )
            }
        }
    }

    private fun reportMatches(
        filename: String,
        text: String,
        pattern: Regex,
        findings: MutableList<AuditFinding>,
        maxReportsPerPattern: Int = 3,
        build: (MatchResult) -> AuditFinding,
    ) {
        pattern.findAll(text).take(maxReportsPerPattern).forEach { match ->
            findings.add(build(match))
        }
    }

    private fun inferRequiredPermissions(pkg: SkillPackage): Set<SkillPermission> {
        val permissions = mutableSetOf<SkillPermission>()

        // 是否包含脚本或前台命令行
        if (pkg.scripts.isNotEmpty() || pkg.rawFiles.keys.any { it.endsWith(".sh") || it.endsWith(".py") }) {
            permissions.add(SkillPermission.EXEC_COMMAND)
        }

        val allText = buildString {
            pkg.templates.skillMd?.let(::append)
            pkg.templates.agentMd?.let(::append)
            pkg.templates.soulMd?.let(::append)
            pkg.templates.toolsMd?.let(::append)
            pkg.templates.userMd?.let(::append)
            pkg.templates.memoryMd?.let(::append)
            pkg.rawFiles.forEach { (path, bytes) ->
                val lower = path.lowercase()
                val isTextFile = lower.endsWith(".sh") || lower.endsWith(".py") || lower.endsWith(".js") ||
                    lower.endsWith(".ts") || lower.endsWith(".txt") || lower.endsWith(".json") ||
                    lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".bash")
                if (isTextFile) {
                    runCatching { append(bytes.toString(StandardCharsets.UTF_8)) }
                }
            }
        }

        if (NETWORK_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.NETWORK)
        }
        if (FILE_WRITE_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.FILE_WRITE)
        }
        if (BROWSER_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.BROWSER_AUTOMATION)
        }
        if (PROCESS_HINT_PATTERNS.any { it.containsMatchIn(allText) }) {
            permissions.add(SkillPermission.BACKGROUND_SERVICE)
        }

        return permissions
    }

    private fun extractSnippet(text: String, charIndex: Int): String {
        val start = (charIndex - 30).coerceAtLeast(0)
        val end = (charIndex + 50).coerceAtMost(text.length)
        return "..." + text.substring(start, end).replace('\n', ' ').trim() + "..."
    }

    companion object {
        private val DANGEROUS_EXTENSIONS = setOf(
            "exe", "dll", "so", "dylib", "msi", "bat", "vbs", "cmd", "scr", "pif",
        )

        private val ZERO_WIDTH_CHARS = setOf(
            '\u200B', '\u200C', '\u200D', '\u200E', '\u200F', '\uFEFF',
        )

        private val PROMPT_INJECTION_PATTERNS = listOf(
            Regex("""(?i)\b(ignore|disregard|forget)\s+(all\s+)?(previous|prior|above)\s+(instructions|prompts|rules)"""),
            Regex("""(?i)\b(you are now|pretend to be|act as)\s+(an unregulated|jailbroken|DAN\b|developer mode|chaosgpt)"""),
            Regex("""(?i)\b(system prompt override|new instructions begin now|admin override mode)"""),
            Regex("""(?i)\b(bypass|disable)\s+(all\s+)?(safety|ethical|content)\s+(filters|guidelines|restrictions)"""),
            Regex("""(?i)do anything now\b"""),
        )

        private val PROMPT_LEAK_PATTERNS = listOf(
            Regex("""(?i)\b(reveal|output|display|print|leak)\s+(your|the)\s+(system prompt|initial prompt|hidden instructions)"""),
            Regex("""(?i)what are (your|the) exact (instructions|prompts) given to you above"""),
        )

        private val DESTRUCTIVE_COMMAND_PATTERNS = listOf(
            Regex("""(?i)\brm\s+(-[a-z0-9_-]*[rf][a-z0-9_-]*\s+|--recursive\s+|--force\s+)*(--no-preserve-root\s+)?(/|/\*|~|~/|~/\*|\$\{?HOME\}?|\$\{?HOME\}?/(|\*))(?=\s*($|[;\n&|)]|\s))"""),
            Regex("""\bmkfs(\.[a-z0-9]+)?\s+"""),
            Regex("""\bdd\s+.*\bof=/dev/(sd|hd|nvme|mmcblk|disk|zero)"""),
            Regex("""(?i)\bchmod\s+(-[a-z0-9_-]*[R][a-z0-9_-]*\s+)?777\s+(/|/etc|/bin|/usr|/var|/root)(/)?(?=\s*($|[;\n&|)]|\s))"""),
            Regex(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:"""), // Fork Bomb
            Regex("""(?i)(bash|sh|zsh)\s+<\(\s*(curl|wget)\b"""), // 进程替换执行远程脚本
        )

        private val REVERSE_SHELL_PATTERNS = listOf(
            Regex("""bash\s+-i\s+>&\s*/dev/tcp/"""),
            Regex("""nc(\.traditional)?\s+.*-e\s+(/bin/)?(bash|sh)"""),
            Regex("""(?i)\bcurl\s+.*\|\s*(sudo\s+)?(bash|sh)\b"""),
            Regex("""(?i)\bwget\s+.*\|\s*(sudo\s+)?(bash|sh)\b"""),
            Regex("""python[0-9.]*\s+-c\s+.*import\s+socket,subprocess"""),
        )

        /**
         * 常见静态混淆执行变体：黑名单无法穷举，仅作 WARNING 级人工复核提示。
         */
        private val OBFUSCATION_HINT_PATTERNS = listOf(
            Regex("""(?i)\bbase64\s+(-[a-z]+\s+)*-d.*\|\s*(sudo\s+)?(ba|z|da)?sh\b"""),
            Regex("""(?i)\beval\s+["']?\$\("""),
            Regex("""\$\{?IFS\}?"""),
            Regex("""(?i)printf\s+['"](\\x[0-9a-f]{2}){4,}"""),
            Regex("""\b[a-z]{1,6}(['"])\s*\1[a-z]{1,6}\b"""), // 相邻引号片段拆分命令名: r""m → rm
        )

        private val SENSITIVE_PROBE_PATTERNS = listOf(
            Regex("""(?i)\b(cat|head|tail|less|grep)\s+.*(id_rsa|id_ed25519|\.ssh/|/etc/shadow|\.bash_history|\.env\b)"""),
            Regex("""(?i)\becho\s+${'$'}(API_KEY|OPENAI_API_KEY|ANTHROPIC_API_KEY|PASSWORD|TOKEN)\b"""),
        )

        private val NETWORK_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(curl|wget|httpclient|fetch|requests\.(get|post)|urllib)\b"""),
            Regex("""https?://[a-zA-Z0-9.-]+"""),
        )

        private val FILE_WRITE_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(write|edit|mkdir|touch|tee\b|>>?)\b"""),
            Regex("""open\([^)]+,\s*['"][wa]"""),
        )

        private val BROWSER_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(browser|cdp|playwright|puppeteer|page\.goto|dom_query)\b"""),
        )

        private val PROCESS_HINT_PATTERNS = listOf(
            Regex("""(?i)\b(daemon|nohup|supervisord|systemctl|process\.start)\b"""),
        )
    }
}
