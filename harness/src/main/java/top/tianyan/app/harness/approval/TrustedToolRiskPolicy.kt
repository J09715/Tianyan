package top.tianyan.app.harness.approval

/** Trusted, compiled policy. Model output and MCP annotations cannot lower classifications. */
internal object TrustedToolRiskPolicy {
    data class Rule(val level: String, val reason: String, val readOnly: Boolean = false, val assistedAuto: Boolean = false)
    private const val HOST_REASON = "命令将通过 Shizuku 或 Root 修改真实 Android 宿主，可能改变系统设置、停用或卸载应用。"

    private fun MutableMap<String, Rule>.register(rule: Rule, vararg names: String) {
        names.forEach { check(put(it, rule) == null) { "Duplicate trusted tool risk rule: $it" } }
    }
    val hostRules: Map<String, Rule> = buildMap {
        register(Rule("low", "", readOnly = true),
            "status", "settings_get", "package_list", "app_list", "logcat", "device_status", "screen_observe")
        register(Rule("high", "操作将操控真实 Android 屏幕或启动应用。", assistedAuto = true),
            "screen_click", "screen_swipe", "screen_input_text", "screen_key", "app_launch")
        register(Rule("high", HOST_REASON, assistedAuto = true),
            "screen_double_click", "screen_long_press", "screen_scroll", "paste_text",
            "virtual_screen_task", "virtual_screen_click", "virtual_screen_double_click", "virtual_screen_long_press",
            "virtual_screen_swipe", "virtual_screen_scroll", "virtual_screen_key", "virtual_screen_input_text", "virtual_screen_screenshot")
        register(Rule("critical", HOST_REASON), "exec")
        register(Rule("critical", "操作将为指定 Android 用户卸载应用；系统应用通常可用 install-existing 恢复，但其数据可能丢失。"), "package_uninstall_user")
        register(Rule("high", "操作将修改真实 Android 系统设置。"), "settings_put")
        register(Rule("high", "操作将改变真实 Android 应用的启用状态。"), "package_disable", "package_enable", "app_freeze", "app_unfreeze")
        register(Rule("high", "操作将为真实 Android 应用授予权限。"), "app_grant_permission")
        register(Rule("high", "操作会把真实 Android 屏幕内容写入指定文件。"), "screen_capture")
        register(Rule("high", HOST_REASON), "virtual_screen_ensure", "virtual_screen_launch", "virtual_screen_set_text",
            "virtual_screen_wait", "virtual_screen_close", "virtual_screen_show", "virtual_screen_hide")
    }
    val readOnlyHostActions = hostRules.filterValues { it.readOnly }.keys
    val assistedHostActions = hostRules.filterValues { it.assistedAuto }.keys
    fun host(action: String): Rule = hostRules[action] ?: Rule("high", HOST_REASON)

    val browserRules: Map<String, Rule> = buildMap {
        register(Rule("low", "", readOnly = true),
            "browser_back", "browser_forward", "browser_refresh", "browser_list_tabs", "browser_close_tab",
            "browser_snapshot", "browser_scroll", "browser_screenshot", "browser_current_url", "browser_title",
            "browser_console_list", "browser_network_list")
        register(Rule("medium", "浏览器操作会改变页面状态或新开会话。"),
            "browser_open", "browser_navigate", "browser_page_source", "browser_console_clear", "browser_local_keys", "browser_session_keys")
        register(Rule("high", "浏览器操作将修改页面内容或写入本地存储。"),
            "browser_click", "browser_type", "browser_press", "browser_cookies_set", "browser_cookies_delete",
            "browser_local_get", "browser_local_set", "browser_local_delete", "browser_session_get", "browser_session_set", "browser_session_delete")
        register(Rule("critical", "浏览器操作涉及代码执行或读取敏感数据（Cookie/页面源码）。"), "browser_evaluate", "browser_cookies_get")
    }
}
