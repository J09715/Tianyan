package top.tianyan.app.ui.bot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.Duration
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.theme.TianyanTheme

/** Real Compose rendering, without a device, account, or model request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h850dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GroupChatRenderTest {
    @Test fun renderLightConversation() = render(false, 1f)
    @Test fun renderDarkConversationWithLargeText() = render(true, 1.3f)

    private fun render(dark: Boolean, scale: Float) {
        RuntimeEnvironment.setFontScale(scale)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        val activity = controller.get().apply { setTheme(android.R.style.Theme_Material_Light_NoActionBar) }
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        controller.setup()
        try {
            val members = listOf(
                Bot("planner", "阿策", "项目主控", "", "阿策", sessionId = "s1", isMainBot = true, createdAt = 0, updatedAt = 0),
                Bot("reviewer", "阿审", "质量审核", "", "阿审", sessionId = "s2", createdAt = 0, updatedAt = 0),
            )
            val question = GroupMessage("user", "g", "u", "我", GroupSenderType.USER, "检查项目配置，给出可以执行的建议。", 0)
            val answer = GroupMessage("answer", "g", "planner", "阿策", GroupSenderType.BOT,
                "已核对项目配置，构建流程正常。\n\n接下来优先处理 **界面一致性**，再由审核成员确认结果。", 1,
                steps = listOf(GroupToolStep("read", "read", true, 400, "build.gradle.kts"),
                    GroupToolStep("build", "base", true, 2400, "./gradlew assembleDebug")),
                reasoning = "先读取配置，再验证构建与模块依赖。")
            activity.setContent {
                TianyanTheme(darkTheme = dark, dynamicColor = false) {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Column(Modifier.fillMaxSize()) {
                            GroupChatTopBar(GroupChatViewMode.CHAT, "天衍项目协作", "2 位成员 · 阿策主持", false,
                                GroupWorkSummary(0, 0, 0), false, {}, {}, {}, {})
                            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                GroupMessageRow(question, null, true, true, {})
                                GroupMessageRow(answer, members.first(), true, true, {})
                                GroupMessageRow(GroupMessage("review", "g", "reviewer", "阿审", GroupSenderType.BOT,
                                    "收到，我会重点检查小屏布局与运行状态。", 2), members.last(), true, true, {})
                            }
                            GroupChatComposer(members, TextFieldValue(), emptyList(), false, false, false,
                                {}, {}, {}, {}, {}, {})
                        }
                    }
                }
            }
            val root = activity.window.decorView
            fun layout() {
                root.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, 393, 850)
            }
            repeat(24) { layout(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32)) }
            val bitmap = Bitmap.createBitmap(393, 850, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bitmap))
            val output = File("build/reports/group-chat/group-${if (dark) "dark-large" else "light"}.png")
            requireNotNull(output.parentFile).mkdirs()
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(output.length() > 1000)
            bitmap.recycle()
        } finally {
            (activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0) as? ComposeView)?.disposeComposition()
            controller.pause().stop().destroy()
            ShadowChoreographer.reset()
        }
    }
}
