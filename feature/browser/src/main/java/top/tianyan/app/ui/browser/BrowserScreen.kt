package top.tianyan.app.ui.browser

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.theme.Spacing
import top.tianyan.app.ui.theme.Radius
import top.tianyan.app.ui.theme.TianyanTheme
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.browser.network.NetworkTimelineSheet
import top.tianyan.app.ui.browser.snapshot.SnapshotSheet

@Composable
fun BrowserScreen(
    onBack: () -> Unit,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 系统返回优先走 WebView 历史栈；无法后退时才退出浏览器页
    BackHandler(enabled = state.canGoBack) { viewModel.onBack(onExhausted = onBack) }
    Column(modifier = Modifier.fillMaxSize()) {
        RuntimeTopBar(
            title = "内置浏览器",
            statusText = state.title.takeIf { it.isNotBlank() } ?: state.url.takeIf { it.isNotBlank() } ?: "—",
            onBack = onBack,
        )
        BrowserPane(viewModel = viewModel, modifier = Modifier.fillMaxSize(), showHomePage = true)
    }
}

/**
 * 可复用的浏览器内容面板，浏览区域最大化：
 *  - 顶部：极薄状态栏（共浏览开关 · 快照入口 · 标签页胶囊 · 返回对话，可横向滚动）；
 *  - 中部：WebView 占满剩余空间；
 *  - 底部：紧凑 URL 栏（后退/前进/刷新 + 小输入胶囊 + 跳转圆钮）。
 *
 * 不含屏幕级导航外壳（RuntimeTopBar/BackHandler）；
 * BrowserScreen 独立页与 chat 分屏内嵌共用。
 * [onExit] 非空时状态栏末尾追加"返回对话"胶囊（chat 分屏内嵌场景）。
 */
@Composable
fun BrowserPane(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier,
    onExit: (() -> Unit)? = null,
    showHomePage: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val networkRequests by viewModel.network.collectAsStateWithLifecycle()
    val networkSheetVisible by viewModel.networkSheetVisible.collectAsStateWithLifecycle()
    val networkDetail by viewModel.networkDetail.collectAsStateWithLifecycle()
    val debugPaused by viewModel.debugPaused.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // 面板可见性上报：可见时才补建活跃 tab / 轮询状态，避免 app 启动即常驻 WebView
    DisposableEffect(Unit) {
        viewModel.onPaneVisible(true)
        onDispose { viewModel.onPaneVisible(false) }
    }
    val toolMessage = state.toolMessage
    if (toolMessage != null) {
        LaunchedEffect(toolMessage) {
            // Short ≈ 4s 自动消失，随后清理消息源，避免重复弹出
            snackbarHostState.showSnackbar(toolMessage, duration = SnackbarDuration.Short)
            viewModel.dismissToolMessage()
        }
    }
    Column(modifier = modifier) {
        BrowserStatusBar(
            coBrowsingEnabled = state.coBrowsingEnabled,
            onCoBrowsingToggle = { viewModel.onCoBrowsingToggle(!state.coBrowsingEnabled) },
            tabs = state.tabs,
            onSelect = viewModel::selectTab,
            onClose = viewModel::closeTab,
            onShowSnapshot = viewModel::onOpenSnapshot,
            onShowNetwork = viewModel::showNetworkSheet,
            networkCount = networkRequests.size,
            onExit = onExit,
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            state.activeTab?.let { tab ->
                key(tab.tabId) {
                    viewModel.activeWebView()?.let { webView ->
                        AndroidView(
                            factory = {
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                webView
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            // CDP 断点/异常暂停横幅：页面被挂起时提示原因与位置，支持手动放行
            debugPaused?.let { paused ->
                DebugPausedBanner(
                    paused = paused,
                    onResume = viewModel::resumeDebugger,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            state.snapshotSheet?.let { sheet ->
                SnapshotSheet(
                    state = sheet,
                    onDismiss = viewModel::dismissSnapshot,
                )
            }
            if (networkSheetVisible) {
                NetworkTimelineSheet(
                    requests = networkRequests,
                    hookHits = viewModel.hookHits.value,
                    detail = networkDetail,
                    onLoadDetail = viewModel::loadNetworkDetail,
                    onDismiss = viewModel::dismissNetworkSheet,
                )
            }
        }
        BrowserUrlBar(
            urlInput = state.urlInput,
            onUrlChange = viewModel::onUrlInputChanged,
            onNavigate = viewModel::onNavigate,
            onBack = { viewModel.onBack() },
            onForward = viewModel::onForward,
            onRefresh = viewModel::onRefresh,
            canGoBack = state.canGoBack,
            canGoForward = state.canGoForward,
        )
    }
}

/**
 * 顶部极薄状态栏：样式对齐智枢工作台工具条（模型·审批·分支·运行）——
 * 半透明 Surface + 横向滚动的小胶囊（icon + label），几乎不侵占浏览区高度。
 */
@Composable
private fun BrowserStatusBar(
    coBrowsingEnabled: Boolean,
    onCoBrowsingToggle: () -> Unit,
    tabs: List<BrowserTabUi>,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onShowSnapshot: () -> Unit,
    onShowNetwork: () -> Unit,
    networkCount: Int,
    onExit: (() -> Unit)? = null,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.65f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs, Alignment.Start),
        ) {
            // 1. 共浏览状态：AI 是否与用户共享浏览（点击切换）
            // 共浏览开=蓝（进行中）、关=橙（已暂停）：这两个是「浏览器自身的
            // 状态指示色」，取语义色 info / warning，深浅主题都有对比度保证。
            val coTint = if (coBrowsingEnabled) TianyanTheme.colors.info else TianyanTheme.colors.warning
            BrowserStatusItem(
                icon = if (coBrowsingEnabled) RuntimeIconName.Visibility else RuntimeIconName.VisibilityOff,
                label = if (coBrowsingEnabled) "共浏览中" else "已暂停",
                tint = coTint,
                highlight = coBrowsingEnabled,
                onClick = onCoBrowsingToggle,
            )
            StatusDivider()
            // 2. 页面快照入口（Agent 视角的元素树）
            BrowserStatusItem(
                icon = RuntimeIconName.Search,
                label = "快照",
                tint = MaterialTheme.colorScheme.tertiary,
                onClick = onShowSnapshot,
            )
            StatusDivider()
            // 3. 网络时间线入口（请求流 + Hook 命中，agent 操作可视化）
            BrowserStatusItem(
                icon = RuntimeIconName.Network,
                label = "网络 $networkCount",
                tint = MaterialTheme.colorScheme.tertiary,
                onClick = onShowNetwork,
            )
            StatusDivider()
            // 4. 标签页胶囊
            if (tabs.isEmpty()) {
                BrowserStatusItem(
                    icon = RuntimeIconName.Globe,
                    label = "尚无标签页",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = {},
                )
            } else {
                tabs.forEach { tab ->
                    BrowserTabChip(
                        tab = tab,
                        onSelect = { onSelect(tab.tabId) },
                        onClose = { onClose(tab.tabId) },
                    )
                }
            }
            // 末尾：返回对话（chat 分屏内嵌时显示）
            if (onExit != null) {
                StatusDivider()
                BrowserStatusItem(
                    icon = RuntimeIconName.Back,
                    label = "返回对话",
                    tint = MaterialTheme.colorScheme.primary,
                    highlight = true,
                    onClick = onExit,
                )
            }
        }
    }
}

/** 标签页胶囊：Globe 图标 + 标题 + 关闭小钮，选中态高亮。 */
@Composable
private fun BrowserTabChip(
    tab: BrowserTabUi,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val tint = if (tab.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = onSelect,
        shape = Radius.xsShape,
        color = if (tab.active) tint.copy(alpha = 0.14f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            RuntimeIcon(RuntimeIconName.Globe, Modifier.size(11.dp), tint = tint)
            Text(
                text = tab.title.ifBlank { tab.url.ifBlank { tab.tabId } },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = if (tab.active) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
            RuntimeIcon(
                RuntimeIconName.Close,
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onClose),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = Spacing.xs)
            .size(width = 1.dp, height = 9.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    )
}

/**
 * CDP 调试暂停横幅：断点/异常命中导致页面 JS 挂起时悬浮于浏览区顶部，
 * 提示暂停原因与命中位置，并提供手动"继续执行"（agent 不在时避免页面无限冻结）。
 */
@Composable
private fun DebugPausedBanner(
    paused: top.tianyan.app.runtime.browser.cdp.DebugPausedState,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = paused.callFrames.firstOrNull()
    val location = frame?.let {
        "${it.functionName.ifEmpty { "<anonymous>" }} @ ${it.url.substringAfterLast('/')}:${it.lineNumber + 1}"
    } ?: "无调用栈"
    // 调试暂停横幅是「盖在浏览器之上的告警条」：琥珀底 + 白字必须成立，
    // 因此固定色值，不随主题走（深色主题的 warning 是浅黄，白字压不住）。
    Surface(
        color = Color(0xCCB45309),
        shape = Radius.smShape,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RuntimeIcon(RuntimeIconName.Alert, Modifier.size(16.dp), tint = Color.White)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "已暂停 · ${paused.reason}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    location,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0xE6FFFFFF),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                onClick = onResume,
                shape = Radius.mdShape,
                color = Color.White,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    RuntimeIcon(RuntimeIconName.Play, Modifier.size(12.dp), tint = Color(0xFFB45309))
                    Text(
                        "继续",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFB45309),
                    )
                }
            }
        }
    }
}

/** 状态栏小胶囊：icon + label，样式对齐智枢工作台的 WorkbenchStatusItem。 */
@Composable
private fun BrowserStatusItem(
    icon: RuntimeIconName,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = Radius.xsShape,
        color = if (highlight) tint.copy(alpha = 0.14f) else Color.Transparent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            RuntimeIcon(
                name = icon,
                modifier = Modifier.size(11.dp),
                tint = tint,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Medium,
                ),
                color = if (highlight) tint else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 底部紧凑 URL 栏：后退/前进/刷新小图标钮 + 圆角输入胶囊 + 主色跳转圆钮。
 * 单行高度 ~44dp，只占浏览区很小一块。
 */
@Composable
private fun BrowserUrlBar(
    urlInput: String,
    onUrlChange: (String) -> Unit,
    onNavigate: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    canGoBack: Boolean,
    canGoForward: Boolean,
) {
    var input by remember(urlInput) { mutableStateOf(urlInput) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            RuntimeIconButton(onClick = onBack, enabled = canGoBack, contentDescription = "后退") {
                RuntimeIcon(RuntimeIconName.Back, Modifier.size(17.dp))
            }
            RuntimeIconButton(onClick = onForward, enabled = canGoForward, contentDescription = "前进") {
                RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(17.dp))
            }
            RuntimeIconButton(onClick = onRefresh, contentDescription = "刷新") {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(17.dp))
            }
            // URL 输入胶囊：占满剩余宽度，右侧内嵌跳转圆钮
            Surface(
                shape = Radius.lgShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            onUrlChange(it)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { onNavigate() }),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                if (input.isEmpty()) {
                                    Text(
                                        "https://…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    // 跳转圆钮：主色填充，视觉锚点
                    Surface(
                        onClick = onNavigate,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            RuntimeIcon(
                                RuntimeIconName.ArrowUp,
                                Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }
        }
    }
}
