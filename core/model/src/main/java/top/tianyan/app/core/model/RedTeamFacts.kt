package top.tianyan.app.core.model

/** Stable fact kinds shared by runtime tools and the mobile workspace. */
enum class RedTeamFactKind(val id: String) {
    ENGAGEMENT("engagement"),
    ASSET("asset"),
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

enum class RedTeamRole(val id: String, val displayName: String) {
    RECON("recon", "信息收集"),
    ASSET("asset", "资产梳理"),
    VULN_SCAN("vuln-scan", "漏洞发现"),
    EXPLOIT("exploit", "漏洞验证"),
    INTERNAL("internal", "内网渗透");
}

data class RedTeamPreflightResult(
    val available: List<String> = emptyList(),
    val missing: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
) {
    val ready: Boolean get() = missing.isEmpty()
}
