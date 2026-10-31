package top.tianyan.app.ui.chat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 工具调用链的交互截图单独成类：弹窗/动画用例需要独立 JVM 才能稳定重组。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolChainInteractionRenderTest : ChatRenderTestSupport() {
    @Test fun renderAndCollapseOneContinuousToolChain() = render(false, 1f, "process-chain", spacing = true)
}
