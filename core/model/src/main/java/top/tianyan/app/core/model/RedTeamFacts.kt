package top.tianyan.app.core.model

/** Stable fact kinds shared by runtime tools and the mobile workspace. */
enum class RedTeamFactKind(val id: String) {
    ENGAGEMENT("engagement"),
    ASSET("asset"),
    /** 资产关系边：src/dst 均为 (kind,id)，用于图谱与横向路径推理。 */
    EDGE("edge"),
    VULNERABILITY("vulnerability"),
    CREDENTIAL("credential"),
    ACCESS_SESSION("access_session"),
    WEBSHELL("webshell"),
    TUNNEL("tunnel"),
    ATTACK_STEP("attack_step"),
    ATTACK_FILE("attack_file"),
    SCORE_HIT("score_hit"),
    REPORT("report"),
    KNOWLEDGE("knowledge"),
    SKILL("skill"),
    AGENT("agent"),
    EVENT("event");
}

/**
 * 红队角色，逐条对齐上游 `ROLE_TITLES`。
 *
 * `plan` 是主会话（指挥者），不是派活目标；派给子智能体的只有 [dispatchable] 那 5 个。
 * 注意 code 是 `assess` 而不是 `asset` —— 上游专门注释过「写死 4 个值曾漏掉 assess」，
 * 角色 code 会被角色提示词查询和并发统计按字符串匹配，拼错就等于该角色查不到提示词。
 */
enum class RedTeamRole(val id: String, val displayName: String) {
    PLANNER("plan", "主会话（指挥）"),
    RECON("recon", "信息收集"),
    ASSESS("assess", "资产梳理"),
    VULN_SCAN("vuln-scan", "漏洞发现"),
    EXPLOIT("exploit", "漏洞利用"),
    INTERNAL("internal", "内网渗透");

    companion object {
        /** 可派给子智能体的角色，顺序与上游 ROLE_ORDER 一致。 */
        val dispatchable: List<RedTeamRole> = listOf(RECON, ASSESS, VULN_SCAN, EXPLOIT, INTERNAL)

        fun fromId(id: String?): RedTeamRole? =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

data class RedTeamPreflightResult(
    val available: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val ready: Boolean get() = missing.isEmpty()
}
