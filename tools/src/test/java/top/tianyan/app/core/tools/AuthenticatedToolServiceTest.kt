package top.tianyan.app.core.tools

import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.*
import top.tianyan.app.runtime.shell.*

class AuthenticatedToolServiceTest {
    @Test fun pageCloseKeepsTheAuthenticatedProcessAndReopeningUsesTheSameInstance() = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.controller.start("github", fixture.adapter, fixture.spec)
            assertEquals(1, fixture.launches)
            val auth = fixture.controller.authentication("github", "debian")!!
            assertNull(fixture.controller.authentication("github", "ubuntu"))
            assertTrue(fixture.command!!.environment.isEmpty())
            assertEquals("gh-ui serve", fixture.command!!.commandLine)
            val source = ToolServiceWebSource(ToolServiceDescriptor(fixture.port, "/", "github", "debian", authentication = auth), { true })
            assertTrue(source.snapshot().containsKey("index.html"))
            assertTrue(source.close())
            assertTrue(first.session.isAlive)
            assertEquals(0, fixture.stops)
            assertSame(first, fixture.controller.start("github", fixture.adapter, fixture.spec))
            val reopened = ToolServiceWebSource(ToolServiceDescriptor(fixture.port, "/", "github", "debian", authentication = auth), { true })
            assertEquals("{\"job\":\"retained\"}", reopened.read("api/current-job"))
            assertTrue(reopened.close())
            assertEquals(1, fixture.launches)
            fixture.controller.stop("github")
            assertNull(fixture.controller.authentication("github", "debian"))
            assertFalse(first.session.isAlive)
        } finally { fixture.close() }
    }

    @Test fun aPanelThatBindsItsPortBeforeItsRoutesAreReadyIsRetriedInsteadOfKilled() = runBlocking {
        val fixture = Fixture()
        try {
            // 端口先于面板路由/签名就绪：旧实现会直接判「服务身份验证失败」并杀掉刚起来的实例。
            fixture.identityFailures = 1
            val process = fixture.controller.start("github", fixture.adapter, fixture.spec)
            assertTrue(process.session.isAlive)
            assertSame(process, fixture.controller.start("github", fixture.adapter, fixture.spec))
            assertEquals(1, fixture.launches)
            assertEquals(0, fixture.stops)
            assertNotNull(fixture.controller.authentication("github", "debian"))
        } finally { fixture.close() }
    }

    @Test fun anOccupiedPortCannotReceiveAProcessBootstrapOrBeAdopted() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.server.start(fixture.port)
            assertTrue(runCatching { fixture.controller.start("github", fixture.adapter, fixture.spec) }.isFailure)
            assertEquals(0, fixture.launches)
            assertNull(fixture.controller.authentication("github", "debian"))
        } finally { fixture.close() }
    }

    private class Fixture {
        val port = ServerSocket(0).use { it.localPort }
        val server = MockWebServer()
        val processes = linkedMapOf<String, ManagedProcess>()
        var launches = 0
        var stops = 0
        var identityFailures = 0
        var command: ShellCommand? = null
        var running = false
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun sign(key: ByteArray, text: String) = hex(Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray())
        })
        inner class Session : LinuxSession {
            var alive = true
            override val isAlive get() = alive
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) {
                val key = Regex("[a-f0-9]{64}").find(data.decodeToString())!!.value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val parts = request.getHeader(ToolServiceAuthentication.HEADER)!!.split(':')
                        val requestHash = hex(MessageDigest.getInstance("SHA-256").digest(request.body.readByteArray()))
                        assertEquals(parts[2], sign(key, "request\n${parts[0]}\n${parts[1]}\n${request.method}\n${request.path}\n$requestHash"))
                        // 面板刚绑定端口、路由或签名还没就绪时返回一个不可验证的响应。
                        if (identityFailures > 0) { identityFailures--; return MockResponse().setResponseCode(503) }
                        val body = if (request.path == "/api/current-job") "{\"job\":\"retained\"}" else "<!doctype html><p>page</p>"
                        val hash = hex(MessageDigest.getInstance("SHA-256").digest(body.toByteArray()))
                        return MockResponse().setBody(body).setHeader(ToolServiceAuthentication.RESPONSE_HEADER,
                            sign(key, "response\n${parts[1]}\n200\n$hash"))
                    }
                }
                server.start(port)
                running = true
            }
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override suspend fun interrupt() = Unit
            override suspend fun close() { alive = false }
        }
        private fun stop(process: ManagedProcess): Boolean {
            (process.session as Session).alive = false
            processes.remove(process.id)
            stops++
            return true
        }
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getSupportsBackgroundInstanceStop" -> true
                "startBackground" -> {
                    launches++; command = args[1] as ShellCommand
                    assertEquals("debian", args[4])
                    ManagedProcess(args[0] as String, 1, Session(), "github").also { processes[it.id] = it }
                }
                "listBackground" -> processes.values.toList()
                "stopBackgroundInstance" -> stop(args[0] as ManagedProcess)
                "stopBackground" -> { processes[args[0]]?.let { stop(it) }; true }
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime
        val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "serviceLaunch" -> ServiceLaunch("gh-ui serve", emptyMap(), authenticated = true, distroId = "debian")
                else -> error("Unexpected adapter call: ${method.name}")
            }
        } as ToolRuntimeAdapter
        val controller = ToolServiceController(runtime)
        val spec = LocalServiceSpec("github", port, startupTimeoutMs = 2000, authenticated = true, distroId = "debian")
        fun close() { server.close() }
    }
}
