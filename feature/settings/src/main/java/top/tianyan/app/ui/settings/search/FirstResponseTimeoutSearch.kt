package top.tianyan.app.ui.settings.search

import top.tianyan.app.ui.components.RuntimeIconName

internal val firstResponseTimeoutSearchItem = SettingsSearchItem(
    id = "agent_first_response_timeout",
    title = "等待模型首个响应",
    subtitle = "首个响应超时默认 90 秒，可调高或关闭。超时不代表上游报错",
    category = SettingsSearchCategory.AGENT,
    target = SettingsSearchTarget.AGENT_EXECUTION,
    icon = RuntimeIconName.Alarm,
    keywords = listOf("timeout", "first response", "90", "首个响应", "超时", "xiangying"),
    breadcrumb = "智能体与 AI 模型 > Agent 执行参数 > 首个响应超时",
)
