package top.tianyan.app.core.tools.skill

import top.tianyan.app.core.common.result.AppError
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.common.result.ErrorCode
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.model.AgentSkill
import top.tianyan.app.core.model.skill.AuditLevel
import top.tianyan.app.core.model.skill.SecurityAuditReport
import top.tianyan.app.core.model.skill.SkillCompatibilityResult
import top.tianyan.app.core.model.skill.SkillPackage
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * 技能安装审查上下文（包含解构后的技能包、端侧静态安全审计报告与兼容性评估结果）。
 */
data class SkillInstallInspection(
    val packageId: String,
    val pkg: SkillPackage,
    val auditReport: SecurityAuditReport,
    val compatibilityResult: SkillCompatibilityResult,
) {
    val isBlocked: Boolean get() = auditReport.isBlocked
    val canProceed: Boolean get() = !isBlocked
}

class SkillSecurityBlockedException(
    val report: SecurityAuditReport,
    message: String = "技能包未能通过端侧静态安全审查，已被系统阻断安装",
) : SecurityException(message)

/**
 * 技能安全审查与安装事务管理器（连接 ClawHub 市场、静态审计引擎与天衍持久化仓储）。
 */
class SkillInstallationManager(
    private val packageParser: SkillPackageParser,
    private val inspector: SkillPackageInspector,
    private val compatibilityEvaluator: SkillCompatibilityEvaluator,
    private val clawHubClient: ClawHubClient,
    private val agentSkillRepository: AgentSkillRepository,
) {

    /**
     * 准备并审查来自 ClawHub 市场的技能包。
     */
    suspend fun prepareMarketSkill(skillId: String): AppResult<SkillInstallInspection> {
        val downloadRes = clawHubClient.downloadPackage(skillId)
        if (downloadRes !is AppResult.Success) {
            return AppResult.Failure(AppError(ErrorCode.DOWNLOAD, "下载市场技能包失败"))
        }

        return runCatching {
            val inspection = inspectZipBytes(downloadRes.data, fallbackId = skillId)
            AppResult.Success(inspection)
        }.getOrElse { err ->
            AppResult.Failure(AppError(ErrorCode.SECURITY, err.message ?: "审查失败", err))
        }
    }

    /**
     * 对 ZIP 字节流执行解构与端侧静态安全审计。
     */
    fun inspectZipBytes(zipBytes: ByteArray, fallbackId: String = "skill_${UUID.randomUUID()}"): SkillInstallInspection {
        val pkg = packageParser.parseFromZip(zipBytes, fallbackId)
        return inspectPackage(pkg)
    }

    /**
     * 对本地技能目录执行解构与端侧静态安全审计。
     */
    fun inspectDirectory(dir: File): SkillInstallInspection {
        val pkg = packageParser.parseFromDirectory(dir)
        return inspectPackage(pkg)
    }

    /**
     * 对解构后的技能包执行完整的安全与兼容性评估。
     */
    fun inspectPackage(pkg: SkillPackage): SkillInstallInspection {
        val auditReport = inspector.inspect(pkg)
        val compatibility = compatibilityEvaluator.evaluate(pkg)

        return SkillInstallInspection(
            packageId = pkg.manifest.id,
            pkg = pkg,
            auditReport = auditReport,
            compatibilityResult = compatibility,
        )
    }

    /**
     * 提交安装：将经安全审查通过的技能包安全解压落盘，并注册到 AgentSkillRepository。
     *
     * @param inspection 审查上下文
     * @param targetSkillsDir 宿主技能安装根目录（通常为 attachments/skills）
     * @param guestPrefix 沙箱内挂载路径前缀（通常为 /attachments/skills）
     */
    suspend fun commitInstallation(
        inspection: SkillInstallInspection,
        targetSkillsDir: File,
        guestPrefix: String = "/attachments/skills",
    ): AgentSkill {
        if (inspection.isBlocked) {
            throw SkillSecurityBlockedException(inspection.auditReport)
        }

        // 提交前对内存中的包体重新审计，防止调用方传入被篡改的审查结论
        val pkg = inspection.pkg
        val reReport = inspector.inspect(pkg)
        if (reReport.isBlocked) {
            throw SkillSecurityBlockedException(reReport)
        }

        val skillId = pkg.manifest.id
        if (!SKILL_ID_PATTERN.matches(skillId)) {
            throw SecurityException("技能 id 含非法字符，已拒绝安装: $skillId")
        }

        // 先写入独立暂存目录，成功后整体重命名：回滚时只需删除暂存目录，绝不触碰其他技能
        val stagingDir = File(targetSkillsDir, "$skillId.staging_${UUID.randomUUID().toString().take(8)}").apply { mkdirs() }
        var renamed = false
        val targetDir = File(targetSkillsDir, skillId)
        val canonicalTarget = stagingDir.canonicalPath

        try {
            pkg.rawFiles.forEach { (relPath, bytes) ->
                val safePath = relPath.trimStart('/')
                val destFile = File(stagingDir, safePath)
                val canonicalDest = destFile.canonicalPath
                if (!canonicalDest.startsWith(canonicalTarget + File.separator)) {
                    throw SecurityException("检测到非法的文件写入逃逸: $relPath")
                }
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(bytes)
            }

            if (targetDir.exists()) targetDir.deleteRecursively()
            if (!stagingDir.renameTo(targetDir)) {
                throw IOException("技能目录暂存重命名失败: ${stagingDir.absolutePath} -> ${targetDir.absolutePath}")
            }
            renamed = true

            val guestPath = guestPrefix.trimEnd('/') + "/$skillId"
            val composedPrompt = pkg.templates.composeSystemPrompt(resourceGuestPath = guestPath)

            val agentSkill = AgentSkill(
                id = "custom_$skillId",
                name = pkg.manifest.name,
                description = pkg.manifest.description,
                systemPrompt = composedPrompt,
                triggerCommand = pkg.manifest.triggerCommand,
                iconName = pkg.manifest.icon,
                isEnabled = true,
                isBuiltin = false,
                isImmutable = false,
                category = pkg.manifest.category,
                resourcePath = targetDir.absolutePath,
            )

            agentSkillRepository.addCustom(agentSkill)
            return agentSkill
        } catch (e: Throwable) {
            if (renamed) {
                targetDir.deleteRecursively()
            } else {
                stagingDir.deleteRecursively()
            }
            throw e
        }
    }

    companion object {
        // 与 SkillPackageParser.sanitizeSkillId 的输出字符集保持一致（清洗结果可能以 _ 开头）
        // 显式全锚定：即便未来调用方误用 containsMatchIn/find 语义，也不会放行 "../../etc" 类路径穿越串
        private val SKILL_ID_PATTERN = Regex("^[a-z0-9_-]+$")
    }
}
