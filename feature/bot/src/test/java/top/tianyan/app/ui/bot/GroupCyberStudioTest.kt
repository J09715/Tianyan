package top.tianyan.app.ui.bot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class GroupCyberStudioTest {
    @get:Rule
    val compose = createComposeRule()

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

    private val assignment1 = BotAssignment(
        id = "task-1",
        groupId = "grp-1",
        botId = "bot-a",
        botName = "阿策",
        title = "设计群聊工位看板方案",
        task = "设计群聊工位看板方案",
        status = BotAssignmentStatus.RUNNING,
        startedAt = System.currentTimeMillis() - 5000L,
        finishedAt = null,
        reportToBotId = "bot-b",
        independent = true,
        createdAt = 1000L,
    )

    @Test
    fun `cyber studio renders overview desks and pipeline with interaction triggers`() {
        var selectedBot: Bot? = null
        var openedBotId: String? = null
        var cancelledTaskId: String? = null
        var backToChatCalled = false

        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA, botB),
                    messages = emptyList(),
                    assignments = listOf(assignment1),
                    onSelectBot = { selectedBot = it },
                    onOpenBot = { openedBotId = it },
                    onCancelAssignment = { cancelledTaskId = it },
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = { backToChatCalled = true },
                )
            }
        }

        compose.onNodeWithTag("group-cyber-studio").assertIsDisplayed()
        compose.onNodeWithTag("pixel-office-scene").assertIsDisplayed()
        compose.onNodeWithTag("office-objective-board").assertExists()
        compose.onNodeWithTag("office-zone-think").assertExists()
        compose.onNodeWithTag("office-zone-meet").assertExists()
        compose.onNodeWithTag("office-zone-work").assertExists()
        compose.onNodeWithTag("office-zone-coffee").assertExists()
        compose.onNodeWithTag("office-zone-rest").assertExists()
        compose.onNodeWithTag("office-cat-lobby").assertExists()
        compose.onNodeWithTag("office-cat-sofa").assertExists()
        compose.onNodeWithTag("office-coffee-machine").assertExists()
        compose.onNodeWithTag("office-task-board").assertExists()
        compose.onNodeWithTag("office-task-board-doing", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("office-task-card-task-1", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("office-report-links").assertExists()
        compose.onNodeWithTag("studio-actor-bot-a").assertIsDisplayed()
        compose.onNodeWithTag("studio-actor-bot-b").assertExists()
        compose.onNodeWithTag("studio-bubble-bot-a").assertIsDisplayed()

        compose.onNodeWithTag("studio-back-to-chat-fab").assertIsDisplayed().performClick()
        assertTrue(backToChatCalled)

        compose.onNodeWithTag("studio-actor-bot-a").performClick()
        compose.onNodeWithTag("studio-select-card").assertIsDisplayed()
        compose.onNodeWithTag("studio-actor-mention").assertIsDisplayed().performClick()
        assertEquals("bot-a", selectedBot?.id)
    }

    @Test
    fun `task board opens full screen and a card selects and focuses its owner`() {
        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA, botB),
                    messages = emptyList(),
                    assignments = listOf(assignment1),
                    onSelectBot = {},
                    onOpenBot = {},
                    onCancelAssignment = {},
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = {},
                )
            }
        }

        compose.onNodeWithTag("office-task-board").performClick()
        compose.onNodeWithTag("office-board-overlay").assertExists()
        compose.onNodeWithTag("office-board-overlay-doing").assertExists()
        compose.onNodeWithTag("office-board-overlay-card-task-1").performClick()
        compose.onNodeWithTag("office-board-overlay").assertDoesNotExist()
        compose.onNodeWithTag("studio-select-card").assertExists()
    }

    @Test
    fun `recent completion walks a courier toward the report recipient`() {
        val completed = assignment1.copy(
            id = "task-done",
            botId = botB.id,
            botName = botB.name,
            title = "完成像素看板",
            status = BotAssignmentStatus.COMPLETED,
            finishedAt = System.currentTimeMillis() - 500L,
            reportToBotId = botA.id,
        )
        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA, botB),
                    messages = emptyList(),
                    assignments = listOf(completed),
                    onSelectBot = {},
                    onOpenBot = {},
                    onCancelAssignment = {},
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = {},
                )
            }
        }

        compose.onNodeWithTag("office-handoff-layer").assertExists()
        compose.onNodeWithTag("studio-actor-bot-b").assertDoesNotExist()
    }

    @Test
    fun `cyber studio projects live reasoning into the office bubble`() {
        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA, botB),
                    messages = listOf(
                        GroupMessage(
                            id = "live-think",
                            groupId = "grp-1",
                            senderId = botB.id,
                            senderName = botB.name,
                            senderType = GroupSenderType.BOT,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            isStreaming = true,
                            reasoning = "正在核对 PRoot 挂载路径",
                        ),
                    ),
                    assignments = emptyList(),
                    onSelectBot = {},
                    onOpenBot = {},
                    onCancelAssignment = {},
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = {},
                )
            }
        }

        compose.onNodeWithTag("office-task-board-thought", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("正在核对 PRoot 挂载路径", substring = true).assertExists()
    }

    @Test
    fun `cyber studio projects tool detail into the office bubble`() {
        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA, botB),
                    messages = listOf(
                        GroupMessage(
                            id = "live-tool",
                            groupId = "grp-1",
                            senderId = botB.id,
                            senderName = botB.name,
                            senderType = GroupSenderType.BOT,
                            content = "",
                            timestamp = System.currentTimeMillis(),
                            isStreaming = true,
                            steps = listOf(
                                GroupToolStep(
                                    id = "step-1",
                                    name = "write_file",
                                    success = null,
                                    detail = "PixelOfficeSprite.kt",
                                ),
                            ),
                        ),
                    ),
                    assignments = emptyList(),
                    onSelectBot = {},
                    onOpenBot = {},
                    onCancelAssignment = {},
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = {},
                )
            }
        }

        compose.onNodeWithTag("studio-bubble-bot-b").assertExists()
        compose.onNodeWithText("PixelOfficeSprite.kt", substring = true).assertExists()
    }

    @Test
    fun `cyber studio keeps the office when there is no assignment`() {
        compose.setContent {
            TianyanTheme {
                GroupCyberStudio(
                    members = listOf(botA),
                    messages = emptyList(),
                    assignments = emptyList(),
                    onSelectBot = {},
                    onOpenBot = {},
                    onCancelAssignment = {},
                    onContinueAssignment = { _, _, _ -> },
                    onBackToChat = {},
                )
            }
        }

        compose.onNodeWithTag("pixel-office-scene").assertIsDisplayed()
        compose.onNodeWithTag("studio-actor-bot-a").assertIsDisplayed()
        compose.onNodeWithTag("studio-bubble-bot-a").assertExists()
        compose.onNodeWithTag("pixel-office-status").assertDoesNotExist()
        compose.onNodeWithTag("pipeline-empty-placeholder").assertDoesNotExist()
    }
}
