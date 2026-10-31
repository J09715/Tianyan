package top.tianyan.app.core.tools

import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.tools.InstallEvent

/** Redact secrets from an install event before it reaches logs, tasks or the UI. */
internal fun InstallEvent.redacted(redactor: SecretRedactor): InstallEvent = when (this) {
    is InstallEvent.Progress -> copy(message = redactor.redact(message))
    is InstallEvent.Output -> copy(line = redactor.redact(line))
    is InstallEvent.Failed -> copy(message = redactor.redact(message))
    else -> this
}

/** Durable install-log row for an event, keyed by the owning distro. */
internal fun InstallEvent.toLog(distroId: String): InstallLogEntity = when (this) {
    is InstallEvent.Started -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "STARTED", message = "开始安装")
    is InstallEvent.Progress -> InstallLogEntity(
        distroId = distroId,
        toolId = toolId,
        event = "PROGRESS_${phase.name}",
        message = message,
    )
    is InstallEvent.Output -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "OUTPUT", message = line)
    is InstallEvent.Completed -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "COMPLETED", message = "安装完成${version?.let { "：$it" } ?: ""}")
    is InstallEvent.Failed -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "FAILED", message = message)
    is InstallEvent.RolledBack -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "ROLLED_BACK", message = "已回滚安装事务")
    is InstallEvent.Cancelled -> InstallLogEntity(distroId = distroId, toolId = toolId, event = "CANCELLED", message = "用户取消安装")
}

internal fun ToolActionResult.toUninstallOutcome(): UninstallOutcome =
    UninstallOutcome(success, message)

internal fun CommandResult.toUninstallOutcome(): UninstallOutcome =
    UninstallOutcome(isSuccess, stderr.ifBlank { stdout })
