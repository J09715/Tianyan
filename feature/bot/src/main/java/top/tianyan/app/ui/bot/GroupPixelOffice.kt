package top.tianyan.app.ui.bot

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.PixelAvatarRecipe
import top.tianyan.app.feature.bot.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Full-bleed pixel office. Characters are hit targets; speech bubbles use real type. */
@Composable
internal fun GroupPixelOffice(
    actors: List<OfficeActor>,
    recipes: Map<String, PixelAvatarRecipe>,
    bubbles: Map<String, String>,
    screens: Map<String, String> = emptyMap(),
    selectedId: String?,
    emptyHint: String?,
    onSelect: (String) -> Unit,
    objective: String? = null,
    tasks: List<OfficeBoardTask> = emptyList(),
    boardThought: String? = null,
    links: List<OfficeReportLink> = emptyList(),
    handoffs: List<OfficeWalkHandoff> = emptyList(),
    focus: OfficeFocusRequest? = null,
    onOpenBoard: () -> Unit = {},
    celebrating: Set<String> = emptySet(),
    modifier: Modifier = Modifier,
    scene: OfficeSceneSpec = OfficeSceneSpec.Default,
) {
    val room = remember(scene) { PixelOfficeScene.pixels(scene).toPixelImage(PixelOfficeDetail.WIDTH, PixelOfficeScene.HEIGHT * PixelOfficeDetail.SCALE) }
    val sceneActors = remember(actors) {
        actors.map { it.copy(y = PixelOfficeScene.y(it.y.toFloat()).roundToInt()) }
    }
    val motionEnabled = rememberMotionEnabled()
    val walkGraph = remember(scene) { scene.walkGraph() }
    val handoffState = remember(handoffs, sceneActors, motionEnabled, scene) {
        if (motionEnabled) officeHandoffState(handoffs, sceneActors, scene)
        else OfficeHandoffState(emptyList(), emptySet(), emptySet())
    }
    var interactionAt by remember(scene) { mutableStateOf(emptyMap<String, Long>()) }
    LaunchedEffect(interactionAt) {
        if (interactionAt.isEmpty()) return@LaunchedEffect
        delay(2_800)
        interactionAt = emptyMap()
    }
    val petLabel = stringResource(R.string.fbot_pixel_office_pet_cat)
    val brewLabel = stringResource(R.string.fbot_pixel_office_brew_coffee)
    val idleAnimation = if (motionEnabled) rememberOfficeIdleAnimation() else null
    val frame = idleAnimation?.frame ?: 0
    val yawnSlot = idleAnimation?.yawnSlot ?: 0
    val panXState = rememberOfficeCameraFloat(0f)
    val panYState = rememberOfficeCameraFloat(0f)
    val zoomState = rememberOfficeCameraFloat(1f)
    val metrics = remember { OfficeMetrics() }
    metrics.panX = panXState
    metrics.panY = panYState
    metrics.zoom = zoomState
    var aimed by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val debugNavigation = remember(context) {
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }
    var navigationVisible by rememberSaveable { mutableStateOf(false) }
    val toggleNavigation: (() -> Unit)? = if (debugNavigation) {
        { navigationVisible = !navigationVisible }
    } else {
        null
    }
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .testTag("pixel-office-scene"),
    ) {
        val widthPx = constraints.maxWidth
        val heightPx = constraints.maxHeight
        val baseScale = if (widthPx > 0 && heightPx > 0) maxOf(
            widthPx / PixelOfficeRoom.WIDTH.toFloat(),
            heightPx / PixelOfficeScene.HEIGHT.toFloat(),
        ) else 1f
        metrics.updateViewport(widthPx, heightPx, baseScale)
        OfficeCameraFocusEffect(focus, sceneActors, metrics, widthPx, heightPx)
        if (widthPx > 0 && heightPx > 0) {
            val scale = metrics.scale
            val mapW = (PixelOfficeRoom.WIDTH * scale).roundToInt()
            val mapH = (PixelOfficeScene.HEIGHT * scale).roundToInt()
            LaunchedEffect(widthPx, heightPx) {
                zoomState.floatValue = zoomState.floatValue.coerceIn(metrics.minZoom, OFFICE_MAX_ZOOM)
                val (x, y) = if (!aimed) {
                    aimed = true
                    openingOfficeCamera(widthPx, heightPx, metrics.baseScale * zoomState.floatValue)
                } else {
                    clampOfficeCamera(panXState.floatValue, panYState.floatValue, metrics.baseScale * zoomState.floatValue, widthPx, heightPx)
                }
                panXState.floatValue = x
                panYState.floatValue = y
            }
            val panX = panXState.floatValue
            val panY = panYState.floatValue
            Box(Modifier.fillMaxSize().officeGestures(metrics)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(PixelOfficeRoom.FLOOR))
                    drawImage(
                        room,
                        dstOffset = IntOffset(panX.roundToInt(), panY.roundToInt()),
                        dstSize = IntSize(mapW, mapH),
                        filterQuality = FilterQuality.None,
                    )
                }
                OfficeAmbientLayer(
                    actors = sceneActors,
                    celebrating = if (motionEnabled) celebrating else emptySet(),
                    interactionAt = interactionAt,
                    scene = scene,
                    originX = panX.roundToInt(),
                    originY = panY.roundToInt(),
                    scale = scale,
                    motionEnabled = motionEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
                OfficeZoneSigns(originX = panX, originY = panY, scale = scale, scene = scene)
                OfficeTaskBoard(
                    tasks = tasks,
                    liveNote = boardThought,
                    originX = panX,
                    originY = panY,
                    scale = scale,
                    board = scene.boards.task,
                    onOpen = onOpenBoard,
                )
                if (links.isNotEmpty()) {
                    OfficeReportLinksLayer(
                        links = links,
                        actors = sceneActors,
                        walkingTaskIds = handoffState.taskIds,
                        originX = panX.roundToInt(),
                        originY = panY.roundToInt(),
                        scale = scale,
                        motionEnabled = motionEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                sceneActors.sortedBy { it.y }.forEach { actor ->
                    if (actor.botId in handoffState.courierIds) return@forEach
                    key(actor.botId) {
                        OfficeActorSprite(
                            actor = actor,
                            recipe = recipes[actor.botId] ?: PixelAvatarRecipe.fromName(actor.botId),
                            frame = frame,
                            yawnSlot = yawnSlot,
                            originX = panX.roundToInt(),
                            originY = panY.roundToInt(),
                            scale = scale,
                            sceneWidth = widthPx,
                            sceneHeight = heightPx,
                            scene = scene,
                            walkGraph = walkGraph,
                            bubble = if (actor.pose == OfficePose.THINK && actor.waitSeconds == null) {
                                null
                            } else {
                                bubbles[actor.botId]
                            },
                            screen = screens[actor.botId],
                            selected = actor.botId == selectedId,
                            onSelect = { onSelect(actor.botId) },
                        )
                    }
                }
                if (motionEnabled && handoffState.handoffs.isNotEmpty()) {
                    OfficeHandoffLayer(
                        handoffs = handoffState.handoffs,
                        actors = sceneActors,
                        recipes = recipes,
                        originX = panX.roundToInt(),
                        originY = panY.roundToInt(),
                        scale = scale,
                        scene = scene,
                        walkGraph = walkGraph,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                scene.interactions.forEach { interaction ->
                    key(interaction.id) {
                        OfficeTapTarget(
                            x = interaction.bounds.x.toFloat(), y = interaction.bounds.y.toFloat(),
                            w = interaction.bounds.width.toFloat(), h = interaction.bounds.height.toFloat(),
                            originX = panX, originY = panY, scale = scale,
                            tag = interaction.id,
                            label = if (interaction.action == OfficeInteractionAction.PET) petLabel else brewLabel,
                            onClick = { interactionAt = interactionAt + (interaction.id to SystemClock.uptimeMillis()) },
                        )
                    }
                }
                OfficeObjectiveBoard(objective, panX, panY, scale, scene.boards.objective)
                if (navigationVisible) {
                    OfficeNavigationOverlay(
                        scene = scene,
                        graph = walkGraph,
                        originX = panX,
                        originY = panY,
                        scale = scale,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (actors.isEmpty() && !emptyHint.isNullOrBlank()) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("pixel-office-empty"),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = emptyHint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        OfficeCameraControls(
            metrics = metrics,
            navigationVisible = navigationVisible,
            onToggleNavigation = toggleNavigation,
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}
