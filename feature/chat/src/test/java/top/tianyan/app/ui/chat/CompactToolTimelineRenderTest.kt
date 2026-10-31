package top.tianyan.app.ui.chat

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Render the real compact components so their density can be visually reviewed without a provider/device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactToolTimelineRenderTest : ChatRenderTestSupport() {
    @Test fun renderCompactProcessWithoutLargeCardBackgrounds() = render(dark = false, scale = 1f, name = "compact-tools")
    @Test fun renderDarkProcessWithLargeSystemText() = render(dark = true, scale = 1.3f, name = "compact-tools-dark-large")
    @Test fun renderConversationSidebar() = render(dark = false, scale = 1f, name = "sidebar", sidebar = true)
}
