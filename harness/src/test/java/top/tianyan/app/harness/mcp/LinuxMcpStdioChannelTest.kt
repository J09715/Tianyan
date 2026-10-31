package top.tianyan.app.harness.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.TerminalOutput
import top.tianyan.app.runtime.shell.TerminalStream

class LinuxMcpStdioChannelTest {
    @Test fun `startup EOF exposes bounded diagnostics with credentials removed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = object : LinuxSession by CompletingSession() {
            override val output = flow {
                emit(TerminalOutput(TerminalStream.STDOUT, "x".repeat(4096) + "\n"))
                emit(TerminalOutput(TerminalStream.STDOUT, "python3: can't open file server.py password=private-password custom-secret\n"))
            }
        }
        val channel = LinuxMcpStdioChannel("srv", session, scope, secretValues = listOf("custom-secret"))
        try {
            val result = kotlinx.coroutines.withTimeout(2_000) { channel.incoming.receiveCatching() }
            val message = result.exceptionOrNull()!!.message!!
            assertTrue(message.contains("can't open file"))
            assertTrue(message.length < 2200)
            assertTrue(!message.contains("private-password") && !message.contains("custom-secret"))
        } finally { channel.close(); scope.cancel() }
    }

    /** output 永不完成的会话：构造"泵挂起在 lines.send 上"的泄漏前提（消费方已离场）。 */
    private class EndlessSession : LinuxSession {
        override val isAlive = true
        override val output = flow {
            var i = 0
            while (true) {
                emit(TerminalOutput(TerminalStream.STDOUT, "{\"resp\":$i}\n"))
                delay(10)
            }
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {}
    }

    /** 输出一行后正常完成的会话：锁定泵的正常收尾路径。 */
    private class CompletingSession : LinuxSession {
        override val isAlive = true
        override val output = flow {
            emit(TerminalOutput(TerminalStream.STDOUT, "{\"resp\":1}\n"))
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {}
    }

    /** 连续吐出多条超长行后正常完成的会话：锁定"单帧熔断"行为。 */
    private class OversizedSpamSession(maxFrameChars: Int) : LinuxSession {
        override val isAlive = true
        override val output = flow {
            val oversizedLine = "x".repeat(maxFrameChars + 1024) + "\n"
            emit(TerminalOutput(TerminalStream.STDOUT, oversizedLine))
            emit(TerminalOutput(TerminalStream.STDOUT, oversizedLine))
            emit(TerminalOutput(TerminalStream.STDOUT, oversizedLine))
            emit(TerminalOutput(TerminalStream.STDOUT, "{\"ok\":true}\n"))
        }
        override suspend fun write(data: ByteArray) {}
        override suspend fun resize(columns: Int, rows: Int) {}
        override suspend fun interrupt() {}
        override suspend fun close() {}
    }

    @Test
    fun `consecutive oversized lines produce a single circuit-break error frame`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val maxFrameChars = 64
        // 用一个已存在的普通文件充当"目录"：mkdirs 必然失败 → 落盘不可用 → 锁定回退熔断路径
        val blockedDir = java.io.File.createTempFile("tianyan-spill-blocker", ".tmp").apply { deleteOnExit() }
        val channel = LinuxMcpStdioChannel(
            "srv", OversizedSpamSession(maxFrameChars), scope = scope,
            maxFrameChars = maxFrameChars, spillDirectory = blockedDir,
        )
        val frames = mutableListOf<String>()
        withTimeoutOrNull(2_000L) {
            for (frame in channel.incoming) {
                frames += frame
            }
        }
        scope.cancel()

        val errorFrames = frames.filter { it.contains("-32603") }
        assertTrue("连续超长行只应广播一条熔断帧，实际 ${errorFrames.size} 条: $frames", errorFrames.size == 1)
        assertTrue("正常帧应照常转发", frames.any { it.contains("\"ok\":true") })
    }

    @Test
    fun `oversized line spills to disk and delivers a synthetic tool result frame`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val maxFrameChars = 64
        val spillDir = java.io.File(System.getProperty("java.io.tmpdir"), "tianyan-mcp-spill-test-${System.nanoTime()}").apply { mkdirs() }
        val payload = "x".repeat(maxFrameChars + 512)
        val oversizedLine = """{"jsonrpc":"2.0","id":"req-1","result":{"content":[{"type":"text","text":"$payload"}]}}""" + "\n"
        val session = object : LinuxSession by CompletingSession() {
            override val output = flow {
                emit(TerminalOutput(TerminalStream.STDOUT, oversizedLine))
                emit(TerminalOutput(TerminalStream.STDOUT, "{\"ok\":true}\n"))
            }
        }
        val channel = LinuxMcpStdioChannel(
            "srv", session, scope = scope,
            maxFrameChars = maxFrameChars, spillDirectory = spillDir,
        )
        val frames = mutableListOf<String>()
        withTimeoutOrNull(2_000L) {
            for (frame in channel.incoming) frames += frame
        }
        scope.cancel()

        val spillFrame = frames.firstOrNull { it.contains("已转存至文件") }
        assertTrue("超长行应转存并合成 tool-result 帧: $frames", spillFrame != null)
        assertTrue("合成帧应携带原始请求 id", spillFrame!!.contains("\"id\":\"req-1\""))
        assertTrue("合成帧应为成功 tool-result 而非 error", spillFrame.contains("\"isError\":false"))
        val spillFiles = spillDir.listFiles()!!.filter { it.name.startsWith("mcp_spill_") }
        assertTrue("应恰好落盘一个文件", spillFiles.size == 1)
        assertTrue("落盘文件应包含完整原始行内容", spillFiles[0].readText().contains(payload))
        assertTrue("后续正常帧应照常转发", frames.any { it.contains("\"ok\":true") })
        spillDir.deleteRecursively()
    }

    @Test
    fun `spill beyond hard cap falls back to circuit break`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val maxFrameChars = 64
        val maxSpillChars = 256L
        val spillDir = java.io.File(System.getProperty("java.io.tmpdir"), "tianyan-mcp-spill-cap-${System.nanoTime()}").apply { mkdirs() }
        val payload = "x".repeat(maxFrameChars + 2048)
        val oversizedLine = """{"jsonrpc":"2.0","id":"req-9","result":{"content":[{"type":"text","text":"$payload"}]}}""" + "\n"
        val session = object : LinuxSession by CompletingSession() {
            override val output = flow {
                emit(TerminalOutput(TerminalStream.STDOUT, oversizedLine))
                emit(TerminalOutput(TerminalStream.STDOUT, "{\"ok\":true}\n"))
            }
        }
        val channel = LinuxMcpStdioChannel(
            "srv", session, scope = scope,
            maxFrameChars = maxFrameChars, maxSpillChars = maxSpillChars, spillDirectory = spillDir,
        )
        val frames = mutableListOf<String>()
        withTimeoutOrNull(2_000L) {
            for (frame in channel.incoming) frames += frame
        }
        scope.cancel()

        val errorFrames = frames.filter { it.contains("-32603") }
        assertTrue("超过落盘硬顶应回退熔断帧: $frames", errorFrames.size == 1)
        assertTrue("熔断帧应携带原始请求 id", errorFrames[0].contains("\"id\":\"req-9\""))
        assertTrue("不应产生转存成功帧", frames.none { it.contains("已转存至文件") })
        assertTrue("熔断后正常帧应照常转发", frames.any { it.contains("\"ok\":true") })
        assertTrue("被中止的落盘残余文件应被删除", spillDir.listFiles()!!.none { it.name.startsWith("mcp_spill_") })
        spillDir.deleteRecursively()
    }

    @Test
    fun `close unwinds a pump stalled on a full send buffer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 容量 1：泵发出第 2 行即挂起在 send 上，session.close() 无法 resume 它——
        // close() 若不取消 scope，泵与 Channel 永久泄漏（每次销毁忙连接漏一个）
        val channel = LinuxMcpStdioChannel("srv", EndlessSession(), scope = scope, bufferCapacity = 1)
        delay(200)
        channel.close()
        val drained = withTimeoutOrNull(2_000L) {
            while (!channel.incoming.receiveCatching().isClosed) {
                // 逐条排空缓冲，直到拿到关闭标记
            }
            true
        }
        assertTrue("close() must terminate the pump and close the lines channel", drained == true)
        scope.cancel()
    }

    @Test
    fun `pump closes the lines channel when the session output completes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = LinuxMcpStdioChannel("srv", CompletingSession(), scope = scope, bufferCapacity = 8)
        val drained = withTimeoutOrNull(2_000L) {
            while (!channel.incoming.receiveCatching().isClosed) {
                // 正常完成路径同样要拿到关闭标记
            }
            true
        }
        assertTrue("output completion must close the lines channel", drained == true)
        scope.cancel()
    }
}
