package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.PixelAvatarRecipe
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PixelAvatarDesignerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `draft survives recreation saves as editable character and cancellation preserves original`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val seed = mutableStateOf("阿策")
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TianyanTheme { BotAvatarEditor(seed.value, "阿策", "#3D5AFE", true,
                onChange = { seed.value = it }, onLoading = {}) }
        }
        val edit = context.getString(R.string.fbot_pixel_edit)
        compose.onNodeWithText(edit).performClick()
        compose.onNodeWithTag("pixel-choice-${R.string.fbot_pixel_kind}-2").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("pixel-choice-${R.string.fbot_pixel_kind}-2").assertIsSelected()
        compose.onNodeWithText(context.getString(R.string.fbot_pixel_apply)).performClick()
        compose.runOnIdle { assertEquals(2, PixelAvatarRecipe.decode(seed.value)?.kind) }
        compose.onNodeWithTag("bot-pixel-character").assertExists()
        val saved = seed.value
        compose.onNodeWithText(edit).performClick()
        compose.onNodeWithTag("pixel-choice-${R.string.fbot_pixel_kind}-2").assertIsSelected()
        compose.onNodeWithTag("pixel-choice-${R.string.fbot_pixel_kind}-0").performClick()
        compose.onNodeWithText(context.getString(R.string.fbot_cancel)).performClick()
        compose.runOnIdle { assertEquals(saved, seed.value) }
    }

    @Test fun `expanded options and preview states can be selected`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val seed = mutableStateOf("阿策")
        compose.setContent {
            TianyanTheme {
                BotAvatarEditor(seed.value, "阿策", "#3D5AFE", true,
                    onChange = { seed.value = it }, onLoading = {})
            }
        }
        val edit = context.getString(R.string.fbot_pixel_edit)
        compose.onNodeWithText(edit).performClick()
        // Select Cyborg (kind = 3)
        compose.onNodeWithTag("pixel-choice-${R.string.fbot_pixel_kind}-3").performClick()
        // Preview thinking state
        compose.onNodeWithText(context.getString(R.string.fbot_pixel_state_thinking)).performClick()
        compose.onNodeWithTag("pixel-avatar-preview").assertExists()
        // Preview executing state
        compose.onNodeWithText(context.getString(R.string.fbot_pixel_state_executing)).performClick()
        compose.onNodeWithTag("pixel-avatar-preview").assertExists()
        // Apply
        compose.onNodeWithText(context.getString(R.string.fbot_pixel_apply)).performClick()
        compose.runOnIdle {
            val recipe = requireNotNull(PixelAvatarRecipe.decode(seed.value))
            assertEquals(3, recipe.kind)
        }
    }
}
