package top.tianyan.app.ui.settings

import top.tianyan.app.ui.components.RuntimeIconName

internal fun parseIconName(name: String): RuntimeIconName {
    return when (name.lowercase()) {
        "play" -> RuntimeIconName.Play
        "check" -> RuntimeIconName.Check
        "alert" -> RuntimeIconName.Alert
        "code" -> RuntimeIconName.Code
        "plus" -> RuntimeIconName.Plus
        "package" -> RuntimeIconName.Package
        "search" -> RuntimeIconName.Search
        "brain" -> RuntimeIconName.Brain
        "bot" -> RuntimeIconName.Bot
        "chat" -> RuntimeIconName.Chat
        "refresh" -> RuntimeIconName.Refresh
        "terminal" -> RuntimeIconName.Terminal
        "tool", "wrench" -> RuntimeIconName.Wrench
        else -> RuntimeIconName.Play
    }
}
