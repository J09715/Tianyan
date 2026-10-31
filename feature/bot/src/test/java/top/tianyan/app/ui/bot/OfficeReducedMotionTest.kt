package top.tianyan.app.ui.bot

import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.ui.theme.TianyanTheme

private object ZeroMotionScale : MotionDurationScale {
    override val scaleFactor: Float = 0f
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficeReducedMotionTest {
    @get:Rule
    val compose = createComposeRule(effectContext = ZeroMotionScale)

    private val botA = Bot(
        id = "bot-a",
        name = "阿策",
        role = "产品经理",
        systemPrompt = "prompt A",
        avatarSeed = "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0",
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
        sessionId = "sess-b",
        createdAt = 1000L,
        updatedAt = 1000L,
    )

    @Test
    fun `reduced motion keeps the handoff courier at their desk`() {
        val completed = BotAssignment(
            id = "task-done",
            groupId = "grp-1",
            botId = botB.id,
            botName = botB.name,
            title = "完成像素看板",
            task = "完成像素看板",
            status = BotAssignmentStatus.COMPLETED,
            startedAt = System.currentTimeMillis() - 5_000L,
            finishedAt = System.currentTimeMillis() - 500L,
            reportToBotId = botA.id,
            createdAt = 1000L,
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

        compose.onNodeWithTag("office-handoff-layer").assertDoesNotExist()
        compose.onNodeWithTag("studio-actor-bot-b").assertExists()
        compose.onNodeWithTag("office-camera-controls").assertExists()
    }
}
