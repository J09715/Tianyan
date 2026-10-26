package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.ui.theme.TianyanTheme
import top.tianyan.app.core.model.ChunkedText

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExpandableGroupTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `chunked markdown keeps earlier paragraphs while streaming and after completion`() {
        val builder = ChunkedText.Builder()
        builder.append("stable paragraph\n\n")
        val body = mutableStateOf(builder.snapshot())
        val live = mutableStateOf(true)
        compose.setContent { TianyanTheme { ExpandableGroupText("stream", body.value, live.value) } }
        compose.onNodeWithText("stable paragraph").assertExists()
        compose.runOnIdle { builder.append("**new tail**"); body.value = builder.snapshot() }
        compose.onNodeWithText("stable paragraph").assertExists()
        compose.onNodeWithText("new tail").assertExists()
        compose.runOnIdle { live.value = false }
        compose.onNodeWithText("stable paragraph").assertExists()
        compose.onNodeWithText("new tail").assertExists()
    }

    @Test fun `collapsed report restores its complete original text`() {
        val text = "report ".repeat(220) + "THE END"
        compose.setContent { TianyanTheme { ExpandableGroupText("report", text, false, markdown = false) } }
        compose.onNodeWithText(text).assertDoesNotExist()
        compose.onNodeWithText("展开全文").performClick()
        compose.onNodeWithText(text).assertExists()
    }

    @Test fun `streaming text stays visible until completion`() {
        val streaming = mutableStateOf(true)
        val text = "report ".repeat(220)
        compose.setContent { TianyanTheme { ExpandableGroupText("report", text, streaming.value, markdown = false) } }
        compose.onNodeWithText(text).assertExists()
        compose.onNodeWithText("展开全文").assertDoesNotExist()
        compose.runOnIdle { streaming.value = false }
        compose.onNodeWithText("展开全文").assertExists()
        compose.onNodeWithText(text).assertDoesNotExist()
    }
}
