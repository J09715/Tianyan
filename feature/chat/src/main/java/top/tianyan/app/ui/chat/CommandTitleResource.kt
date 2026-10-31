package top.tianyan.app.ui.chat

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.ToolCall

/** Fixed labels for known executables; command arguments and script bodies stay in details. */
internal fun commandTitleResource(call: ToolCall): Int {
    val command = (call.args["command"] as? JsonPrimitive)?.contentOrNull ?: return R.string.chat_action_command
    val executable = command.trimStart().take(256).takeWhile { !it.isWhitespace() }.substringAfterLast('/')
    return when (executable) {
        "gradlew", "gradle" -> R.string.chat_step_command_gradle
        "android" -> R.string.chat_step_command_android
        "git" -> R.string.chat_step_command_git
        "rg", "grep", "find" -> R.string.chat_step_command_search
        "java", "javac" -> R.string.chat_step_command_java
        "python", "python3" -> R.string.chat_step_command_python
        "ls", "cat", "head", "tail" -> R.string.chat_step_command_files
        else -> R.string.chat_action_command
    }
}
