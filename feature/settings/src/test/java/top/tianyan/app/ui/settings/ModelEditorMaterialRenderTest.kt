package top.tianyan.app.ui.settings

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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
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
import top.tianyan.app.core.tools.AgentProviderDefinition
import top.tianyan.app.core.tools.ProviderGroup
import top.tianyan.app.ui.theme.TianyanTheme
import java.io.File
import java.time.Duration

/** 真正渲染面板并执行语义动作；不访问 Provider 或用户设备。 */
abstract class ModelEditorRenderSupport {

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
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
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
            val target = File("build/reports/model-editor/$name.png").apply { parentFile?.mkdirs() }
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
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h800dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelEditorMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun providerPresetSelectionUpdatesTheFormWithoutCallingAnApi() {
        val providers = listOf(
            AgentProviderDefinition("openai", "OpenAI", "https://api.openai.com/v1", group = ProviderGroup.OFFICIAL,
                recommendedModels = listOf("gpt-4o", "gpt-5-mini")),
            AgentProviderDefinition("deepseek", "DeepSeek", "https://api.deepseek.com/v1", group = ProviderGroup.CHINA,
                recommendedModels = listOf("deepseek-chat", "deepseek-reasoner")),
        )
        render("editor-light", height = 800, content = {
            ModelEditorContent(modelId = null, existing = null, providers = providers,
                discovered = emptyList(), discovering = false, discoveryError = null, testing = false, testResult = null,
                discover = { _, _, _ -> fail("Rendering must not discover models") },
                test = { _, _, _, _, _ -> fail("Rendering must not call a provider") },
                save = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> }, onFillFromJson = { null }, showImportDialog = false,
                onDismissImportDialog = {})
        }) { root ->
            assertTrue(hasText(root, "接口与基础连接"))
            click(root, "OpenAI")
            val picker = requireNotNull(ShadowDialog.getLatestDialog()).window!!.decorView
            picker.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            picker.layout(0, 0, 393, 800)
            settle()
            snapshot(picker, 393, 800, "provider-presets")
            click(picker, "DeepSeek")
            assertTrue(hasText(root, "DeepSeek"))
        }
    }
}
