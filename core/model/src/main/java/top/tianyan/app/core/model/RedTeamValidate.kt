package top.tianyan.app.core.model

import java.io.File

/**
 * 写库前的校验与规范化，移植自上游 `validate.js`。
 *
 * 这些函数决定「什么值允许进库」，是**安全边界**所在（路径穿越、非法枚举），
 * 所以单独一处、单独测。
 */
object RedTeamValidate {

    val WEBSHELL_TYPES = listOf("behinder", "godzilla", "antSword", "antsword", "custom")
    val WEBSHELL_STATUSES = listOf("online", "offline", "dead", "unknown")

    /**
     * 规范化马类型：大小写不敏感、允许中文别名（冰蝎/哥斯拉/蚁剑）。
     *
     * 认不出就**报错而不是静默存原文** —— 面板的「用户连不上」红标与会话页的连接口令复制
     * 都按这个字段判断，存进去一个拼错的值不会报错，只会让这两处判定静默失效。
     *
     * @return 规范化后的值，或 null（空值合法 = 未声明）
     */
    fun normalizeShellType(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase()
        if (Regex("behinder|冰蝎").containsMatchIn(lower)) return "behinder"
        if (Regex("godzilla|哥斯拉").containsMatchIn(lower)) return "godzilla"
        if (Regex("antsword|蚁剑").containsMatchIn(lower)) return "antSword"
        if (lower == "custom" || lower == "custom-shell" || Regex("自定义|自研").containsMatchIn(raw)) return "custom"
        throw IllegalArgumentException(
            "webshell shell_type 非法：\"$value\"。" +
                "交付要求是**冰蝎马（behinder）/ 哥斯拉马（godzilla）**（用户要能自己连上）；" +
                "其它类型请填 custom（只作临时中转，面板会标\"用户连不上\"）。",
        )
    }

    /** 规范化马状态（界面按 online/offline/unknown 三态显示）。 */
    fun normalizeShellStatus(value: String?): String {
        val raw = value?.trim()?.lowercase().orEmpty()
        val aliases = mapOf("up" to "online", "alive" to "online", "down" to "offline", "failed" to "offline", "removed" to "dead")
        val normalized = aliases[raw] ?: raw
        if (normalized.isEmpty()) return "online"
        if (normalized !in WEBSHELL_STATUSES) {
            throw IllegalArgumentException(
                "webshell status 非法：\"$value\"。合法值只有 ${WEBSHELL_STATUSES.joinToString(" / ")}。",
            )
        }
        return normalized
    }

    /**
     * 路径必须在允许的根目录内（防目录穿越）。
     *
     * 为什么需要：攻击文件与 PoC 读的是**库里存的 path 列**，而那个列是智能体自己写进去的
     * —— 也就是不可信输入。一旦填成 `/etc/passwd` 或 `~/.ssh/id_rsa`，
     * 界面上点一下就把文件读出来了。读路径与写路径共用同一处判定。
     *
     * @return 规范化后的绝对路径
     * @throws IllegalArgumentException 越界时抛错（调用方按「查不到」处理，**不要静默返回内容**）
     */
    fun assertPathWithin(target: String?, allowedRoots: List<String>): String {
        val abs = File(target.orEmpty()).absoluteFile.toPath().normalize().toString()
        val roots = allowedRoots.filter { it.isNotEmpty() }
            .map { File(it).absoluteFile.toPath().normalize().toString() }
        val within = roots.any { root -> abs == root || abs.startsWith(root.trimEnd(File.separatorChar) + File.separator) }
        if (!within) {
            throw IllegalArgumentException(
                "path outside allowed roots: $abs（允许的根：${roots.joinToString(", ")}）。" +
                    "这条记录的文件路径不在本靶标目录内，已拒绝读取。",
            )
        }
        return abs
    }

    /** 便捷判定：越界返回 null，供界面按「查不到」处理而不是抛到 UI 层。 */
    fun pathWithinOrNull(target: String?, allowedRoots: List<String>): String? =
        runCatching { assertPathWithin(target, allowedRoots) }.getOrNull()
}