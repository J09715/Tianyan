package top.tianyan.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R as ComponentsR
import top.tianyan.app.ui.components.LocalRuntimeWidthClass
import top.tianyan.app.ui.components.RuntimeAdaptiveMotion
import top.tianyan.app.ui.components.RuntimeWidthClass

/** Chat area needed before the companion pane can sit beside the conversation. */
private val DockedCompanionMinWidth = 720.dp
private val ResizeHandleWidth = 12.dp
private const val MIN_COMPANION_RATIO = 0.35f
private const val MAX_COMPANION_RATIO = 0.65f

private enum class CompanionMode { None, Pager, Overlay, Docked }

/** Top-bar entry that shows or hides the terminal/browser companion pane. */
internal data class ChatCompanionToggle(val open: Boolean, val onToggle: () -> Unit)

/**
 * Lays out the conversation with its terminal/browser companion per window width:
 * - Compact: the browser replaces the conversation as a full page.
 * - Medium (or Expanded without room): the companion slides over from the end edge.
 * - Expanded: the companion docks beside the conversation, resizable and collapsible.
 *
 * One "shown / browser selected" state drives every mode, and terminal/browser are movable
 * content, so folding or resizing changes presentation without reloading either pane.
 */
@Composable
internal fun ChatAdaptiveLayout(
    workspace: String,
    terminalPane: (@Composable (project: String) -> Unit)?,
    browserPane: (@Composable (onExit: (() -> Unit)?) -> Unit)?,
    browserActivityTick: Long,
    browserBackPressed: (() -> Boolean)?,
    bottomInsets: WindowInsets,
    topBar: @Composable (onOpenBrowser: (() -> Unit)?, browserHighlight: Boolean, companion: ChatCompanionToggle?) -> Unit,
    chatContent: @Composable (Modifier) -> Unit,
) {
    val widthClass = LocalRuntimeWidthClass.current
    val latestTerminal by rememberUpdatedState(terminalPane)
    val latestBrowser by rememberUpdatedState(browserPane)
    val terminal = remember { movableContentOf { project: String -> latestTerminal?.invoke(project) } }
    val browser = remember { movableContentOf { onExit: (() -> Unit)? -> latestBrowser?.invoke(onExit) } }
    var browserSelected by rememberSaveable { mutableStateOf(false) }
    var companionRatio by rememberSaveable { mutableFloatStateOf(0.52f) }
    var lastSeenBrowserTick by remember { mutableLongStateOf(0L) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mode = when {
            terminalPane != null && widthClass == RuntimeWidthClass.Expanded && maxWidth >= DockedCompanionMinWidth -> CompanionMode.Docked
            terminalPane != null && widthClass != RuntimeWidthClass.Compact -> CompanionMode.Overlay
            browserPane != null -> CompanionMode.Pager
            else -> CompanionMode.None
        }
        // Docked starts visible like the former dual pane; other modes open on demand.
        var companionShown by rememberSaveable { mutableStateOf(mode == CompanionMode.Docked) }
        val dockedFraction by animateFloatAsState(
            if (mode == CompanionMode.Docked && companionShown) 1f else 0f, RuntimeAdaptiveMotion.float)
        val overlayProgress by animateFloatAsState(
            if (mode == CompanionMode.Overlay && companionShown) 1f else 0f, RuntimeAdaptiveMotion.float)
        val browserPage = mode == CompanionMode.Pager && companionShown && browserSelected && browserPane != null
        val overlayOpen = mode == CompanionMode.Overlay && companionShown
        val browserVisible = browserPane != null && companionShown && browserSelected && mode != CompanionMode.None
        LaunchedEffect(browserVisible, browserActivityTick) {
            if (browserVisible) lastSeenBrowserTick = browserActivityTick
        }
        BackHandler(enabled = browserPage || overlayOpen) {
            if (!(browserSelected && browserBackPressed?.invoke() == true)) companionShown = false
        }
        val openBrowser: (() -> Unit)? = browserPane?.let { { browserSelected = true; companionShown = true } }
        val toggle = if (mode == CompanionMode.Docked || mode == CompanionMode.Overlay) {
            ChatCompanionToggle(companionShown) { companionShown = !companionShown }
        } else null
        val companion: @Composable (Modifier) -> Unit = { modifier ->
            ChatCompanionPane(
                browserSelected = browserSelected, onSelectBrowser = { browserSelected = it },
                workspace = workspace, terminal = { terminal(it) },
                browser = if (browserPane != null) ({ onExit -> browser(onExit) }) else null,
                modifier = modifier,
            )
        }

        val screenWidth = maxWidth
        Box(
            Modifier.fillMaxSize()
                .then(if (browserPage || overlayProgress > 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            val innerWidth = screenWidth - 24.dp - ResizeHandleWidth
            Row(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
            ) {
                chatContent(Modifier.weight(1f).fillMaxHeight())
                if (mode == CompanionMode.Docked && dockedFraction > 0f) {
                    CompanionResizeHandle(innerWidth) { delta ->
                        companionRatio = (companionRatio - delta).coerceIn(MIN_COMPANION_RATIO, MAX_COMPANION_RATIO)
                    }
                    companion(
                        Modifier.width(innerWidth * companionRatio * dockedFraction)
                            .fillMaxHeight().statusBarsPadding().padding(top = 64.dp).windowInsetsPadding(bottomInsets).padding(vertical = 4.dp),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().then(if (browserPage || overlayProgress > 0.5f) Modifier.clearAndSetSemantics {} else Modifier)) {
            topBar(openBrowser, browserActivityTick > lastSeenBrowserTick, toggle)
        }
        if (mode == CompanionMode.Overlay && (overlayOpen || overlayProgress > 0f)) {
            CompanionOverlay(overlayProgress, (maxWidth * 0.55f).coerceIn(360.dp, 480.dp).coerceAtMost(maxWidth - 48.dp),
                bottomInsets, onDismiss = { companionShown = false }) { companion(Modifier.fillMaxSize()) }
        }
        if (browserPage) {
            // Full-page browser keeps only its own slim status bar; the URL bar sits on the navigation bar.
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(bottomInsets)) {
                    browser { companionShown = false }
                }
            }
        }
    }
}

/** Drag target between conversation and companion; reports the drag as a fraction of [innerWidth]. */
@Composable
private fun CompanionResizeHandle(innerWidth: Dp, onDragFraction: (Float) -> Unit) {
    val innerPx = with(LocalDensity.current) { innerWidth.toPx() }.coerceAtLeast(1f)
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val label = stringResource(ComponentsR.string.components_companion_resize)
    Box(
        Modifier.width(ResizeHandleWidth).fillMaxHeight()
            .semantics { contentDescription = label }
            .draggable(rememberDraggableState { delta -> onDragFraction(delta * direction / innerPx) }, Orientation.Horizontal),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.width(4.dp).height(40.dp).clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun CompanionOverlay(
    progress: Float,
    width: Dp,
    bottomInsets: WindowInsets,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val scrim = MaterialTheme.colorScheme.scrim
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(scrim.copy(alpha = 0.32f * progress)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(ComponentsR.string.components_close),
                    onClick = onDismiss,
                ),
        )
        Surface(
            modifier = Modifier.align(Alignment.CenterEnd).width(width).fillMaxHeight()
                .graphicsLayer { translationX = (1f - progress) * widthPx * direction },
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 6.dp,
        ) {
            Box(Modifier.fillMaxSize().statusBarsPadding().windowInsetsPadding(bottomInsets).padding(8.dp)) {
                content()
            }
        }
    }
}
