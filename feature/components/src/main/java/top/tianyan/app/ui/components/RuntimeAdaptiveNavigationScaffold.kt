package top.tianyan.app.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.zIndex
import top.tianyan.app.feature.components.R

val RuntimeSidebarWidth = 320.dp

/**
 * App shell that adapts navigation to [LocalRuntimeWidthClass]:
 * - Compact: reveal drawer over the page.
 * - Medium: icon rail; the sidebar slides over the page from the rail edge.
 * - Expanded: sidebar docked beside the page, collapsible into the rail.
 *
 * Page and sidebar are movable content, so crossing a breakpoint (fold/unfold, resize) moves them
 * without rebuilding their state.
 *
 * @param visible Overlay sidebar requested. In Expanded it is absorbed into the docked sidebar.
 * @param docked Expanded only: sidebar docked (true) or collapsed into the rail (false).
 * @param rail Receives a toggle that opens the sidebar in the current mode.
 * @param drawerContent Receives a collapse action while docked, null otherwise.
 */
@Composable
fun RuntimeAdaptiveNavigationScaffold(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpen: (() -> Unit)?,
    docked: Boolean,
    onDockedChange: (Boolean) -> Unit,
    rail: @Composable (onOpenSidebar: () -> Unit) -> Unit,
    drawerContent: @Composable (onCollapse: (() -> Unit)?) -> Unit,
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val widthClass = LocalRuntimeWidthClass.current
    val expanded = widthClass == RuntimeWidthClass.Expanded
    val latestContent by rememberUpdatedState(content)
    val latestDrawer by rememberUpdatedState(drawerContent)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val latestDockedChange by rememberUpdatedState(onDockedChange)
    val page = remember { movableContentOf { latestContent() } }
    val sidebar = remember { movableContentOf { collapse: (() -> Unit)? -> latestDrawer(collapse) } }

    LaunchedEffect(expanded, visible) {
        if (expanded && visible) {
            latestDockedChange(true)
            latestDismiss()
        }
    }

    if (widthClass == RuntimeWidthClass.Compact) {
        RuntimeRevealNavigationDrawer(
            visible = visible, onDismiss = onDismiss, onOpen = onOpen, gesturesEnabled = gesturesEnabled,
            drawerContent = { sidebar(null) }, content = { page() },
        )
        return
    }

    val dockProgress by animateFloatAsState(if (expanded && docked) 1f else 0f, RuntimeAdaptiveMotion.float)
    val overlayProgress by animateFloatAsState(if (!expanded && visible) 1f else 0f, RuntimeAdaptiveMotion.float)
    BackHandler(enabled = !expanded && visible, onBack = onDismiss)
    val openSidebar: () -> Unit = {
        if (expanded) onDockedChange(true) else if (visible) onDismiss() else onOpen?.invoke()
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.width(lerp(RuntimeNavigationRailWidth, RuntimeSidebarWidth, dockProgress))
                .fillMaxHeight().clipToBounds(),
        ) {
            if (expanded) {
                DockedSidebar(dockProgress, Modifier.zIndex(if (docked) 1f else 0f)) {
                    sidebar(if (docked) ({ onDockedChange(false) }) else null)
                }
            }
            if (dockProgress < 1f) {
                Box(
                    Modifier.zIndex(if (expanded && docked) 0f else 1f)
                        .graphicsLayer { alpha = 1f - dockProgress }
                        .then(if (expanded && docked) Modifier.clearAndSetSemantics {} else Modifier),
                ) { rail(openSidebar) }
            }
        }
        var pageWidth by remember { mutableStateOf(Dp.Unspecified) }
        val density = LocalDensity.current
        Box(
            Modifier.weight(1f).fillMaxHeight().clipToBounds()
                .onSizeChanged { pageWidth = with(density) { it.width.toDp() } },
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(if (overlayProgress > 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
            ) { CompositionLocalProvider(LocalRuntimePageWidth provides pageWidth) { page() } }
            if (!expanded) OverlaySidebar(overlayProgress, onDismiss) { sidebar(null) }
        }
    }
}

@Composable
private fun DockedSidebar(progress: Float, modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxHeight()
            .wrapContentWidth(Alignment.Start, unbounded = true)
            .width(RuntimeSidebarWidth)
            .graphicsLayer { alpha = progress }
            .then(if (progress < 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Start),
            ),
        ) { content() }
    }
}

@Composable
private fun OverlaySidebar(progress: Float, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val widthPx = with(LocalDensity.current) { RuntimeSidebarWidth.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    if (progress > 0f) {
        val scrim = MaterialTheme.colorScheme.scrim
        Box(
            Modifier.fillMaxSize()
                .drawBehind { drawRect(scrim.copy(alpha = 0.32f * progress)) }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(R.string.components_close),
                    onClick = onDismiss,
                ),
        )
    }
    // Stay composed while hidden so opening does not build the sidebar mid-animation.
    Surface(
        modifier = Modifier.width(RuntimeSidebarWidth).fillMaxHeight()
            .graphicsLayer { translationX = (progress - 1f) * widthPx * direction }
            .then(if (progress < 0.5f) Modifier.clearAndSetSemantics {} else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = if (progress > 0f) 6.dp else 0.dp,
    ) { content() }
}
