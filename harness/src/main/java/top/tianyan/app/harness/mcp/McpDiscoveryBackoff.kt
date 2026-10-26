package top.tianyan.app.harness.mcp

internal const val MCP_DISCOVERY_COOLDOWN_BASE_MS = 30_000L
internal const val MCP_DISCOVERY_COOLDOWN_MAX_MS = 5 * 60_000L

/** Per execution target: 30s → 1min → 2min → 4min → 5min. */
internal fun mcpDiscoveryCooldownMs(failureStreak: Int): Long {
    if (failureStreak <= 1) return MCP_DISCOVERY_COOLDOWN_BASE_MS
    val shift = (failureStreak - 1).coerceAtMost(16)
    return (MCP_DISCOVERY_COOLDOWN_BASE_MS shl shift).coerceAtMost(MCP_DISCOVERY_COOLDOWN_MAX_MS)
}
