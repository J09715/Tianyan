package top.tianyan.app.core.model.skill

import kotlinx.serialization.Serializable

/**
 * 技能所申请的系统权限边界。
 */
@Serializable
enum class SkillPermission(
    val id: String,
    val label: String,
    val description: String,
    val isHighRisk: Boolean,
) {
    EXEC_COMMAND(
        id = "exec_command",
        label = "命令执行",
        description = "允许在 Linux PRoot 沙箱中执行 Shell 指令或调用附带脚本",
        isHighRisk = true,
    ),
    NETWORK(
        id = "network",
        label = "网络访问",
        description = "允许向外部公网或局域网主机发起网络请求",
        isHighRisk = true,
    ),
    FILE_WRITE(
        id = "file_write",
        label = "文件系统写",
        description = "允许在工作区或沙箱内部创建、修改或删除文件",
        isHighRisk = false,
    ),
    BROWSER_AUTOMATION(
        id = "browser_automation",
        label = "浏览器控制",
        description = "允许控制内置无头浏览器进行页面渲染、快照抓取或 DOM 分析",
        isHighRisk = true,
    ),
    BACKGROUND_SERVICE(
        id = "background_service",
        label = "后台常驻",
        description = "允许在沙箱中注册并启动长期常驻的后台进程服务",
        isHighRisk = false,
    ),
    SYSTEM_PROBE(
        id = "system_probe",
        label = "系统探测",
        description = "允许探测沙箱 CPU、内存利用率及运行中进程列表",
        isHighRisk = false,
    );

    companion object {
        fun fromIdOrNull(id: String): SkillPermission? =
            entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }
    }
}

/**
 * 技能包标准元数据声明（对应 SKILL.md Frontmatter 或 manifest.json）。
 */
@Serializable
data class SkillManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val description: String,
    val author: String = "Tianyan Community",
    val license: String? = "Apache-2.0",
    val icon: String = "Sparkles",
    val category: String = "通用",
    val tags: List<String> = emptyList(),
    val minTianyanVersion: String = "1.0.0",
    val compatibleRuntimes: List<String> = listOf("debian", "proot"),
    val permissions: List<SkillPermission> = emptyList(),
    val requiredTools: List<String> = emptyList(),
    val triggerCommand: String? = null,
    val homepage: String? = null,
    val repository: String? = null,
)

/**
 * 声明式提示词模板解构集合（对标 PalmClaw 标准）。
 */
@Serializable
data class PromptTemplateBundle(
    val skillMd: String? = null,
    val agentMd: String? = null,
    val soulMd: String? = null,
    val toolsMd: String? = null,
    val userMd: String? = null,
    val memoryMd: String? = null,
    val extraFiles: Map<String, String> = emptyMap(),
) {
    /**
     * 智能组装生成系统提示词 (System Prompt)。
     */
    fun composeSystemPrompt(resourceGuestPath: String? = null): String = buildString {
        // 1. 技能入口与总纲
        skillMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append(it)
            append("\n\n")
        }

        // 2. 角色与执行指引 (AGENT)
        agentMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("### 【核心角色与指令 (AGENT)】\n")
            append(it)
            append("\n\n")
        }

        // 3. 人格心智与沟通风格 (SOUL)
        soulMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("### 【人格边界与风格 (SOUL)】\n")
            append(it)
            append("\n\n")
        }

        // 4. 工具使用约定 (TOOLS)
        toolsMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("### 【工具使用规范 (TOOLS)】\n")
            append(it)
            append("\n\n")
        }

        // 5. 用户上下文契约 (USER)
        userMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("### 【用户交互契约 (USER)】\n")
            append(it)
            append("\n\n")
        }

        // 6. 长期记忆与初始事实切片 (MEMORY)
        memoryMd?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("### 【初始化事实记忆 (MEMORY)】\n")
            append(it)
            append("\n\n")
        }

        // 7. 沙箱物理资源目录挂载提示
        if (!resourceGuestPath.isNullOrBlank()) {
            append("### 【Skill 资源与沙箱挂载】\n")
            append("资源物理路径：`$resourceGuestPath`\n")
            append("如需调用该 Skill 附带的 scripts/ 脚本或 references/ 资产，请在此目录下执行。\n")
        }
    }.trim()
}

/**
 * 完整解构后的技能包模型。
 */
data class SkillPackage(
    val manifest: SkillManifest,
    val templates: PromptTemplateBundle,
    val scripts: List<String> = emptyList(),
    val references: List<String> = emptyList(),
    val rawFiles: Map<String, ByteArray> = emptyMap(),
)

/**
 * 静态安全审查等级。
 */
@Serializable
enum class AuditLevel(val severity: Int, val label: String) {
    SAFE(0, "安全"),
    INFO(1, "提示"),
    WARNING(2, "警告"),
    DANGER(3, "高危"),
    BLOCKED(4, "严重阻断");
}

/**
 * 安全审查发现项。
 */
@Serializable
data class AuditFinding(
    val level: AuditLevel,
    val ruleId: String,
    val title: String,
    val detail: String,
    val targetFile: String? = null,
    val line: Int? = null,
    val snippet: String? = null,
)

/**
 * 完整的静态安全审查报告。
 */
@Serializable
data class SecurityAuditReport(
    val level: AuditLevel,
    val findings: List<AuditFinding> = emptyList(),
    val declaredPermissions: Set<SkillPermission> = emptySet(),
    val detectedPermissions: Set<SkillPermission> = emptySet(),
    val undeclaredPermissions: Set<SkillPermission> = emptySet(),
) {
    val isBlocked: Boolean get() = level == AuditLevel.BLOCKED
    val isSafe: Boolean get() = level == AuditLevel.SAFE
    val hasWarnings: Boolean get() = level >= AuditLevel.WARNING
}

/**
 * 兼容性评估等级。
 */
@Serializable
enum class CompatibilityLevel(val label: String) {
    COMPATIBLE("完全兼容"),
    PARTIALLY_COMPATIBLE("部分兼容（存在依赖缺失或降级）"),
    INCOMPATIBLE("不兼容");
}

/**
 * 技能兼容性评估报告。
 */
@Serializable
data class SkillCompatibilityResult(
    val level: CompatibilityLevel,
    val missingTools: List<String> = emptyList(),
    val unsupportedRuntimes: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val isCompatible: Boolean get() = level != CompatibilityLevel.INCOMPATIBLE
}

/**
 * ClawHub 在线技能市场条目简报。
 */
@Serializable
data class ClawHubMarketItem(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val icon: String = "Sparkles",
    val tags: List<String> = emptyList(),
    val category: String = "通用",
    val downloadUrl: String,
    val stars: Int = 0,
    val isInstalled: Boolean = false,
    val requiredTools: List<String> = emptyList(),
    val permissions: List<SkillPermission> = emptyList(),
)

/**
 * ClawHub 技能详情。
 */
@Serializable
data class ClawHubMarketDetail(
    val item: ClawHubMarketItem,
    val readmeMarkdown: String? = null,
    val license: String? = "Apache-2.0",
    val templateSummary: List<String> = emptyList(),
    val sampleScripts: List<String> = emptyList(),
)
