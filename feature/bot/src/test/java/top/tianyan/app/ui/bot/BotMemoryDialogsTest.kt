package top.tianyan.app.ui.bot

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.BotMemoryDiff
import top.tianyan.app.core.model.bot.BotMemoryDocument
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotMemoryDialogsTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun `failed save retains full draft until user explicitly reloads current contents`() {
        var server = BotMemoryDocument("preferences.md", "original", "v1")
        var saves = 0
        var dismissed = false
        compose.setContent {
            TianyanTheme {
                BotMemoryEditorDialog(server.path, { dismissed = true }, load = { server }) { document, text ->
                    assertEquals("v1", document.revision)
                    assertEquals("my draft", text)
                    saves++
                    error("concurrent edit; draft retained")
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("my draft")
        compose.onNodeWithText(context.getString(R.string.fbot_save)).performClick()
        compose.onNodeWithText("concurrent edit; draft retained").assertExists()
        compose.onNode(hasSetTextAction()).assertTextContains("my draft")
        assertEquals(1, saves)
        assertFalse(dismissed)
        server = server.copy(content = "current server contents", revision = "v2")
        compose.onNodeWithText(context.getString(R.string.fbot_memory_reload)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).assertTextContains("current server contents")
    }

    @Test fun `restored editor preserves original conflict revision instead of silently rebasing draft`() {
        val restoration = StateRestorationTester(compose)
        val retainedDraft = BotMemoryEditorState()
        var server = BotMemoryDocument("preferences.md", "original", "v1")
        var savedRevision: String? = null
        restoration.setContent {
            TianyanTheme {
                BotMemoryEditorDialog(server.path, {}, state = retainedDraft, load = { server }) { document, text ->
                    savedRevision = document.revision
                    assertEquals("unsaved draft", text)
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextReplacement("unsaved draft")
        server = server.copy(content = "agent changed this", revision = "v2")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNode(hasSetTextAction()).assertTextContains("unsaved draft")
        compose.onNodeWithText(context.getString(R.string.fbot_save)).performClick()
        compose.waitUntil(5_000) { savedRevision != null }
        assertEquals("v1", savedRevision)
    }

    @Test fun `commit diff shows exact patch and explicit truncation notice`() {
        compose.setContent {
            TianyanTheme {
                BotMemoryDiffDialog("12345678", {}) { BotMemoryDiff("12345678", "-old\n+new", true) }
            }
        }
        compose.onNodeWithText("-old\n+new").assertExists()
        compose.onNodeWithText(context.getString(R.string.fbot_memory_diff_truncated)).assertExists()
    }
}
