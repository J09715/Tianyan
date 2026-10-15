package top.tianyan.app.runtime.pty

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回显防护是协议会话的最后一道闸：即便 rawTerminal 在某个后端上没落地，
 * 宿主也不能把自己写出的帧当成引擎的反向请求。
 */
class FrameEchoGuardTest {

    @Test
    fun swallowsTheFrameThatWasJustWritten() {
        val guard = FrameEchoGuard()
        val frame = """{"jsonrpc":"2.0","id":"1","method":"initialize"}"""

        guard.record(frame)

        assertTrue(guard.isEcho(frame))
    }

    @Test
    fun anEchoIsSwallowedOnlyOnce() {
        val guard = FrameEchoGuard()
        val frame = """{"jsonrpc":"2.0","id":"1","method":"session/new"}"""
        guard.record(frame)

        assertTrue(guard.isEcho(frame))
        // 引擎若真的原样回一条同样的帧，第二次必须放行，否则会吞掉合法响应。
        assertFalse(guard.isEcho(frame))
    }

    @Test
    fun passesThroughFramesTheHostNeverWrote() {
        val guard = FrameEchoGuard()
        guard.record("""{"jsonrpc":"2.0","id":"1","method":"initialize"}""")

        assertFalse(guard.isEcho("""{"jsonrpc":"2.0","id":"1","result":{"protocolVersion":1}}"""))
    }

    @Test
    fun forgetsTheOldestFrameBeyondCapacity() {
        val guard = FrameEchoGuard(capacity = 2)
        guard.record("frame-1")
        guard.record("frame-2")
        guard.record("frame-3")

        assertFalse(guard.isEcho("frame-1"))
        assertTrue(guard.isEcho("frame-2"))
        assertTrue(guard.isEcho("frame-3"))
    }

    @Test
    fun toleratesConcurrentRecordAndLookup() {
        val guard = FrameEchoGuard(capacity = 512)
        val frames = (1..256).map { """{"id":"$it"}""" }

        val writer = Thread { frames.forEach(guard::record) }
        val reader = Thread { frames.forEach { guard.isEcho(it) } }
        writer.start()
        reader.start()
        writer.join()
        reader.join()

        // 只要求不抛并发异常，命中与否取决于交错顺序。
        assertEquals(guard.isEcho("never-recorded"), false)
    }
}
