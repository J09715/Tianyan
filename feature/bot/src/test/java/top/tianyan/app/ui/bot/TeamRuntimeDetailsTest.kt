package top.tianyan.app.ui.bot

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeamRuntimeDetailsTest {
    @get:Rule val compose=createComposeRule()
    private val team=GroupTeam("root",setOf("root","author","reviewer","executor"))
    private val members=team.memberIds.map { Bot(it,it,"role","","",sessionId=it,createdAt=1,updatedAt=1) }
    private val group=GroupChat("g","group","root",createdAt=1,teamGroupId="t",teamVersion=1)
    private val rule=GroupReviewRule("r","review","author","reviewer","executor")

    @Test fun memberClickShowsBlockingEvidenceWithoutOpeningOrganizationEditor() {
        val task=BotAssignment("task","g","root","root","环境检查","check",BotAssignmentStatus.BLOCKED,createdAt=1,startedAt=1,error="缺少构建工具，请安装后继续")
        compose.setContent { TianyanTheme { TeamGraphDialog(team,GroupGovernance(listOf(rule)),members,{},listOf(task),emptyList(),group) } }
        compose.onNodeWithText("全图").performClick()
        compose.onNodeWithTag("team-graph-member-root").performClick()
        compose.onNodeWithText("缺少构建工具，请安装后继续").assertExists()
        compose.onNodeWithText("选择团队主控").assertDoesNotExist()
    }

    @Test fun highlightedConnectionOpensExactProposalAndReviewReason() {
        val proposal=GroupProposal("p","g",rule,3,"准确方案正文",ProposalDecision.REJECTED,"缺少验收标准","reviewer","t",1)
        compose.setContent { TianyanTheme { TeamGraphDialog(team,GroupGovernance(listOf(rule)),members,{},emptyList(),listOf(proposal),group) } }
        compose.onNodeWithText("全图").performClick()
        compose.onNodeWithTag("team-edge-reviewer-author").performClick()
        compose.onNodeWithText("准确方案正文").assertExists()
        compose.onNodeWithText("审核依据：缺少验收标准").assertExists()
    }
}
