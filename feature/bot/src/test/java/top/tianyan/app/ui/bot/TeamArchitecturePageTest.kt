package top.tianyan.app.ui.bot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.theme.TianyanTheme
import top.tianyan.app.feature.components.R as ComponentsR

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamArchitecturePageTest {
    @get:Rule val compose = createComposeRule()
    private val team = GroupTeam("root", setOf("root", "worker"))
    private val members = team.memberIds.map { Bot(it, it, "role", "", "", sessionId = it, createdAt = 1, updatedAt = 1) }

    @Test fun previewOpensLargerPageAndBackRestoresPreview() {
        val wideTeam = team.copy(memberIds = team.memberIds + (1..6).map { "member$it" })
        compose.setContent { TianyanTheme { Box(Modifier.width(280.dp)) { TeamArchitectureGraph(wideTeam, GroupGovernance(), members) } } }
        val previewHeight = compose.onNodeWithTag("team-graph-preview").fetchSemanticsNode().boundsInRoot.height
        compose.onNodeWithTag("team-graph-open").performClick()
        val viewport = compose.onNodeWithTag("team-graph-viewport").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Full page should use more vertical space than the preview", viewport.height > previewHeight)
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.onNodeWithContentDescription(context.getString(ComponentsR.string.components_back)).performClick()
        compose.onNodeWithTag("team-graph-viewport").assertDoesNotExist()
        compose.onNodeWithTag("team-graph-preview").assertIsDisplayed()
    }

    @Test fun editablePageReturnsSelectedMemberToExistingEditor() {
        var selected: String? = null
        compose.setContent { TianyanTheme { TeamArchitectureGraph(team, GroupGovernance(), members, editable = true) { selected = it } } }
        compose.onNodeWithTag("team-graph-open").performClick()
        compose.onAllNodesWithTag("team-graph-member-root").onLast().performClick()
        compose.runOnIdle { assertEquals("root", selected) }
        compose.onNodeWithTag("team-graph-viewport").assertDoesNotExist()
        compose.onNodeWithTag("team-graph-preview").assertExists()
    }

    @Test fun chatPageKeepsMemberNodesReadOnlyWhileAllowingZoom() {
        compose.setContent { TianyanTheme { TeamGraphDialog(team, GroupGovernance(), members, {}) } }
        compose.onNodeWithTag("team-graph-viewport").assertIsDisplayed()
        compose.onNodeWithTag("team-graph-member-root").assertHasNoClickAction()
        compose.onNodeWithText("全图").performClick()
        compose.onNodeWithTag("team-graph-member-worker").assertIsDisplayed()
        compose.onNodeWithText("＋").performClick()
        compose.onNodeWithTag("team-graph-viewport").assertIsDisplayed()
    }
}
