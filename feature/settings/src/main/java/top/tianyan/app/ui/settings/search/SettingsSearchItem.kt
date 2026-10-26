package top.tianyan.app.ui.settings.search

import androidx.compose.ui.graphics.Color
import top.tianyan.app.ui.components.RuntimeIconName

/**
 * 天衍设置与全局功能大类划分
 */
enum class SettingsSearchCategory(val label: String, val themeColor: Color) {
    AGENT("智能体与 AI 模型", Color(0xFF6366F1)),
    LINUX_ENV("Linux 容器与存储", Color(0xFF10B981)),
    APPEARANCE("外观、字号与终端", Color(0xFF8B5CF6)),
    SYSTEM_DEV("系统保活与开发者诊断", Color(0xFFF59E0B)),
    WORKSHOP("工坊与离线构建", Color(0xFFEC4899)),
    CORE_FEATURE("天衍核心直达", Color(0xFF3B82F6)),
    ABOUT("关于与官方社区", Color(0xFF06B6D4)),
}

/**
 * 搜索跳转目标定义
 */
enum class SettingsSearchTarget {
    // 智能体板块
    MODEL_PROFILES,
    MODEL_EDITOR_NEW,
    LOCAL_LLM,
    QUICK_PHRASES,
    STATS,
    TOOL_CENTER,
    CC_SWITCH,
    AGENT_EXECUTION,
    AGENT_SUBAGENTS,
    AGENT_SKILLS,
    MCP_SETTINGS,

    // Linux 容器与存储
    DISTRO_MANAGEMENT,
    STORAGE_USAGE,
    STORAGE_MOUNTS,
    ENV_VARS,
    SSH_SETTINGS,
    FTP_SETTINGS,
    WEB_CHAT,
    APP_MANAGEMENT,
    PRIVILEGE_MODE,

    // 外观与终端
    APPEARANCE_SETTINGS,
    THEME_MODE,
    DYNAMIC_COLOR,
    FONT_SCALE,
    TERMINAL_SETTINGS,
    LANGUAGE_SETTINGS,

    // 系统保活与开发者
    BATTERY_OPTIMIZATION,
    PHANTOM_PROCESS,
    DEVELOPER_OPTIONS,
    ADB_LOGCAT,
    CUSTOM_ITERATION,
    PERMISSION_GUIDE,

    // 工坊与构建环境
    WORKSHOP_SETTINGS,
    WORKSHOP_ENVIRONMENT,
    WORKSHOP_SIGNING,
    WORKFLOWS,

    // 核心主功能直达
    NAV_HOME,
    NAV_AGENT_CHAT,
    NAV_WORKSPACE,
    NAV_TERMINAL,
    NAV_BROWSER,

    // 关于与社区
    ABOUT_COMMUNITY,
    ABOUT_UPDATE,
}

/**
 * 单个全局功能选项搜索索引定义
 */
data class SettingsSearchItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: SettingsSearchCategory,
    val target: SettingsSearchTarget,
    val icon: RuntimeIconName,
    val keywords: List<String> = emptyList(),
    val badge: String? = null,
    val breadcrumb: String? = null,
)
