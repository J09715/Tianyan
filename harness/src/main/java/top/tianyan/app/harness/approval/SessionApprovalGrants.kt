package top.tianyan.app.harness.approval

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话内审批授权表（"本会话内记住"，对齐 Reasonix 的 allow-for-session 语义）。
 *
 * 用户批准某个待审批操作时可以勾选"本会话内同类操作不再询问"：host 把该操作的
 * **规范化类别**（不是精确参数）写入本表，同类后续操作免审批直接执行。
 *
 * 安全边界（刻意保守，照抄 Reasonix 的"无永久授权"原则）：
 * - 纯内存、per-session：进程退出即消失，会话删除即清理，**没有跨会话/永久授权**；
 * - critical 风险的请求调用方不得写入（双重防线：UI 隐藏 + HarnessLoop 拒绝）；
 * - 类别键只到"命令前缀 / 目标目录 / MCP server / 精确参数"粒度，绝不按工具名一揽子放行；
 * - 显式 deny 与策略引擎的其余分支不受影响——本表只在 `decision.required` 之后做豁免。
 *
 * 键类别：
 * - `cmd:` base/process/host 等带 command 参数的操作 → 命令前缀（剥环境变量后取前两个 token）；
 * - `dir:` write/edit/download 等带 path/destination 的操作 → 目标的父目录；
 * - `mcp:` mcp__server__tool / use_capability(call) → server + tool；
 * - `exact:` 其余操作 → argumentsJson 的 SHA-256。
 */
class SessionApprovalGrants() {

    private val grants = ConcurrentHashMap<String, LinkedHashMap<String, Long>>()

    /** @return 本会话内是否已有能覆盖该操作类别的授权。 */
    fun isGranted(sessionId: String, toolName: String, argumentsJson: String, riskLevel: String = "normal"): Boolean {
        if (sessionId.isBlank() || !isRememberableRisk(riskLevel)) return false
        val requestKey = grantKey(toolName, argumentsJson) ?: return false
        val sessionGrants = grants[sessionId] ?: return false
        return synchronized(sessionGrants) {
            sessionGrants.keys.any { stored -> covers(stored, requestKey) }
        }
    }

    /** 记录一条会话内授权；同类键刷新时间戳，超上限淘汰最旧。 */
    fun grant(sessionId: String, toolName: String, argumentsJson: String, riskLevel: String = "normal") {
        if (sessionId.isBlank() || !isRememberableRisk(riskLevel)) return
        val key = grantKey(toolName, argumentsJson) ?: return
        val sessionGrants = grants.getOrPut(sessionId) { LinkedHashMap() }
        synchronized(sessionGrants) {
            sessionGrants.remove(key)
            sessionGrants[key] = System.currentTimeMillis()
            while (sessionGrants.size > MAX_GRANTS_PER_SESSION) {
                val oldest = sessionGrants.keys.firstOrNull() ?: break
                sessionGrants.remove(oldest)
            }
        }
    }

    /** 会话删除/清理时调用；进程退出自然销毁。 */
    fun revokeSession(sessionId: String) {
        grants.remove(sessionId)
    }

    companion object {
        private const val MAX_GRANTS_PER_SESSION = 64

        private fun isRememberableRisk(riskLevel: String): Boolean =
            riskLevel.trim().lowercase() !in setOf("high", "critical")

        /**
         * 规范化类别键；无法归类（参数不可解析、无命令/路径/MCP 特征）返回 null——
         * 归不了类的操作一律走逐次审批，不做模糊放行。
         */
        internal fun grantKey(toolName: String, argumentsJson: String): String? {
            val args = runCatching { Json.parseToJsonElement(argumentsJson) }
                .getOrNull() as? JsonObject ?: return null
            val tool = toolName.lowercase()
            if (tool == "use_capability") {
                val server = args.stringOf("server").orEmpty().trim()
                val innerTool = args.stringOf("tool").orEmpty().trim()
                if (server.isNotBlank() && innerTool.isNotBlank()) return "mcp:$server:$innerTool"
            }
            if (tool.startsWith("mcp__")) {
                val parts = tool.split("__")
                val server = parts.getOrNull(1).orEmpty()
                val innerTool = parts.getOrNull(2).orEmpty()
                if (server.isBlank() || innerTool.isBlank()) return null
                return "mcp:$server:$innerTool"
            }
            args.stringOf("command")?.takeIf { it.isNotBlank() }?.let { command ->
                val prefix = commandPrefix(command) ?: return@let
                return "cmd:$prefix"
            }
            val path = args.stringOf("path") ?: args.stringOf("destination")
            if (tool in PATH_SCOPED_TOOLS && !path.isNullOrBlank()) {
                val parent = parentDir(path)
                return "dir:$parent"
            }
            return "exact:${sha256(argumentsJson)}"
        }

        /** 命令前缀：剥掉开头的环境变量赋值后取前两个 token（`git push origin main` → `git push`）。 */
        private fun commandPrefix(command: String): String? {
            val tokens = command.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                .dropWhile { ENV_ASSIGNMENT.matches(it) }
            if (tokens.isEmpty()) return null
            return tokens.take(2).joinToString(" ")
        }

        /** 授权是否覆盖请求：cmd 按前缀、dir 按目录层级、mcp/exact 按相等；类别不同不覆盖。 */
        private fun covers(stored: String, request: String): Boolean {
            val storedKind = stored.substringBefore(':')
            val requestKind = request.substringBefore(':')
            if (storedKind != requestKind) return false
            val storedValue = stored.substringAfter(':')
            val requestValue = request.substringAfter(':')
            return when (storedKind) {
                "cmd" -> requestValue == storedValue || requestValue.startsWith("$storedValue ")
                // storedValue 为 "/"（文件系统根）时前缀就是 "/" 本身，不能再拼一层斜杠
                "dir" -> requestValue == storedValue ||
                    requestValue.startsWith(if (storedValue == "/") "/" else "$storedValue/")
                else -> requestValue == storedValue
            }
        }

        /**
         * 目标的父目录键。绝对路径与工作区相对路径必须是不同键空间：
         * `/sdcard` 的父目录是 `/` 而不是 `.`——若归入 `.`，批准一次绝对根路径的下载
         * 会连带放行工作区根级文件的写入（键碰撞导致越权免审）。
         */
        private fun parentDir(path: String): String {
            val normalized = path.replace('\\', '/').trimEnd('/')
            if (normalized.isEmpty()) return "."
            val index = normalized.lastIndexOf('/')
            return when {
                index < 0 -> "."                       // 相对路径无目录 → 工作区根
                index == 0 -> "/"                      // 绝对路径直接挂在文件系统根
                else -> normalized.take(index)
            }
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        private val ENV_ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*")

        private val PATH_SCOPED_TOOLS = setOf("write", "edit", "download")

        private fun JsonObject.stringOf(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull
    }
}
