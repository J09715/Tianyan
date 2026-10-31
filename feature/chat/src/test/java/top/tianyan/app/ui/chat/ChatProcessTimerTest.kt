package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatProcessTimerTest {
    @Test fun tenthsRemainVisibleForShortAndLongOperations() {
        assertEquals("0.0s", formatProcessDuration(0))
        assertEquals("0.0s", formatProcessDuration(99))
        assertEquals("0.1s", formatProcessDuration(100))
        assertEquals("10.1s", formatProcessDuration(10_100))
        assertEquals("61.2s", formatProcessDuration(61_299))
        assertEquals("0.0s", formatProcessDuration(-1))
    }
}
