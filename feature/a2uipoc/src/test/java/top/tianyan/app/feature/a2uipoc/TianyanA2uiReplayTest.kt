package top.tianyan.app.feature.a2uipoc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TianyanA2uiReplayTest {
    private val replay = TianyanA2uiReplay()

    @Test
    fun `同一工具调用只投喂一次，删除后旧卡片不再创建，新调用可以`() {
        assertFalse(replay.shouldSkip("call-1", "content", "s1"))
        replay.record("call-1", "content", "s1", emptySet())
        assertTrue(replay.shouldSkip("call-1", "content", "s1"))

        replay.record("call-del", "delete", null, setOf("s1"))
        assertTrue(replay.shouldSkip("call-1", "content", "s1"))
        assertTrue(replay.shouldSkip(null, "content", "s1"))
        assertFalse(replay.shouldSkip("call-2", "content", "s1"))

        replay.record("call-2", "content", "s1", emptySet())
        assertFalse(replay.shouldSkip(null, "content-2", "s1"))
    }
}
