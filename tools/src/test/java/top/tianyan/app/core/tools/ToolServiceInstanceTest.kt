package top.tianyan.app.core.tools

import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.shell.*

/**
 * 生命周期按实例记账：重复启动幂等，停止只停控制器启动的那个实例，
 * 不会顺手带走同 toolId 的「新实例」或插件页面自建的实例。
 */
class ToolServiceInstanceTest {
    @Test fun duplicateStartReturnsTheSameInstanceWithoutRestarting() = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            val second = fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            assertSame(first, second)
            assertEquals(1, fixture.launches)
            assertTrue(first.session.isAlive)
        } finally { fixture.close() }
    }

    @Test fun stopOnlyStopsTheTrackedInstanceNotAnotherProcessWithTheSameToolId() = runBlocking {
        val fixture = Fixture()
        try {
            val owned = fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            // 页面自建/上一代遗留的另一实例：同 toolId、同发行版，但没有控制器登记。
            val foreign = fixture.register("gateway", "debian")
            fixture.controller.stop("gateway", fixture.spec)
            assertFalse(owned.session.isAlive)
            assertTrue(foreign.session.isAlive)
            assertTrue(fixture.processes.containsKey(foreign.id))
        } finally { fixture.close() }
    }

    @Test fun stopWithoutATrackedInstanceFallsBackToRegisteredProcesses() = runBlocking {
        val fixture = Fixture()
        try {
            val leftover = fixture.register("gateway", "debian")
            fixture.controller.stop("gateway", fixture.spec)
            assertFalse(leftover.session.isAlive)
            assertTrue(fixture.processes.isEmpty())
        } finally { fixture.close() }
    }

    @Test fun stopLeavesAnotherDistributionInstanceAlone() = runBlocking {
        val fixture = Fixture()
        try {
            val other = fixture.register("gateway", "ubuntu")
            fixture.controller.stop("gateway", fixture.spec)
            assertTrue(other.session.isAlive)
        } finally { fixture.close() }
    }

    @Test fun startingAndStoppingTheGatewayTogglesItsOngoingNotification() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            assertEquals(setOf("debian" to "gateway"), fixture.sink.serviceNotifications())
            assertEquals("http://127.0.0.1:${fixture.port}/tianyan/", fixture.sink.detail)
            // 重复启动幂等：不应重复贴通知。
            fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            assertEquals(1, fixture.sink.shows)
            fixture.controller.stop("gateway", fixture.spec)
            assertTrue(fixture.sink.serviceNotifications().isEmpty())
        } finally { fixture.close() }
    }

    @Test fun reconcileDropsStaleNotificationsAndRepostsLiveOnes() = runBlocking {
        val fixture = Fixture()
        try {
            // 上一代留下的、已无进程支撑的通知。
            fixture.sink.showService("debian", "ghost", "ghost", "http://127.0.0.1:1/")
            fixture.controller.reconcileNotifications()
            assertTrue(fixture.sink.serviceNotifications().isEmpty())

            // 运行中但通知被划掉/丢失（App 重启后进程仍存活）：对账补上。
            fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            fixture.sink.hideService("debian", "gateway")
            fixture.controller.reconcileNotifications()
            assertEquals(setOf("debian" to "gateway"), fixture.sink.serviceNotifications())
            assertEquals(3, fixture.sink.shows)
        } finally { fixture.close() }
    }

    @Test fun hidingAllServicesClearsEveryGatewayNotification() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            fixture.controller.hideNotifications()
            assertTrue(fixture.sink.serviceNotifications().isEmpty())
        } finally { fixture.close() }
    }

    @Test fun stopReclaimsAListenerTheServiceLeftBehind() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.controller.start("gateway", fixture.adapter, fixture.spec)
            // 模拟「tracer 先退、server 被 reparent 后继续监听」：停止时登记的实例没释放端口，
            // 只能靠停止后的端口兜底回收；停止本身不应报错。
            fixture.leavePortBehind = true
            fixture.controller.stop("gateway", fixture.spec)
            assertTrue("未触发端口兜底回收", fixture.reclaims >= 1)
            assertTrue("端口仍被残留监听者占用", isPortFree(fixture.port))
        } finally { fixture.close() }
    }

    private fun isPortFree(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250) }
        false
    }.getOrDefault(true)

    private class FakeServiceNotificationSink : ServiceNotificationSink {
        private val posted = linkedSetOf<Pair<String, String>>()
        var shows = 0
        var detail: String? = null

        override fun showService(distroId: String, toolId: String, toolName: String, detail: String) {
            shows++; this.detail = detail
            posted += distroId to toolId
        }

        override fun hideService(distroId: String, toolId: String) {
            posted -= distroId to toolId
        }

        override fun hideAllServices() = posted.clear()

        override fun serviceNotifications(): Set<Pair<String, String>> = posted.toSet()
    }

    private class Fixture {
        val port = ServerSocket(0).use { it.localPort }
        val processes = linkedMapOf<String, ManagedProcess>()
        val sink = FakeServiceNotificationSink()
        var launches = 0
        var sequence = 0
        var leavePortBehind = false
        var reclaims = 0
        private var leftover: ServerSocket? = null

        inner class Session(private val server: ServerSocket?) : LinuxSession {
            var alive = true
            override val isAlive get() = alive
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override suspend fun interrupt() = Unit
            override suspend fun close() = shutdown()
            fun boundServer(): ServerSocket? = server
            fun shutdown() { alive = false; runCatching { server?.close() } }
        }

        fun register(toolId: String, distroId: String): ManagedProcess =
            ManagedProcess("external-service:${++sequence}", 1, Session(null), toolId, distroId = distroId)
                .also { processes[it.id] = it }

        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getActiveDistroId" -> MutableStateFlow("debian")
                "getSupportsBackgroundInstanceStop" -> true
                "reclaimListeningPort" -> {
                    reclaims++
                    leftover?.close()
                    leftover = null
                    1
                }
                "startBackground" -> error("非认证服务通过 adapter.startService 启动")
                "listBackground" -> processes.values.toList()
                "stopBackgroundInstance" -> {
                    val owned = args[0] as ManagedProcess
                    if (processes[owned.id] !== owned) !owned.session.isAlive
                    else {
                        // 残留场景故意不关套接字（并由 fixture 持引用，避免被 GC 回收），
                        // 交由停止后的端口兜底回收。
                        if (leavePortBehind) leftover = (owned.session as Session).boundServer()
                        else (owned.session as Session).shutdown()
                        processes.remove(owned.id); true
                    }
                }
                "stopBackground" -> { processes[args[0]]?.let { (it.session as Session).shutdown() }; processes.remove(args[0]); true }
                "getBackgroundLogs" -> emptyList<String>()
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime

        val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "startService" -> {
                    // 真实网关在启动过程中绑定端口；就绪探测正是等这一步，并把自己登记进进程表。
                    val id = "tool-service:debian:gateway"
                    ManagedProcess(id, 1, Session(ServerSocket(port)), "gateway", distroId = "debian")
                        .also { processes[id] = it; launches++ }
                }
                else -> error("Unexpected adapter call: ${method.name}")
            }
        } as ToolRuntimeAdapter

        val controller = ToolServiceController(runtime, sink)
        val spec = LocalServiceSpec("gateway", port, startupTimeoutMs = 2_000, pollIntervalMs = 50, distroId = "debian", path = "/tianyan/")

        fun close() {
            leftover?.close()
            leftover = null
            processes.values.forEach { runCatching { (it.session as Session).shutdown() } }
            processes.clear()
        }
    }
}
