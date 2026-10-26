package top.tianyan.app.core.model.bot

import kotlinx.serialization.Serializable

/** Applies to delegated native turns; provider transport timeouts remain independent. */
@Serializable
data class GroupExecutionLimits(val warnAfterMs: Long = 120_000, val stopAfterMs: Long = 300_000) {
    init { require(warnAfterMs > 0 && stopAfterMs > warnAfterMs && stopAfterMs <= 3_600_000) }
}
