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
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupCoworkingDeskBarTest {
    @get:Rule val compose = createComposeRule()

    private val botA = Bot(
        id = "bot-a",
        name = "阿策",
        role = "产品经理",
        systemPrompt = "prompt A",
        avatarSeed = "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0",
        colorHex = "#F59E0B",
        sessionId = "sess-a",
        isMainBot = true,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    private val botB = Bot(
        id = "bot-b",
        name = "小前",
        role = "前端工程师",
        systemPrompt = "prompt B",
        avatarSeed = "tianyan-pixel:v1:3,4,3,3,3,5,0,5,0,3",
        colorHex = "#3B82F6",
        sessionId = "sess-b",
        isMainBot = false,
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    @Test fun `coworking desk bar renders all members and handles click selection`() {
        var selectedBot: Bot? = null
        compose.setContent {
            TianyanTheme {
                GroupCoworkingDeskBar(
                    members = listOf(botA, botB),
                    messages = emptyList(),
                    assignments = emptyList(),
                    onSelectBot = { selectedBot = it },
                )
            }
        }

        compose.onNodeWithTag("group-coworking-desk-bar").assertIsDisplayed()
        compose.onNodeWithTag("desk-station-bot-a").assertIsDisplayed()
        compose.onNodeWithTag("desk-station-bot-b").assertIsDisplayed()

        // Click station B
        compose.onNodeWithTag("desk-station-bot-b").performClick()
        assertEquals("bot-b", selectedBot?.id)
    }

    @Test fun `state mapping reflects thinking executing blocked and idle`() {
        val idleState = computeMemberAvatarState(botA, null, null)
        assertEquals(BotAvatarState.IDLE, idleState)

        val thinkingMsg = GroupMessage(
            id = "msg-1",
            groupId = "group-1",
            senderId = botA.id,
            senderName = botA.name,
            senderType = GroupSenderType.BOT,
            content = "",
            reasoning = "Let me think carefully...",
            timestamp = 1000L,
            isStreaming = true,
        )
        val thinkingState = computeMemberAvatarState(botA, thinkingMsg, null)
        assertEquals(BotAvatarState.THINKING, thinkingState)

        val executingMsg = GroupMessage(
            id = "msg-2",
            groupId = "group-1",
            senderId = botB.id,
            senderName = botB.name,
            senderType = GroupSenderType.BOT,
            content = "Running shell command",
            timestamp = 1000L,
            isStreaming = true,
            steps = listOf(GroupToolStep(id = "step-1", name = "exec", success = null)),
        )
        val executingState = computeMemberAvatarState(botB, executingMsg, null)
        assertEquals(BotAvatarState.EXECUTING, executingState)

        val blockedTask = BotAssignment(
            id = "task-1",
            groupId = "group-1",
            botId = botA.id,
            botName = botA.name,
            title = "Analyze PRD",
            task = "Analyze PRD task",
            status = BotAssignmentStatus.BLOCKED,
            createdAt = 1000L,
            startedAt = 1000L,
            revision = 1,
        )
        val blockedState = computeMemberAvatarState(botA, null, blockedTask)
        assertEquals(BotAvatarState.BLOCKED, blockedState)
    }
}
