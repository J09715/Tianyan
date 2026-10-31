package top.tianyan.app.core.model

/** Client STDIO subprocess idle policy. HTTP MCP sessions are unaffected. */
object McpIdlePolicy {
    const val DEFAULT_MINUTES = 10
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 60
    fun normalize(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)
    fun timeoutMillis(minutes: Int): Long = normalize(minutes) * 60_000L
}
