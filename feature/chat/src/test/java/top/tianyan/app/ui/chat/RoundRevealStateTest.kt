package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [RoundRevealState] 不依赖组合：`onToggled` / `start` / `stop` / `limits`
 * 只读写 snapshot state，可在普通 JVM 单测里直接断言。
 */
class RoundRevealStateTest {

    private fun button(roundKey: String, hiddenItemCount: Int, isExpanded: Boolean) =
        ChatRenderItem.CollapseButtonItem(
            roundKey = roundKey,
            hiddenSteps = hiddenItemCount,
            totalSteps = hiddenItemCount + 2,
            hiddenDurationMs = 0L,
            isExpanded = isExpanded,
            hiddenItemCount = hiddenItemCount,
        )

    @Test
    fun `expanding a collapsed button with more than three hidden items starts reveal`() {
        val state = RoundRevealState()
        val item = button(roundKey = "u1", hiddenItemCount = 4, isExpanded = false)

        state.onToggled(item, wasExpanded = false, enabled = true)

        assertEquals(mapOf("u1" to 3), state.limits)
    }

    @Test
    fun `hidden segment of three or fewer does not start reveal`() {
        val state = RoundRevealState()

        state.onToggled(button("u1", hiddenItemCount = 3, isExpanded = false), wasExpanded = false, enabled = true)
        assertEquals(emptyMap<String, Int>(), state.limits)

        state.onToggled(button("u1", hiddenItemCount = 0, isExpanded = false), wasExpanded = false, enabled = true)
        assertEquals(emptyMap<String, Int>(), state.limits)
    }

    @Test
    fun `collapsing stops reveal and leaves limits empty`() {
        val state = RoundRevealState()
        state.start("u1", totalItems = 10)
        assertEquals(mapOf("u1" to 3), state.limits)

        state.onToggled(button("u1", hiddenItemCount = 10, isExpanded = true), wasExpanded = true, enabled = true)

        assertEquals(emptyMap<String, Int>(), state.limits)
        state.stop()
        assertEquals(emptyMap<String, Int>(), state.limits)
    }
}
