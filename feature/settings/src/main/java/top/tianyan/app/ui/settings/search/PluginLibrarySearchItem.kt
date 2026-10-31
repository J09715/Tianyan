package top.tianyan.app.ui.settings.search

import top.tianyan.app.ui.components.RuntimeIconName

internal val pluginLibrarySearchItem = SettingsSearchItem(
    id = "plugin_library", title = "下载插件与更新",
    subtitle = "下载插件、检查更新及切换 GitHub / Gitee 仓库源",
    category = SettingsSearchCategory.CORE_FEATURE, target = SettingsSearchTarget.PLUGIN_LIBRARY,
    icon = RuntimeIconName.Download,
    keywords = listOf("插件", "插件仓库", "仓库", "下载", "安装", "更新", "切换源", "github", "gitee", "plugin", "library"),
    breadcrumb = "设置 > 插件中心 > 下载插件",
)
