package top.tianyan.app.core.model

/** Per-session red-team orchestration mode and its bounded execution phases. */
enum class RedTeamMode(val id: String, val displayName: String) {
    OFF("off", "普通 Agent"),
    RED_TEAM("red_team", "红队模式");

    companion object {
        fun fromId(id: String?): RedTeamMode = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF
    }
}

enum class RedTeamPhase(val id: String, val displayName: String) {
    IDLE("idle", "待绑定目标"),
    AWAITING_CONFIRMATION("awaiting_confirmation", "等待目标确认"),
    RECON("recon", "信息收集"),
    INTERNET("internet", "互联网资产"),
    BOUNDARY("boundary", "边界验证"),
    INTERNAL("internal", "内网资产"),
    TARGET("target", "目标系统"),
    PAUSED("paused", "已暂停"),
    COMPLETED("completed", "已完成");

    companion object {
        fun fromId(id: String?): RedTeamPhase = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: IDLE
    }
}
