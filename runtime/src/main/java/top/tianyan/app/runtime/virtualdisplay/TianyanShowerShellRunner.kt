package top.tianyan.app.runtime.virtualdisplay

import com.ai.assistance.showerclient.ShellCommandResult
import com.ai.assistance.showerclient.ShellIdentity
import com.ai.assistance.showerclient.ShellRunner
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.privilege.PrivilegeManager

/**
 * Shower 客户端库的 shell 执行通道适配器（骨架版）。
 *
 * 身份映射说明：天衍的特权模型只有一条宿主执行通道 [PrivilegeManager.executeShellCommand]，
 * 由当前生效模式决定身份（SHIZUKU = shell uid / ROOT = root uid）。Shower 库的
 * DEFAULT / SHELL / ROOT 三种身份在此统一收敛到该通道：
 * - DEFAULT（pkill 清理等）与 SHELL（cp / app_process 启动）在两种特权模式下语义均正确；
 * - ROOT 身份仅额外要求 Root 模式，骨架版不做二次校验，依赖 PrivilegeManager 的模式约束；
 * - PRoot 模式下 executeShellCommand 直接返回失败，虚拟屏能力随之自然禁用。
 */
internal class TianyanShowerShellRunner(
    private val privilegeManager: PrivilegeManager,
    private val logger: AppLogger,
) : ShellRunner {

    override suspend fun run(command: String, identity: ShellIdentity): ShellCommandResult {
        val result = privilegeManager.executeShellCommand(command)
        if (!result.success) {
            logger.w("Shower shell 命令执行失败 (identity=$identity, exit=${result.exitCode}): " +
                result.stderr.take(LOG_PREVIEW_LIMIT))
        }
        return ShellCommandResult(
            success = result.success,
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
        )
    }

    private companion object {
        const val LOG_PREVIEW_LIMIT = 200
    }
}
