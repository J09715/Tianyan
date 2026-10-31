package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 能力白名单 chips 的切换语义（PR #72 审阅修复）：
 * 不限制下首次点选 = 仅该服务；取消到空回退 null（不限制）而非空集合（空集合会拒全部 MCP）。
 */
class BotCapabilitySelectionTest {

    @Test fun `first tap from unrestricted selects only that server`() {
        assertEquals(listOf("codegraph"), toggleCapabilitySelection(null, "codegraph"))
    }

    @Test fun `tapping another server adds it to the selection`() {
        assertEquals(
            listOf("codegraph", "browser"),
            toggleCapabilitySelection(listOf("codegraph"), "browser"),
        )
    }

    @Test fun `tapping a selected server removes it case-insensitively`() {
        assertEquals(
            listOf("browser"),
            toggleCapabilitySelection(listOf("codegraph", "browser"), "CodeGraph"),
        )
    }

    @Test fun `cancelling the last selection falls back to unrestricted`() {
        assertNull(toggleCapabilitySelection(listOf("codegraph"), "codegraph"))
    }
}
