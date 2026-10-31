package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.GroupMessage
import top.tianyan.app.core.model.bot.GroupSenderType
import top.tianyan.app.core.model.bot.GroupToolStep
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class GroupToolCallPanelTest {
    @get:Rule val compose = createComposeRule()
    private val steps = listOf(GroupToolStep("call", "read", true, 18))

    @Test fun `tools collapse independently above the answer bubble`() {
        val message = GroupMessage("reply", "group", "bot", "成员", GroupSenderType.BOT,
            "正文回复", 0, steps = steps)
        compose.setContent { TianyanTheme {
            GroupMessageRow(message, null, true, true, {})
        } }
        compose.onNodeWithText("正文回复").assertExists()
        compose.onNodeWithTag("group-tools-details:reply").assertExists()
        compose.onNodeWithText("读取文件").assertExists()
        val panel = compose.onNodeWithTag("group-tools:reply").fetchSemanticsNode().boundsInRoot
        val answer = compose.onNodeWithText("正文回复").fetchSemanticsNode().boundsInRoot
        assertTrue("Tool frame must end before the answer", panel.bottom <= answer.top)
        compose.onNodeWithTag("group-tools-toggle:reply").performClick()
        compose.onNodeWithTag("group-tools-details:reply").assertDoesNotExist()
        compose.onNodeWithText("正文回复").assertExists()
        compose.onNodeWithTag("group-tools-toggle:reply").performClick()
        compose.onNodeWithTag("group-step:call").performClick()
        compose.onNodeWithText("read").assertExists()
        compose.onNodeWithText("正文回复").assertExists()
    }

    @Test fun `short tool lists can collapse and do not share state with other messages`() {
        compose.setContent { TianyanTheme { Column {
            GroupToolCallPanel("one", steps, false)
            GroupToolCallPanel("two", listOf(GroupToolStep("pending", "base")), false)
        } } }
        compose.onNodeWithTag("group-tools-toggle:one").performClick()
        compose.onNodeWithTag("group-tools-details:one").assertDoesNotExist()
        compose.onNodeWithTag("group-tools-details:two").assertExists()
        compose.onNodeWithText("1 项待核对").assertExists()
        compose.onNodeWithText("待核对").assertExists()
        compose.onNodeWithTag("group-tools-toggle:two").performClick()
        compose.onNodeWithTag("group-tools-details:two").assertDoesNotExist()
        compose.onNodeWithText("执行中").assertDoesNotExist()
    }
}
