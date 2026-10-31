package top.tianyan.app.core.tools.skill

import top.tianyan.app.core.model.skill.CompatibilityLevel
import top.tianyan.app.core.model.skill.SkillCompatibilityResult
import top.tianyan.app.core.model.skill.SkillPackage
import top.tianyan.app.core.tools.ToolRegistry

/**
 * 技能兼容性与依赖评估器（静态评估技能在当前移动端 PRoot 沙箱与天衍环境下的适配度）。
 */
class SkillCompatibilityEvaluator(
    private val toolRegistry: ToolRegistry? = null,
) {
    /**
     * 评估技能包对当前 Tianyan 运行时环境的兼容性。
     */
    fun evaluate(pkg: SkillPackage): SkillCompatibilityResult {
        val notes = mutableListOf<String>()
        val missingTools = mutableListOf<String>()
        val unsupportedRuntimes = mutableListOf<String>()

        // 1. 运行时沙箱兼容性检查
        val declaredRuntimes = pkg.manifest.compatibleRuntimes
        if (declaredRuntimes.isNotEmpty()) {
            val supportedInTianyan = setOf("proot", "debian", "ubuntu", "alpine", "linux", "android", "arm64", "aarch64")
            val hasOverlap = declaredRuntimes.any { it.lowercase() in supportedInTianyan }
            if (!hasOverlap) {
                unsupportedRuntimes.addAll(declaredRuntimes)
                notes.add("该技能声明的目标运行环境 (${declaredRuntimes.joinToString()}) 与天衍 Android PRoot (arm64-v8a) 不匹配")
            }
        }

        // 2. 依赖外部命令/工具检查
        val requiredTools = pkg.manifest.requiredTools
        if (requiredTools.isNotEmpty()) {
            val installedToolIds = toolRegistry?.load()?.map { it.id.lowercase() }?.toSet().orEmpty()
            requiredTools.forEach { tool ->
                val normalized = tool.trim().lowercase()
                val isBuiltinLinuxCommand = normalized in BUILTIN_SANDBOX_COMMANDS
                val isRegisteredInTianyan = normalized in installedToolIds

                if (!isBuiltinLinuxCommand && !isRegisteredInTianyan) {
                    missingTools.add(tool)
                    notes.add("缺少前置工具依赖: `$tool`（可在沙箱内通过 apt 安装或在工具中心配置对应 Recipe）")
                }
            }
        }

        // 3. 评定综合兼容度
        val level = when {
            unsupportedRuntimes.isNotEmpty() -> CompatibilityLevel.INCOMPATIBLE
            missingTools.isNotEmpty() -> CompatibilityLevel.PARTIALLY_COMPATIBLE
            else -> CompatibilityLevel.COMPATIBLE
        }

        if (level == CompatibilityLevel.COMPATIBLE) {
            notes.add("与当前天衍沙箱 (Debian arm64-v8a) 完全兼容，所有前置依赖均已就绪。")
        }

        return SkillCompatibilityResult(
            level = level,
            missingTools = missingTools,
            unsupportedRuntimes = unsupportedRuntimes,
            notes = notes,
        )
    }

    companion object {
        /** 天衍 Debian 沙箱预置或基础 Linux 核心命令列表。 */
        private val BUILTIN_SANDBOX_COMMANDS = setOf(
            "sh", "bash", "cat", "echo", "grep", "find", "sed", "awk",
            "ls", "cp", "mv", "rm", "mkdir", "chmod", "ps", "tar", "gzip",
            "tianyan-build", "tianyan",
        )
    }
}
