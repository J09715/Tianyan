package top.tianyan.app.ui.terminal
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import android.app.Activity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.terminal.R
import top.tianyan.app.ui.components.LocalAppNavigation
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.SpotlightGuideOverlay
import top.tianyan.app.ui.components.rememberSpotlightAnchor
import kotlin.math.roundToInt

private const val MIN_TERMINAL_FONT_SIZE_SP = 10f
private const val MAX_TERMINAL_FONT_SIZE_SP = 24f

/**
 * 天衍 · 矩阵控制台 — Termux TerminalView + PRoot argv via TerminalSession JNI.
 * Input is owned by TerminalView (same as Android-PRoot-Engine TerminalBridge).
 */
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    project: String = "",
    showBackButton: Boolean = true,
    embedded: Boolean = false,
    viewModel: TerminalViewModel? = previewOrNull { koinViewModel() },
) {
    val error by viewModel?.error?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val handles by viewModel?.handles?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val activeId by viewModel?.activeId?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val workspaces by viewModel?.workspaces?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val installedDistros by viewModel?.installedDistros?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val remoteActive by viewModel?.remoteActive?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val configuredFontSize by viewModel?.terminalFontSize?.collectAsStateWithLifecycle() ?: previewStateOf(0)
    val colorScheme by viewModel?.terminalColorScheme?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val hapticsEnabled by viewModel?.terminalHapticsEnabled?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val context = LocalContext.current
    val density = LocalDensity.current
    var fontSizeSp by remember { mutableFloatStateOf(configuredFontSize.toFloat()) }
    var sessionToClose by remember { mutableStateOf<String?>(null) }

    val termBg = top.tianyan.app.ui.theme.terminalSurfaceColor(colorScheme)
    val termText = top.tianyan.app.ui.theme.terminalContentColor(colorScheme)

    val bridge = remember(context) {
        TianyanTerminalBridge(context, viewModel?.sessionClientRouter)
    }
    // Attach before AndroidView layout so the first PTY callbacks aren't dropped.
    bridge.attachToRouter()

    DisposableEffect(bridge) {
        bridge.onFontScale = { increase ->
            fontSizeSp = (fontSizeSp + if (increase) 1f else -1f)
                .coerceIn(MIN_TERMINAL_FONT_SIZE_SP, MAX_TERMINAL_FONT_SIZE_SP)
            viewModel?.setTerminalFontSize(fontSizeSp.roundToInt())
        }
        onDispose {
            bridge.onFontScale = null
            bridge.detachFromRouter()
        }
    }

    // Match Android-PRoot-Engine / Termux Activity soft-input flags while this screen is visible.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val previous = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
        )
        onDispose {
            if (previous != null) window.setSoftInputMode(previous)
        }
    }

    val activeHandle = handles.firstOrNull { it.id == activeId }

    var isLeaving by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val firstUseGuidesShown by viewModel?.firstUseGuidesShown?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val sessionsAnchor = rememberSpotlightAnchor()
    var showCreateSession by remember { mutableStateOf(false) }

    LaunchedEffect(project) {
        viewModel?.initialize(project)
    }

    LaunchedEffect(configuredFontSize) {
        fontSizeSp = configuredFontSize.toFloat()
    }

    val navigateBack = {
        if (!isLeaving) {
            isLeaving = true
            onBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(termBg),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 独立页面自行消费状态栏与“导航栏 ∪ 软键盘”；内嵌分屏由宿主提供内边距
                .then(if (embedded) Modifier else Modifier.statusBarsPadding())
                .then(
                    if (embedded) Modifier
                    else Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
                ),
        ) {
            TerminalConsoleBar(
                onBack = if (showBackButton) navigateBack else null,
                onOpenNavigation = if (showBackButton) null else LocalAppNavigation.current,
                onOpenSessions = { showSessions = true },
                sessionsAnchor = sessionsAnchor,
            )

            TerminalRestoreWarnings(viewModel)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                when {
                    error != null -> top.tianyan.app.ui.components.RuntimeCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            error.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontFamily = FontFamily.Monospace,
                        )
                        Button(
                            onClick = { viewModel?.retryInitialize(project) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(24.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.terminal_retry))
                        }
                    }
                    activeHandle == null -> Text(
                        stringResource(R.string.terminal_starting),
                        Modifier.padding(12.dp),
                        color = termText,
                        fontFamily = FontFamily.Monospace,
                    )
                    else -> {
                        val session = activeHandle.termuxSession
                        val fontPx = with(density) { fontSizeSp.sp.toPx().roundToInt().coerceAtLeast(8) }
                        // key(session): recreate view only on session switch — avoids
                        // Compose update{} calling updateSize every frame (that resets mTopRow).
                        key(activeHandle.id) {
                            var appliedFontPx by remember { mutableStateOf(fontPx) }
                            AndroidView(
                                factory = { ctx ->
                                    TianyanTerminalHost(ctx).also { host ->
                                        // Client before attachSession (updateSize → onEmulatorSet).
                                        bridge.terminalView = host.terminalView
                                        host.terminalView.setTextSize(fontPx)
                                        appliedFontPx = fontPx
                                        host.terminalView.attachSession(session)
                                    }
                                },
                                update = { host ->
                                    val view = host.terminalView
                                    if (bridge.terminalView !== view) {
                                        bridge.terminalView = view
                                    }
                                    if (appliedFontPx != fontPx) {
                                        view.setTextSize(fontPx)
                                        appliedFontPx = fontPx
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

            // PC-standard two-row ExtraKeys (Android-PRoot-Engine):
            // Row1: ESC TAB / - ~ HOME ▲ END
            // Row2: CTRL ALT | ^C ^D ◀ ▼ ▶
            TerminalExtraKeys(bridge, hapticsEnabled)
        }

        if ("terminal_sessions" !in firstUseGuidesShown) {
            SpotlightGuideOverlay(
                anchor = sessionsAnchor,
                title = stringResource(R.string.terminal_guide_title),
                message = stringResource(R.string.terminal_guide_message),
                onDismiss = { viewModel?.markFirstUseGuideShown("terminal_sessions") },
            )
        }
    }

    if (showSessions) {
        SessionListDialog(
            handles = handles,
            activeId = activeId,
            onDismiss = { showSessions = false },
            onSwitch = { id -> viewModel?.switchSession(id); showSessions = false },
            onCreate = {
                showSessions = false
                showCreateSession = true
            },
            onClose = { id -> showSessions = false; sessionToClose = id },
        )
    }

    sessionToClose?.let { closingId ->
        CloseSessionConfirmDialog(
            onConfirm = {
                sessionToClose = null
                val wasOnlySession = handles.size == 1
                viewModel?.closeSession(closingId) { success ->
                    if (success && wasOnlySession) {
                        Toast.makeText(context, context.getString(R.string.terminal_reset_complete), Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { sessionToClose = null },
        )
    }

    if (showCreateSession) {
        CreateTerminalDialog(
            workspaces = workspaces,
            installedDistros = if (remoteActive) emptyList() else installedDistros,
            nextSessionIndex = handles.size + 1,
            onDismiss = { showCreateSession = false },
            onCreate = { label, workDir, distroId ->
                showCreateSession = false
                viewModel?.createSession(label, workDir, distroId)
            },
        )
    }
}

