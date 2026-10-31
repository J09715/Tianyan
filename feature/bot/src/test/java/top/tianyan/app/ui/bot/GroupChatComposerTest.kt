package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class GroupChatComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `busy group permits a new message and empty input exposes stop`() {
        val value = mutableStateOf(TextFieldValue("追加说明"))
        var sent = 0
        var stopped = 0
        compose.setContent { TianyanTheme {
            GroupChatComposer(emptyList(), value.value, emptyList(), false, true, false,
                { value.value = it }, {}, {}, {}, { sent++ }, { stopped++ })
        } }
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sent)
        assertEquals(0, stopped)
        compose.runOnIdle { value.value = TextFieldValue() }
        compose.onNodeWithContentDescription("停止执行").assertIsEnabled().performClick()
        assertEquals(1, stopped)
    }

    @Test fun `stopping prevents duplicate stop actions`() {
        compose.setContent { TianyanTheme {
            GroupChatComposer(emptyList(), TextFieldValue(), emptyList(), false, true, true,
                {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("停止执行").assertIsNotEnabled()
    }
}
