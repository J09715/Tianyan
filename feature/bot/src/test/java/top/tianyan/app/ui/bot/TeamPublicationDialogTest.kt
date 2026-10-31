package top.tianyan.app.ui.bot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamPublicationDialogTest {
    @get:Rule val compose=createComposeRule()
    private val definition=BotTeamDefinition("root")
    private val published=TeamPublishedVersion("t",1,"original",listOf("root"),definition,1)
    private val draft=BotMemberGroup("t","changed",1,listOf("root"),definition,1,4,published)
    private val impact=listOf(TeamUpgradeImpact("idle","空闲群",1,0,0),TeamUpgradeImpact("busy","工作群",1,2,0))

    @Test fun onlyExplicitlySelectedIdleRoomIsPublishedAndBusyRoomIsDisabled() {
        var selected:Set<String>?=null
        compose.setContent { TianyanTheme { TeamPublicationDialog(draft,{}, { impact }, { listOf(published) },
            { id,revision,groups -> assertEquals("t",id);assertEquals(4,revision);selected=groups;2 }, { _,_,_ -> fail("unexpected upgrade") }) } }
        compose.onNodeWithTag("upgrade-busy").assertIsNotEnabled()
        compose.onNodeWithTag("upgrade-idle").performScrollTo().performClick()
        compose.onNodeWithText("发布 v2").performClick()
        compose.runOnIdle { assertEquals(setOf("idle"),selected) }
    }

    @Test fun failedPublicationKeepsDialogOpenAndShowsReason() {
        var dismissed=false
        compose.setContent { TianyanTheme { TeamPublicationDialog(draft,{ dismissed=true }, { impact }, { listOf(published) },
            { _,_,_ -> error("群聊刚开始执行，请刷新") }, { _,_,_ -> }) } }
        compose.onNodeWithText("发布 v2").performClick()
        compose.onNodeWithText("群聊刚开始执行，请刷新").assertExists()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun unchangedDraftUpgradesExistingVersionWithoutPublishingDuplicate() {
        var upgraded=false
        compose.setContent { TianyanTheme { TeamPublicationDialog(draft.copy(name="original"),{},
            { listOf(impact.first().copy(version=0)) },{ listOf(published) },
            { _,_,_ -> fail("unchanged version must not republish");0 },
            { id,version,groups -> assertEquals("t",id);assertEquals(1,version);assertEquals(setOf("idle"),groups);upgraded=true }) } }
        compose.onNodeWithTag("upgrade-idle").performScrollTo().performClick()
        compose.onNodeWithText("升级到 v1").performClick()
        compose.runOnIdle { assertTrue(upgraded) }
    }
}
