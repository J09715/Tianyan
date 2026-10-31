package top.tianyan.app.ui.chat

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.components.*

@Composable
internal fun chatProcessColor() = runtimeProcessColor()

@Composable
internal fun ChatProcessContent(content: @Composable () -> Unit) = RuntimeProcessContent(content)

@Composable
internal fun ChatProcessIcon(name: RuntimeIconName, running: Boolean = false) = RuntimeProcessIcon(name, running)
