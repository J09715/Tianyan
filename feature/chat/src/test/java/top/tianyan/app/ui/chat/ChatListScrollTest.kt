package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatListScrollTest {

    @Test
    fun `empty or unmeasured list has no scroll target`() {
        assertNull(lastLazyItemIndex(0))
        assertNull(lastLazyItemIndex(-1))
    }

    @Test
    fun `non-empty list scrolls to the last laid-out item`() {
        assertEquals(0, lastLazyItemIndex(1))
        assertEquals(11, lastLazyItemIndex(12))
    }
    @Test
    fun `jump action detects offscreen messages and clipped final message`() {
        assertEquals(false, isAwayFromChatBottom(0, null, 0, 800))
        assertEquals(false, isAwayFromChatBottom(10, null, 0, 800))
        assertEquals(true, isAwayFromChatBottom(10, 8, 780, 800))
        assertEquals(true, isAwayFromChatBottom(10, 9, 850, 800))
        assertEquals(false, isAwayFromChatBottom(10, 9, 800, 800))
        assertEquals(false, isAwayFromChatBottom(1, 0, 200, 800))
    }
}
