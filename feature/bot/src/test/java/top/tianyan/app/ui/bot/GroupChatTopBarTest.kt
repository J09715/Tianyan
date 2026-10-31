package top.tianyan.app.ui.bot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupChatTopBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `top bar in chat mode shows cyber studio button and switches mode`() {
        var switchedMode: GroupChatViewMode? = null

        compose.setContent {
            TianyanTheme {
                GroupChatTopBar(
                    viewMode = GroupChatViewMode.CHAT,
                    groupName = "天衍项目群",
                    statusText = "2 位成员",
                    busy = false,
                    workSummary = GroupWorkSummary(0, 0, 0),
                    exporting = false,
                    onBack = {},
                    onSwitchMode = { switchedMode = it },
                    onExportDiagnostics = {},
                    onShowInfo = {},
                )
            }
        }

        compose.onNodeWithTag("topbar-cyber-studio-btn").assertIsDisplayed().performClick()
        assertEquals(GroupChatViewMode.STUDIO, switchedMode)
    }

    @Test
    fun `top bar in studio mode shows back to chat button`() {
        var switchedMode: GroupChatViewMode? = null

        compose.setContent {
            TianyanTheme {
                GroupChatTopBar(
                    viewMode = GroupChatViewMode.STUDIO,
                    groupName = "天衍项目群",
                    statusText = "2 位成员",
                    busy = false,
                    workSummary = GroupWorkSummary(0, 0, 0),
                    exporting = false,
                    onBack = {},
                    onSwitchMode = { switchedMode = it },
                    onExportDiagnostics = {},
                    onShowInfo = {},
                )
            }
        }

        compose.onNodeWithTag("topbar-back-to-chat-btn").assertIsDisplayed().performClick()
        assertEquals(GroupChatViewMode.CHAT, switchedMode)
    }
}
