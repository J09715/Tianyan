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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.ui.theme.TianyanTheme
import java.io.File
import java.time.Duration

/** 真正渲染面板并执行语义动作；不访问 Provider 或用户设备。 */
abstract class ModelFlowRenderSupport {
    protected val profile = AiModelEntity("profile", "OpenAI", "OpenAI", "gpt-4o", createdAt = 0)

    protected fun render(name: String, dark: Boolean = false, scale: Float = 1f, width: Int = 393,
                         height: Int = 700, content: @Composable () -> Unit, check: (View) -> Unit) {
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        controller.setup()
        try {
            activity.setContent {
                TianyanTheme(darkTheme = dark, dynamicColor = false) {
                    CompositionLocalProvider(LocalInspectionMode provides true) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                    }
                }
            }
            layout(activity.window.decorView, width, height)
            settle()
            val root = ShadowDialog.getLatestDialog()?.window?.decorView ?: activity.window.decorView
            layout(root, width, height)
            settle()
            snapshot(root, width, height, "$name-initial")
            check(root)
            layout(root, width, height)
            snapshot(root, width, height, name)
        } finally {
            ShadowDialog.getLatestDialog()?.dismiss()
            settle()
            (activity.window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ComposeView)
                ?.disposeComposition()
            controller.pause().stop().destroy()
            ShadowChoreographer.reset()
        }
    }

    protected fun snapshot(root: View, width: Int, height: Int, name: String) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val target = File("build/reports/model-flow/$name.png").apply { parentFile?.mkdirs() }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            assertTrue(target.length() > 3_000)
    }

    protected fun settle() {
        repeat(4) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250))
        }
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
    }

    protected fun nodes(root: View): List<SemanticsNode> {
        fun owner(view: View): ViewRootForTest? {
            if (view is ViewRootForTest) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) owner(view.getChildAt(i))?.let { return it }
            return null
        }
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap { walk(it) }
        return walk(requireNotNull(owner(root)).semanticsOwner.rootSemanticsNode)
    }

    protected fun click(root: View, label: String) {
        val node = nodes(root).single {
            it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == label } &&
                it.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
        settle()
        layout(root, root.width, root.height)
        settle()
    }

    protected fun hasText(root: View, value: String) = nodes(root).any {
        it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains(value) }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProviderSelectionRenderTest : ModelFlowRenderSupport() {
    @Test fun currentVariantAndProfileActionsRemainReachable() {
        var selected: String? = null
        render("providers-light", content = {
            ModelDialog(listOf(profile, profile.copy(id = "deepseek", name = "DeepSeek", provider = "DeepSeek", model = "deepseek-chat")),
                "profile", "gpt-5-mini", emptyList(), false, null, null,
                {}, { selected = it }, { _, _ -> }, {}, {}, {}, { _, _ -> }, {}, {})
        }) { root ->
            assertTrue(hasText(root, "gpt-5-mini"))
            assertTrue(hasText(root, "添加"))
            click(root, "DeepSeek")
            assertEquals("deepseek", selected)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h600dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelSelectionRenderTest : ModelFlowRenderSupport() {
    @Test fun selectingDiscoveredModelPassesExactId() {
        var selected: String? = null
        render("models-dark-large", dark = true, scale = 1.3f, width = 360, height = 600, content = {
            ProviderModelPickerDialog(profile, "gpt-4o", listOf("gpt-4o", "gpt-5-mini", "claude-sonnet-4.5"),
                false, null, {}, {}, { selected = it })
        }) { root ->
            click(root, "gpt-5-mini")
            assertEquals("gpt-5-mini", selected)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h420dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManualModelRenderTest : ModelFlowRenderSupport() {
    @Test fun manualInputWorksInShortViewportWithoutDiscovery() {
        var selected: String? = null
        render("manual-short-viewport", width = 360, height = 420, content = {
            ProviderModelPickerDialog(profile, "gpt-4o", emptyList(), false, "暂时无法发现模型", {}, {}, { selected = it })
        }) { root ->
            click(root, "手动输入")
            snapshot(root, 360, 420, "manual-after-toggle")
            val input = requireNotNull(nodes(root).singleOrNull { it.config.getOrNull(SemanticsActions.SetText) != null }) {
                nodes(root).joinToString("\n") { it.config.toString() }
            }
            assertTrue(requireNotNull(input.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString("custom-model")))
            settle()
            click(root, "应用")
            assertEquals("custom-model", selected)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h600dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerMaterialRenderTest : ModelFlowRenderSupport() {
    @Test fun nativeInputEnablesSendAndPreservesTheDraft() {
        val draft = mutableStateOf("")
        var sent = false
        render("composer-dark-large", dark = true, scale = 1.3f, width = 360, height = 600, content = {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
                ChatComposer(listState = rememberLazyListState(), running = false, initializing = false,
                    status = null, messages = emptyList(), toolResults = emptyMap(), workspace = "",
                    input = draft.value, onInputChanged = { draft.value = it }, onSend = { sent = true }, onStop = {},
                    sendMode = ComposerSendMode.NEXT_RUN, matchingCommands = emptyList(), onApplyCommand = {},
                    matchingMentions = emptyList(), onApplyMention = {}, attachments = emptyList(),
                    attachmentsProcessing = false, onAttachmentsPicked = { _, _ -> }, onRemoveAttachment = {},
                    queuedPrompts = emptyList(), onEditQueuedPrompt = {}, onRemoveQueuedPrompt = {}, onConvertToSteer = {},
                    knownMentionNames = emptyList(), attachedMentions = emptyList(), onRemoveMention = {},
                    pinnedCapabilities = emptyList(), onUnpinMention = {}, onOpenSkillsMcp = {}, onCompactContext = {}, onOpenModels = {},
                    activeModel = profile, approvalMode = ApprovalMode.REQUEST, onOpenApprovalModes = {}, contextUsage = ContextUsage())
            }
        }) { root ->
            val input = nodes(root).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(input.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString("帮我检查这段代码")))
            settle()
            assertEquals("帮我检查这段代码", draft.value)
            val send = nodes(root).single {
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text ->
                    text == RuntimeEnvironment.getApplication().getString(top.tianyan.app.feature.chat.R.string.chat_send)
                }
            }
            assertNull(send.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(requireNotNull(send.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            assertTrue(sent)
        }
    }
}
