package top.tianyan.app.ui.bot

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupAssignmentContinueDialogTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `failed admission keeps instruction and retry reuses its idempotency key`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val task = BotAssignment("task", "group", "member", "Member", "Build", "original", BotAssignmentStatus.BLOCKED,
            createdAt = 1, startedAt = 1, independent = true)
        val keys = mutableListOf<String>()
        var dismissed = false
        compose.setContent {
            TianyanTheme {
                GroupAssignmentContinueDialog(task, { dismissed = true }) { id, instruction, key ->
                    assertEquals("task", id)
                    assertEquals("inspect outputs first", instruction)
                    keys += key
                    if (keys.size == 1) error("offline")
                }
            }
        }
        val submit = context.getString(R.string.fbot_assignment_submit)
        compose.onNodeWithText(submit).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("inspect outputs first")
        compose.onNodeWithText(submit).performClick()
        compose.waitUntil(5_000) { keys.size == 1 }
        compose.onNodeWithText("offline").assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("inspect outputs first")
        compose.onNodeWithText(submit).performClick()
        compose.waitUntil(5_000) { dismissed }
        compose.runOnIdle { assertEquals(2, keys.size); assertEquals(keys[0], keys[1]) }
    }
}
