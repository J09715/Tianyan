package top.tianyan.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.*
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.theme.TianyanTheme
import java.io.File
import java.time.Duration

/**
 * 渲染真实 Compose 组件的共享夹具。
 * 无头渲染的帧时钟和效果调度会随用例累积而失效，交互与弹窗用例各自独立成测试类，
 * 模块测试任务按类 fork JVM，保证每次都是干净的组合环境。
 */
abstract class ChatRenderTestSupport {
    protected fun render(dark: Boolean, scale: Float, name: String, sidebar: Boolean = false,
                         details: Boolean = false, spacing: Boolean = false) {
        // 用系统字体缩放模拟大字号；在组合里覆盖 LocalDensity 会让后续用例收不到重组通知。
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        controller.setup()
        val calls = listOf(
            ToolCall("sdk", 1, HarnessTool.BASE, buildJsonObject { put("command", "android sdk list") }),
            ToolCall("read", 1, HarnessTool.READ, buildJsonObject { put("path", "/workspace/demo/build.gradle.kts") }),
            ToolCall("build", 1, HarnessTool.BASE, buildJsonObject { put("command", "./gradlew assembleDebug") }),
        )
        val results = calls.associate { it.id to ToolResult("r-${it.id}", 2, it.id, true, "ok", durationMs = 2_400) }
        var processState: LazyListState? = null
        // 聚焦状态提到组合外，便于截图后关闭弹窗，避免窗口组合残留到后续用例。
        val focusState = mutableStateOf<String?>(if (details) "build" else null)
        try {
            activity.setContent {
                CompositionLocalProvider(LocalChatTimelineFocus provides focusState) {
                    TianyanTheme(darkTheme = dark, dynamicColor = false) {
                        Surface(color = MaterialTheme.colorScheme.background) {
                            if (sidebar) SidebarFixture() else if (spacing) ProcessFixture { processState = it } else {
                                Column(Modifier.fillMaxSize().padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    AssistantTurnLabel()
                                    ThinkingBlock("thinking", "核对 SDK 与构建配置。", false, durationMs = 4_200)
                                    Text("先核对工具链，再执行构建。", style = MaterialTheme.typography.bodyMedium)
                                    ToolStepsCard(
                                        ToolStepsItem(calls.mapIndexed { i, call -> ChatRenderItem.MessageItem(call, i) }),
                                        results, calls, "demo", null, false, null, false, {})
                                }
                            }
                        }
                    }
                }
            }
            var root = activity.window.decorView
            val width = if (sidebar) 320 else 393
            val height = 700
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            layout()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            if (details) root = requireNotNull(ShadowDialog.getLatestDialog()) { "Tool details must open a modal window" }.window!!.decorView
            layout()
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val target = File("build/reports/chat-timeline/$name.png").apply { parentFile?.mkdirs() }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(target.length() > 3_000)
            if (details) {
                // 走真实关闭按钮让弹窗离场；未关闭的弹窗窗口会让同 JVM 后续组合收不到重组通知。
                clickSemantics(root) { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it == "关闭" }
                }
                repeat(4) {
                    Snapshot.sendApplyNotifications()
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
                }
                assertTrue("Tool details sheet should be dismissed", ShadowDialog.getLatestDialog()?.isShowing != true)
            }
            if (spacing) {
                val state = requireNotNull(processState)
                fun chainHeight() = state.layoutInfo.visibleItemsInfo.single { it.key == "tool_steps_plan" }.size
                // 无头渲染只能靠显式帧推进动画，按测量高度轮询到连续两帧稳定再断言，
                // 避免整组运行时动画未结束就取到中间高度。
                fun settleChainLayout(): Int {
                    var previous = -1
                    var stableFrames = 0
                    repeat(80) { frame ->
                        Snapshot.sendApplyNotifications()
                        root.composeOwner()!!.measureAndLayoutForTest()
                        layout()
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(24))
                        val current = chainHeight()
                        // 前几帧只负责启动动画，之后连续两帧高度一致才认为已收敛。
                        stableFrames = if (frame >= 4 && current == previous) stableFrames + 1 else 0
                        previous = current
                        if (stableFrames >= 2) return current
                    }
                    return previous
                }
                val expandedHeight = chainHeight()
                // 头部 + 四条紧凑行（三条工具 + 一条思考）。
                assertTrue("One header plus four compact rows must not have large blank gaps: $expandedHeight",
                    expandedHeight <= 152)
                val title = chainTitleText(root)
                assertTrue("Chain title must count tool calls only, thinking excluded: $title", title.contains("3 次"))
                activateChainTitle(root)
                val collapsedHeight = settleChainLayout()
                root.draw(Canvas(bitmap))
                File("build/reports/chat-timeline/process-chain-collapsed.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                assertTrue("Tapping the chain title must hide its rows ($expandedHeight -> $collapsedHeight)",
                    collapsedHeight < expandedHeight / 2)
                activateChainTitle(root)
                val restoredHeight = settleChainLayout()
                assertTrue("Tapping the collapsed title must restore the chain ($collapsedHeight -> $restoredHeight)",
                    restoredHeight > collapsedHeight * 2)
            }
            bitmap.recycle()
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            // 显式释放组合并复位帧时钟，避免残留状态让同一 JVM 内后续用例收不到重组通知。
            (activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ComposeView)
                ?.disposeComposition()
            controller.pause().stop().destroy()
            ShadowChoreographer.reset()
        }
    }

    private fun View.composeOwner(): ViewRootForTest? {
        if (this is ViewRootForTest) return this
        if (this is ViewGroup) for (i in 0 until childCount) getChildAt(i).composeOwner()?.let { return it }
        return null
    }

    private fun semanticsNodes(root: View): Sequence<SemanticsNode> {
        val owner = requireNotNull(root.composeOwner())
        fun walk(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
            yield(node)
            node.children.forEach { yieldAll(walk(it)) }
        }
        return walk(owner.semanticsOwner.rootSemanticsNode)
    }

    private fun clickSemantics(root: View, predicate: (SemanticsNode) -> Boolean) {
        val target = semanticsNodes(root).single {
            predicate(it) && it.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(requireNotNull(target.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
    }

    private fun chainTitleText(root: View): String = semanticsNodes(root).single { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("工具调用") } &&
            node.config.getOrNull(SemanticsActions.OnClick) != null
    }.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }

    private fun activateChainTitle(root: View) = clickSemantics(root) { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.startsWith("工具调用") }
    }

    @androidx.compose.runtime.Composable
    private fun ProcessFixture(onState: (LazyListState) -> Unit) {
        val state = rememberLazyListState()
        androidx.compose.runtime.SideEffect { onState(state) }
        val calls = listOf(
            ToolCall("plan", 1, HarnessTool.PLAN, buildJsonObject {}),
            ToolCall("cmd", 1, HarnessTool.BASE, buildJsonObject { put("command", "echo environment") }),
            ToolCall("ask", 1, HarnessTool.ASK_USER, buildJsonObject {}),
        )
        val durations = listOf(0L, 2_100L, 33_000L)
        val results = calls.mapIndexed { i, call ->
            ToolResult("result-${call.id}", 2, call.id, true, "ok", durationMs = durations[i])
        }
        val messages = listOf(
            UserMessage("u", 0, "创建一个安卓 app"),
            AssistantText("intro", 1, "我来先摸清环境，同时确认几个会影响结果的关键点。",
                reasoning = "核对环境。", reasoningMs = 10_900)
        ) + calls.zip(results).flatMap { (call, result) -> listOf(call, result) } +
            listOf(AssistantText("after", 3, "收到：极简可运行 Demo + Jetpack Compose。",
                reasoning = "确认工具链用法。", reasoningMs = 3_200))
        ChatMessageList(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), listState = state,
            messages = messages, toolResults = results.associateBy { it.toolCallId }, initializing = false,
            running = true, status = null, workspace = "", workspaceProject = null, onboardingPrivilege = null,
            thinkingExpanded = false, thinkingLive = false, liveThinkingMessageId = null,
            lastAssistantMessageId = "after", knownMentionNames = emptyList(), onSelectCommand = {},
            onEditMessage = {}, onDeleteMessage = {}, onCreateBranch = {}, onRegenerate = {}, onRetryTool = {},
            onOpenFile = null, activeCompaction = null, activePlan = null, pendingApprovals = emptyList(),
            onResolveApproval = { _, _, _ -> })
    }

    @androidx.compose.runtime.Composable
    private fun SidebarFixture() {
        val now = System.currentTimeMillis()
        fun session(id: String, title: String, age: Long, workspace: String = "") =
            HarnessSessionEntity(id, title, now - age, now - age, null, workspace = workspace)
        val sessions = listOf(
            session("demo", "Android Demo 构建", 0, "/workspace/demo"),
            session("theme", "主题与交互细节", 60_000, "/workspace/demo"),
            session("tools", "终端工具链排查", 60_000),
            session("notes", "整理项目想法", 24 * 60 * 60_000L),
            session("old", "周末的几个小实验", 10 * 24 * 60 * 60_000L),
        )
        SessionsSideDrawer(visible = true, sessions = sessions, currentSessionId = "demo",
            workspaces = listOf(WorkspaceProject("Android Demo", "/workspace/demo", "/workspace/demo", 0,
                projectType = ProjectType.ANDROID)),
            sessionRunStates = mapOf("tools" to SessionRunState.RUNNING), onDismiss = {}, onSettings = {},
            onSwitch = {}, onNew = {}, onCreateInWorkspace = {}, onDelete = {}, onRename = { _, _ -> })
    }
}
