package top.tianyan.app.ui.bot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment
import top.tianyan.app.core.model.bot.BotAssignmentStatus
import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.theme.TianyanTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w480dp-h900dp")
class OfficeSceneUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `replacing scene moves zone signs and creates cat targets from instance data`() {
        val scene = mutableStateOf(OfficeSceneSpec.Default)
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    GroupPixelOffice(
                        actors = emptyList(), recipes = emptyMap(), bubbles = emptyMap(),
                        selectedId = null, emptyHint = null, onSelect = {}, scene = scene.value,
                    )
                }
            }
        }
        val scale = compose.onNodeWithTag("pixel-office-scene").fetchSemanticsNode().boundsInRoot.width / 192f
        val oldCat = compose.onNodeWithTag("office-cat-lobby").fetchSemanticsNode().boundsInRoot
        val oldZone = compose.onNodeWithTag("office-zone-think").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val original = scene.value
            scene.value = original.copy(
                cats = original.cats.map {
                    if (it.id == "office-cat-lobby") it.copy(id = "visitor-cat", x = it.x + 20, y = it.y + 20) else it
                } + OfficeCatSpec("extra-cat", OfficeCatStyle.SLEEPING, 20, 54),
                zones = original.zones.map {
                    if (it.id == "think") it.copy(anchor = OfficeAnchor(it.anchor.x + 10, it.anchor.y + 20)) else it
                },
            )
        }
        compose.onNodeWithTag("office-cat-lobby").assertDoesNotExist()
        val newCat = compose.onNodeWithTag("visitor-cat").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val newZone = compose.onNodeWithTag("office-zone-think").fetchSemanticsNode().boundsInRoot
        assertEquals(20f * scale, newCat.left - oldCat.left, 1f)
        assertEquals(20f * scale, newCat.top - oldCat.top, 1f)
        assertEquals(10f * scale, newZone.left - oldZone.left, 1f)
        assertEquals(20f * scale, newZone.top - oldZone.top, 1f)
        compose.onNodeWithTag("extra-cat").assertHasClickAction().performClick()
        compose.onNodeWithTag("visitor-cat").performClick()
    }

    @Test
    fun `studio uses the same changed scene for actor placement and rendering`() {
        val scene = mutableStateOf(OfficeSceneSpec.Default)
        val bot = Bot(
            id = "coder", name = "工程师", role = "开发", systemPrompt = "", avatarSeed = "",
            sessionId = "session", createdAt = 1L, updatedAt = 1L,
        )
        val task = BotAssignment(
            id = "task", groupId = "group", botId = bot.id, botName = bot.name, title = "工作",
            task = "工作", status = BotAssignmentStatus.RUNNING, startedAt = 1L, createdAt = 1L,
        )
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    GroupCyberStudio(
                        members = listOf(bot), messages = emptyList(), assignments = listOf(task),
                        onSelectBot = {}, onOpenBot = {}, onCancelAssignment = {},
                        onContinueAssignment = { _, _, _ -> }, onBackToChat = {}, scene = scene.value,
                    )
                }
            }
        }
        val scale = compose.onNodeWithTag("pixel-office-scene").fetchSemanticsNode().boundsInRoot.width / 192f
        val before = compose.onNodeWithTag("studio-actor-coder").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            scene.value = scene.value.copy(desks = scene.value.desks.map {
                if (it.id == "desk") it.copy(x = it.x + 4, y = it.y + 4) else it
            })
        }
        compose.mainClock.advanceTimeBy(900L)
        val after = compose.onNodeWithTag("studio-actor-coder").fetchSemanticsNode().boundsInRoot
        assertEquals(4f * scale, after.left - before.left, 1f)
        assertEquals(4f * scale, after.top - before.top, 1f)
    }

    @Test
    fun `board specs move both scene overlays`() {
        val scene = mutableStateOf(OfficeSceneSpec.Default)
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    GroupPixelOffice(
                        actors = emptyList(),
                        recipes = emptyMap(),
                        bubbles = emptyMap(),
                        selectedId = null,
                        emptyHint = null,
                        onSelect = {},
                        scene = scene.value,
                    )
                }
            }
        }
        val scale = compose.onNodeWithTag("pixel-office-scene").fetchSemanticsNode().boundsInRoot.width / 192f
        val taskBefore = compose.onNodeWithTag("office-task-board").fetchSemanticsNode().boundsInRoot
        val objectiveBefore = compose.onNodeWithTag("office-objective-board").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            val original = scene.value
            scene.value = original.copy(
                boards = OfficeBoardsSpec(
                    task = OfficeBoardSpec(
                        original.boards.task.bounds.copy(
                            x = original.boards.task.bounds.x + 4,
                            y = original.boards.task.bounds.y + 4,
                        ),
                    ),
                    objective = OfficeBoardSpec(
                        original.boards.objective.bounds.copy(
                            x = original.boards.objective.bounds.x + 4,
                            y = original.boards.objective.bounds.y + 4,
                        ),
                    ),
                ),
            )
        }
        val taskAfter = compose.onNodeWithTag("office-task-board").fetchSemanticsNode().boundsInRoot
        val objectiveAfter = compose.onNodeWithTag("office-objective-board").fetchSemanticsNode().boundsInRoot
        assertEquals(4f * scale, taskAfter.left - taskBefore.left, 1f)
        assertEquals(4f * scale, taskAfter.top - taskBefore.top, 1f)
        assertEquals(4f * scale, objectiveAfter.left - objectiveBefore.left, 1f)
        assertEquals(4f * scale, objectiveAfter.top - objectiveBefore.top, 1f)
    }

    @Test
    fun `actors and scenery interactions expose accessible targets`() {
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    GroupPixelOffice(
                        actors = listOf(
                            OfficeActor(
                                botId = "coder",
                                name = "工程师",
                                x = 36,
                                y = 220,
                                pose = OfficePose.SIT,
                                state = BotAvatarState.EXECUTING,
                            ),
                        ),
                        recipes = emptyMap(),
                        bubbles = emptyMap(),
                        selectedId = null,
                        emptyHint = null,
                        onSelect = {},
                    )
                }
            }
        }

        val minimum = with(compose.density) { 48.dp.toPx() }
        val actor = compose.onNodeWithTag("studio-actor-coder")
            .assertHasClickAction()
            .assertContentDescriptionContains("工程师", substring = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(actor.width >= minimum - 0.5f)
        assertTrue(actor.height >= minimum - 0.5f)

        val cat = compose.onNodeWithTag("office-cat-lobby")
            .assertHasClickAction()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(cat.width >= minimum - 0.5f)
        assertTrue(cat.height >= minimum - 0.5f)

        val zoomInLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.fbot_pixel_office_zoom_in)
        compose.onNodeWithTag("office-camera-controls").assertExists()
        compose.onNodeWithTag("office-camera-zoom-in")
            .assertHasClickAction()
            .assertContentDescriptionContains(zoomInLabel)
            .performClick()
        compose.onNodeWithTag("office-camera-fit").performClick()
    }

    @Test
    fun `camera fit control toggles its label`() {
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 700.dp)) {
                    GroupPixelOffice(
                        actors = emptyList(),
                        recipes = emptyMap(),
                        bubbles = emptyMap(),
                        selectedId = null,
                        emptyHint = null,
                        onSelect = {},
                    )
                }
            }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fitLabel = context.getString(R.string.fbot_pixel_office_fit_view)
        val resetLabel = context.getString(R.string.fbot_pixel_office_reset_view)
        compose.waitForIdle()

        compose.onNodeWithTag("office-camera-fit")
            .assertContentDescriptionContains(fitLabel)
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("office-camera-fit")
            .assertContentDescriptionContains(resetLabel)
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("office-camera-fit")
            .assertContentDescriptionContains(fitLabel)
    }

    @Test
    fun `camera zoom survives state restoration`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    GroupPixelOffice(
                        actors = listOf(
                            OfficeActor(
                                botId = "coder",
                                name = "工程师",
                                x = 30,
                                y = 200,
                                pose = OfficePose.SIT,
                                state = BotAvatarState.EXECUTING,
                            ),
                        ),
                        recipes = emptyMap(),
                        bubbles = emptyMap(),
                        selectedId = null,
                        emptyHint = null,
                        onSelect = {},
                    )
                }
            }
        }

        val initial = compose.onNodeWithTag("studio-actor-coder").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("office-camera-zoom-in").performClick()
        val zoomed = compose.onNodeWithTag("studio-actor-coder").fetchSemanticsNode().boundsInRoot
        assertTrue(zoomed.left != initial.left || zoomed.top != initial.top)
        restoration.emulateSavedInstanceStateRestore()
        val restored = compose.onNodeWithTag("studio-actor-coder").fetchSemanticsNode().boundsInRoot
        assertEquals(zoomed.left, restored.left, 1f)
        assertEquals(zoomed.top, restored.top, 1f)
    }

    @Test
    fun `navigation overlay renders the walkway graph`() {
        val scene = OfficeSceneSpec.Default
        compose.setContent {
            TianyanTheme {
                Box(Modifier.requiredSize(384.dp, 808.dp)) {
                    OfficeNavigationOverlay(
                        scene = scene,
                        graph = scene.walkGraph(),
                        originX = 0f,
                        originY = 0f,
                        scale = 2f,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        compose.onNodeWithTag("office-navigation-overlay").assertExists()
    }
}
