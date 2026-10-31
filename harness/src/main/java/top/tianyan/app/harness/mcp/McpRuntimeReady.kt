package top.tianyan.app.harness.mcp

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import top.tianyan.app.core.model.RuntimeState
import kotlin.time.Duration.Companion.milliseconds

/** Wait budget for restoreInstalledState() racing MCP STDIO discovery at process start. */
internal const val RUNTIME_READY_WAIT_MS = 20_000L

/**
 * STDIO MCP servers need a Ready PRoot runtime. App start warms tools in parallel with
 * restore, so discovery used to fail immediately with "Call initialize() first".
 */
internal suspend fun awaitLinuxRuntimeReady(
    state: StateFlow<RuntimeState>,
    timeoutMs: Long = RUNTIME_READY_WAIT_MS,
): Boolean {
    if (state.value is RuntimeState.Ready) return true
    return withTimeoutOrNull(timeoutMs.milliseconds) {
        state.first { it is RuntimeState.Ready }
        true
    } ?: false
}
