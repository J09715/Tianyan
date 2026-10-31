package top.tianyan.app.ui.chat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 工具详情弹窗单独成类：弹窗窗口停留在同一 JVM 会影响后续用例的重组。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolDetailsRenderTest : ChatRenderTestSupport() {
    @Test fun renderModalToolDetails() = render(dark = false, scale = 1f, name = "tool-details", details = true)
}
