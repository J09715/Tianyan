package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupReasoningPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `reasoning viewport grows with content up to its maximum height`() {
        val reasoning = mutableStateOf("short thought")
        compose.setContent { TianyanTheme { GroupReasoningPanel("message", reasoning.value, true) } }
        val maximum = with(compose.density) { 160.dp.roundToPx() }
        val shortHeight = compose.onNodeWithTag("group-reasoning-viewport")
            .fetchSemanticsNode().size.height
        assertTrue("short reasoning should not fill the max viewport", shortHeight < maximum)
        compose.runOnIdle { reasoning.value = (1..100).joinToString("\n") { "Reasoning line $it" } }
        compose.waitForIdle()
        val tallHeight = compose.onNodeWithTag("group-reasoning-viewport")
            .fetchSemanticsNode().size.height
        assertEquals(maximum, tallHeight)
        compose.runOnIdle {
            val range = compose.onNodeWithTag("group-reasoning-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertTrue(range.maxValue() > 0)
            assertEquals(range.maxValue(), range.value(), 2f)
        }
    }

    @Test fun `reading older thoughts pauses following until requested`() {
        val reasoning = mutableStateOf((1..100).joinToString("\n") { "Reasoning line $it" })
        compose.setContent { TianyanTheme { GroupReasoningPanel("message", reasoning.value, true) } }
        compose.waitForIdle()
        compose.onNodeWithTag("group-reasoning-scroll").performTouchInput { swipeDown() }
        compose.waitForIdle()
        var position = 0f
        compose.runOnIdle {
            val range = compose.onNodeWithTag("group-reasoning-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            position = range.value()
            assertTrue(position < range.maxValue())
            reasoning.value += "\nAn additional thought"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val range = compose.onNodeWithTag("group-reasoning-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(position, range.value(), 2f)
        }
        compose.onNodeWithTag("group-reasoning-follow").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            val range = compose.onNodeWithTag("group-reasoning-scroll").fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(range.maxValue(), range.value(), 2f)
        }
    }
}
