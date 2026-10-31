package top.tianyan.app.runtime.doctor

import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

/**
 * 沙箱探针结果：显式区分「命令执行完成」与「探测不可达」。
 *
 * 旧实现把执行异常与命令超时折叠成同一个 null，于是门禁拒绝（前台重型命令名额未释放）、
 * 沙箱未就绪、进程启动失败等异常都被一律显示成「探针超时」。这里把两类原因分开，
 * 调用方得以按真实原因给出灰牌文案。
 */
internal sealed interface ProbeOutcome {
    /** 命令已执行完成；结果可能是成功，也可能是非 0 退出。 */
    data class Executed(val command: CommandResult) : ProbeOutcome

    /** 探测不可达：[timedOut] 为真表示命令触达执行超时，否则 [reason] 是执行异常原文。 */
    data class Unreachable(val timedOut: Boolean, val reason: String) : ProbeOutcome
}

/** 与 ProcessShellExecutor 的超时退出码一致：超时以该退出码返回结果，不抛异常。 */
private const val TIMEOUT_EXIT_CODE = 124

/**
 * 探针统一入口：执行异常后以双倍超时重试一次。
 * - 命令执行完成：归一为 [ProbeOutcome.Executed]；超时退出码归一为不可达（timedOut = true）；
 * - 连续两次抛异常：归一为不可达（timedOut = false），保留异常原文供调用方如实展示。
 *
 * 探测不可达 ≠ 配置异常：调用方应给灰牌 UNKNOWN，绝不误报 WARNING/ERROR。
 */
internal suspend fun LinuxRuntime.probeSandbox(commandLine: String, timeoutMs: Long): ProbeOutcome {
    var failure: Throwable? = null
    repeat(2) { attempt ->
        val outcome = runCatching {
            execute(ShellCommand(commandLine, timeoutMs = timeoutMs * (attempt + 1)))
        }
        val result = outcome.getOrNull()
        if (result != null) {
            return if (result.exitCode == TIMEOUT_EXIT_CODE) {
                ProbeOutcome.Unreachable(
                    timedOut = true,
                    reason = result.outputDiagnostic ?: "命令在 ${timeoutMs}ms 内未返回",
                )
            } else {
                ProbeOutcome.Executed(result)
            }
        }
        failure = outcome.exceptionOrNull()
    }
    return ProbeOutcome.Unreachable(
        timedOut = false,
        reason = failure?.message?.takeIf { it.isNotBlank() } ?: failure?.javaClass?.simpleName.orEmpty(),
    )
}

/**
 * 探测可达时的命令结果。调用方已用不可达判定（灰牌 + 提前 return）排除异常分支后使用。
 */
internal fun ProbeOutcome.requireExecuted(): CommandResult =
    (this as? ProbeOutcome.Executed)?.command
        ?: error("探测不可达时不应读取执行结果：$this")
