package top.tianyan.app.ui.chat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

class CommandTitleResourceTest {
    private fun label(command: String) = commandTitleResource(ToolCall("c", 1, HarnessTool.BASE,
        buildJsonObject { put("command", command) }))

    @Test fun knownExecutablesGetFixedLabelsWithoutDisplayingArguments() {
        assertEquals(R.string.chat_step_command_gradle, label("./gradlew assembleDebug --password SECRET"))
        assertEquals(R.string.chat_step_command_android, label("/opt/tianyan/bin/android sdk list"))
        assertEquals(R.string.chat_step_command_git, label(" git status"))
        assertEquals(R.string.chat_step_command_search, label("rg \"private text\" /workspace"))
    }

    @Test fun scriptsAndUnknownCommandsStayGenericWithoutGuessingFromTheirBody() {
        assertEquals(R.string.chat_action_command, label("echo 'gradlew assembleDebug'"))
        assertEquals(R.string.chat_action_command, label("export PATH=/opt/bin; gradle build"))
        assertEquals(R.string.chat_action_command, label("private-command --token SECRET"))
        assertEquals(R.string.chat_action_command, label(""))
    }
}
