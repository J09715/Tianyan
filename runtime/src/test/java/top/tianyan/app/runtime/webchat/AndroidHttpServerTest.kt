package top.tianyan.app.runtime.webchat

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidHttpServerTest {

    @Test
    fun `serves a fixed length response over a socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/test") { exchange ->
            val body = "{\"ok\":true}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            server.start()
            val response = Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().apply {
                    write("GET /api/test HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                socket.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Length: 11"))
            assertTrue(response.endsWith("{\"ok\":true}"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `stop closes open ended connections and releases the listening socket`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val streaming = CountDownLatch(1)
        server.createContext("/api/events") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.write("event: ping\ndata: {}\n\n".toByteArray())
            exchange.responseBody.flush()
            streaming.countDown()
            // 故意不关闭：模拟 WebChat 的 SSE 长连接，回收责任落在 stop() 上。
        }

        val client = Socket()
        try {
            server.start()
            client.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            client.soTimeout = 5_000
            client.getOutputStream().apply {
                write("GET /api/events HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                flush()
            }
            val input = client.getInputStream()
            assertTrue("SSE 首字节应可读", input.read() >= 0)
            assertTrue("handler 应已接管连接", streaming.await(5, TimeUnit.SECONDS))

            server.stop(0)

            // 连接被服务端关闭时读到 EOF 或 RST；若仍挂着，soTimeout 会抛超时 —— 那就是泄漏。
            val outcome = runCatching {
                var value = input.read()
                while (value >= 0) value = input.read()
            }
            assertFalse(
                "stop() 必须关闭已 accept 的长连接",
                outcome.exceptionOrNull() is SocketTimeoutException,
            )

            // 监听 socket 已释放：同一端口应能重新绑定。
            // 用有界等待而不是一次性断言 —— TCP 拆链是内核异步完成的，要求"零延迟"
            // 在部分内核上会假失败；但真泄漏（监听 socket 没关）在超时后照样会被抓到。
            assertTrue(
                "stop() 必须释放监听 socket，使同端口可重新绑定",
                awaitRebindable(port, 5_000),
            )
        } finally {
            runCatching { client.close() }
            server.stop(0)
        }
    }

    @Test
    fun `server can be restarted after stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        server.createContext("/api/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
            exchange.close()
        }

        try {
            repeat(2) { round ->
                // 重启前先等端口真正可用：断言的是"能重启"，不是"零延迟能重启"。
                if (round > 0) assertTrue("端口应在 stop() 后恢复可用", awaitRebindable(port, 5_000))
                server.start()
                val response = Socket("127.0.0.1", port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().apply {
                        write("GET /api/ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    socket.getInputStream().bufferedReader().readText()
                }
                assertTrue("第 ${round + 1} 次启动应正常响应", response.endsWith("pong"))
                server.stop(0)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `injected executor is owned by the caller and survives stop`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = AndroidHttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        val injected = Executors.newSingleThreadExecutor()
        server.executor = injected

        try {
            server.start()
            server.stop(0)
            assertFalse("外部注入的线程池不应被 stop() 关闭", injected.isShutdown)
        } finally {
            injected.shutdownNow()
            server.stop(0)
        }
    }

    /**
     * 轮询到同端口可重新绑定为止。
     *
     * 监听 socket 被 close() 之后，内核还需要一点时间回收本地端口（取决于对端是否
     * 已回 FIN、以及内核的 TIME_WAIT/FIN_WAIT_2 处理）。所以这里给一个有界窗口，
     * 而不是断言"立刻"。若 stop() 根本没关监听 socket，整个窗口内都会失败。
     */
    private fun awaitRebindable(port: Int, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            try {
                ServerSocket().use { probe ->
                    probe.reuseAddress = true
                    probe.bind(InetSocketAddress("127.0.0.1", port))
                }
                return true
            } catch (_: java.net.BindException) {
                if (System.currentTimeMillis() >= deadline) return false
                Thread.sleep(50)
            }
        }
    }
}
