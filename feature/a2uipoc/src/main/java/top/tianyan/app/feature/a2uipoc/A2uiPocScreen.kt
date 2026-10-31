package top.tianyan.app.feature.a2uipoc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.Surface
import androidx.compose.runtime.saveable.rememberSaveable
import top.tianyan.app.harness.A2uiSurfaceBus
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.RuntimeTopBar
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.delay

/** 当前聊天会话。卡片用它给 surfaceId 加前缀，和工具执行时写入总线的会话一致。 */
val LocalA2uiSessionId = staticCompositionLocalOf { "" }

/**
 * A2UI 的两个复用入口：
 * - [A2uiPocSurfaceCard]：聊天流内嵌卡片（render_surface 工具结果 → 原生界面）；
 * - [A2uiPocScreen]：设置里的界面页（注入示例 / 清空预览 / 浏览最近载荷）。
 *
 * 协议消息只在内容变化时处理一次（LaunchedEffect 按 messagesJson 键控）；
 * 渲染失败或 surface 尚无组件数据时回退展示原始 JSON。
 */

@Composable
fun A2uiPocSurfaceCard(
    call: ToolCall,
    modifier: Modifier = Modifier,
    sessionId: String = LocalA2uiSessionId.current,
) {
    val surfaceId = call.args["surfaceId"]?.toString()?.trim('"', ' ')?.trim().orEmpty()
    val rawMessages = call.args["messages"]?.toString()?.trim('"', ' ')?.trim().orEmpty()
    // 与工具执行路径共用同一份归一化（双转义自动修复）；失败时保留原文供回退展示
    val messagesJson = remember(rawMessages) {
        A2uiSurfaceBus.normalizeMessagesJson(rawMessages) ?: rawMessages
    }
    A2uiSurfaceHost(
        surfaceId = surfaceId,
        messagesJson = messagesJson,
        modifier = modifier,
        sessionId = sessionId,
        replayKey = call.id,
    )
}

@Composable
fun A2uiSurfaceHost(
    surfaceId: String,
    messagesJson: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    sessionId: String = "",
    replayKey: String? = null,
) {
    val context = LocalContext.current
    SideEffect { TianyanUrlOpener.bind(context) }
    val scopedJson = remember(messagesJson, sessionId) {
        TianyanA2uiCatalog.scopeMessages(messagesJson, sessionId)
    }
    val engineId = A2uiSurfaceBus.engineSurfaceId(sessionId, surfaceId)
    var renderError by remember(scopedJson) { mutableStateOf<String?>(null) }
    // surface 入队到引擎真正建出来有几十~上百毫秒的窗口，此前直接摆原文 JSON 会闪一下。
    // 先给骨架屏，超过宽限期仍未渲染（如 surfaceId 对不上）再回退到原文，避免无限闪烁。
    var loadingGraceExpired by remember(scopedJson) { mutableStateOf(false) }
    LaunchedEffect(scopedJson) {
        delay(SURFACE_LOADING_GRACE_MS)
        loadingGraceExpired = true
    }
    LaunchedEffect(scopedJson, replayKey) {
        renderError = if (scopedJson.isBlank()) {
            "载荷为空"
        } else {
            TianyanA2uiRenderer.processMessages(scopedJson, replayKey)
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        val rendered = TianyanA2uiRenderer.SurfaceView(surfaceId = engineId, modifier = Modifier.fillMaxWidth())
        if (!rendered) {
            if (renderError == null && !loadingGraceExpired) {
                TianyanA2uiSkeletonGroup { shimmerRootModifier ->
                    TianyanA2uiSkeleton(
                        Modifier
                            .then(shimmerRootModifier)
                            .fillMaxWidth()
                            .height(TianyanA2uiRootSkeletonHeight),
                    )
                }
            } else {
                A2uiFallbackCard(renderError = renderError, messagesJson = messagesJson)
            }
        }
    }
}

@Composable
private fun A2uiFallbackCard(renderError: String?, messagesJson: String, modifier: Modifier = Modifier) {
    RuntimeCard(
        containerColor = if (renderError != null) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (renderError != null) {
                Text(
                    renderError,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                messagesJson.take(1200).ifBlank { "（空载荷）" },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 10,
            )
        }
    }
}

@Composable
fun A2uiPocScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val surfaces by A2uiSurfaceBus.surfaces.collectAsStateWithLifecycle()
    var lastInjectedAt by rememberSaveable { mutableStateOf(0L) }
    // 与其他二级页一致：RuntimeTopBar（返回箭头/状态栏间距/单行标题）+ 不透明底色
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            RuntimeTopBar(
                title = stringResource(R.string.fa2ui_poc_title),
                onBack = onBack,
                actions = {
                    RuntimeTextButton(onClick = {
                        lastInjectedAt = System.currentTimeMillis()
                        A2uiSurfaceBus.publishFromTool(
                            sampleToolArgs(lastInjectedAt),
                            A2uiSurfaceBus.PREVIEW_SESSION_ID,
                        )
                    }) { Text(stringResource(R.string.fa2ui_inject_sample)) }
                    Spacer(Modifier.width(8.dp))
                    RuntimeTextButton(onClick = { A2uiSurfaceBus.clearPreview() }) {
                        Text(stringResource(R.string.fa2ui_clear))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier.fillMaxWidth().padding(padding)) {
            if (surfaces.isEmpty()) {
                RuntimeCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        stringResource(R.string.fa2ui_empty_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(surfaces, key = { "${it.sessionId}:${it.surfaceId}" }) { payload ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    payload.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                A2uiSurfaceHost(
                                    surfaceId = payload.surfaceId,
                                    messagesJson = payload.messagesJson,
                                    modifier = Modifier.fillMaxWidth(),
                                    sessionId = payload.sessionId,
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/** surface 入队到引擎可渲染的宽限期，超过则不再假装"还在加载"。 */
private const val SURFACE_LOADING_GRACE_MS = 1_500L

/** 演示屏注入示例时模拟一次 render_surface 工具调用（与真实工具共用同一校验/发布链路）。 */
private fun sampleToolArgs(timestamp: Long) = buildJsonObject {
    val surfaceId = "demo-$timestamp"
    put("surfaceId", JsonPrimitive(surfaceId))
    put("title", JsonPrimitive("内置示例"))
    // createSurface 里的 surfaceId 必须与入参一致，否则卡片按入参 id 找不到已渲染的 surface
    put("messages", JsonPrimitive(A2uiSurfaceBus.sampleMessagesJson(surfaceId)))
}
