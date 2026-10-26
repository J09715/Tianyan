package top.tianyan.app.harness

import top.tianyan.app.runtime.shell.CommandResult

/**
 * 命令失败的双分类器（借鉴 DSH 沙箱的 denialSignatures / runnerFailureRules 正交分类）：
 * 区分「沙箱/环境坏了」（runner 自身在命令执行前失败）与「沙箱拦住了 / 命令本身失败」。
 *
 * 动机：环境故障（PRoot 不可用、发行版缺失、运行时未就绪）与命令错误（exit N + stderr）
 * 在失败观感上混同，模型会把环境故障当成命令错误去反复修正重试，白烧轮次。命中环境
 * 分类的失败返回专用文案：明确"这不是命令的问题、不要重试、请报告用户检查环境"。
 *
 * 分类原则（保守）：只匹配已知的执行器异常签名与 PRoot 自身的输出方言；
 * 普通命令的语义错误（command not found、文件不存在等）绝不归类为环境问题。
 */
object ExecutionEnvironmentClassifier {

    /** 环境失败归因：[summary] 说明坏了什么，[advice] 告诉模型下一步该做什么。 */
    data class EnvironmentFailure(val summary: String, val advice: String)

    /**
     * A 类：执行器异常——命令根本没有被执行。[throwable] 沿 cause 链逐层匹配
     * LinuxRuntime / 执行器层的已知失败签名；未命中返回 null（调用方保持原语义）。
     */
    fun classify(throwable: Throwable): EnvironmentFailure? {
        var current: Throwable? = throwable
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val message = current.message.orEmpty()
            when {
                message.contains("Linux runtime is not ready") ->
                    return EnvironmentFailure(
                        summary = "Linux 运行时尚未初始化完成",
                        advice = "等待几秒后重试一次；若仍失败，说明运行时初始化异常，请告知用户重启应用或查看初始化状态",
                    )
                message.contains("QEMU") ->
                    return EnvironmentFailure(
                        summary = "QEMU 兼容环境不可用（未开启兼容开关或 qemu 兼容插件未安装）",
                        advice = "不要重试该命令；告知用户需要在设置中开启 QEMU 兼容模式并安装 qemu-x86-64-compat 插件",
                    )
                current is java.io.IOException && isProcessLaunchFailure(message) ->
                    return EnvironmentFailure(
                        summary = "沙箱执行器（PRoot）无法启动",
                        advice = "不要重试该命令；告知用户检查发行版安装状态与存储空间",
                    )
            }
            current = current.cause
            depth++
        }
        return null
    }

    /**
     * C 类：PRoot 自身的拒绝方言——命令进程是 proot 本尊，它在启动 guest 前失败
     * （rootfs 缺失、挂载点无效等）。签名取 proot 固定的输出前缀；普通命令的错误
     * 输出（shell/工具的 stderr）不含这些前缀，不会误判。
     */
    fun classifyDenial(result: CommandResult): EnvironmentFailure? {
        if (result.isSuccess) return null
        val stderr = result.stderr.trim()
        if (!stderr.startsWith("proot ") && !stderr.contains("\nproot ")) return null
        return EnvironmentFailure(
            summary = "沙箱（PRoot）拒绝了本次执行：${stderr.lineSequence().firstOrNull()?.take(120).orEmpty()}",
            advice = "不要重试该命令；这通常是发行版文件系统缺失或挂载异常，请告知用户重新安装或修复当前发行版",
        )
    }

    /** 环境失败的标准结果文案：与命令失败的反思文案明确区分，引导模型上报而非重试。 */
    fun resultText(failure: EnvironmentFailure): String = buildString {
        append("【环境故障】").append(failure.summary).append("。本次失败与命令内容无关。\n")
        append(failure.advice).append("。")
    }

    private fun isProcessLaunchFailure(message: String): Boolean =
        message.contains("Cannot run program") ||
            message.contains("no such file or directory", ignoreCase = true) && message.contains("proot", ignoreCase = true) ||
            message.contains("Permission denied") && message.contains("proot", ignoreCase = true) ||
            message.contains("execv failed", ignoreCase = true)

    private const val MAX_CAUSE_DEPTH = 6
}
