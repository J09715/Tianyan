package top.tianyan.app.core.tools

import java.lang.reflect.Proxy
import java.net.ServerSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.shell.*

class ToolServiceCancellationTest {
    @Test fun occupiedPortCannotBeTreatedAsAReadyUnownedService() = runBlocking {
        ServerSocket(0).use { occupied ->
            var launches = 0
            val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
                error("Unexpected runtime call: ${method.name}")
            } as LinuxRuntime
            val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, _, _ ->
                launches++; error("must not launch")
            } as ToolRuntimeAdapter
            try {
                ToolServiceController(runtime).start("gateway", adapter, LocalServiceSpec("gateway", occupied.localPort))
                fail("occupied port accepted")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("端口已被占用"))
            }
            assertEquals(0, launches)
        }
    }

    @Test fun occupiedPortWithoutAReclaimableOrphanStillRefusesToStartAfterOneReclaimAttempt() = runBlocking {
        ServerSocket(0).use { occupied ->
            var reclaims = 0
            var launches = 0
            val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
                when (method.name) {
                    "reclaimListeningPort" -> { reclaims++; 0 }
                    else -> error("Unexpected runtime call: ${method.name}")
                }
            } as LinuxRuntime
            val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, _, _ ->
                launches++; error("must not launch")
            } as ToolRuntimeAdapter
            try {
                ToolServiceController(runtime).start("gateway", adapter, LocalServiceSpec("gateway", occupied.localPort, pollIntervalMs = 50))
                fail("occupied port accepted")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("端口已被占用"))
            }
            assertEquals(0, launches)
            assertEquals(1, reclaims)
        }
    }

    @Test fun orphanReclaimFreesThePortSoStartIsNoLongerRejectedAsOccupied() = runBlocking {
        val occupied = ServerSocket(0)
        val port = occupied.localPort
        var reclaims = 0
        var alive = true
        val session = object : LinuxSession {
            override val isAlive get() = alive
            override val pid: Long? = 123L
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun interrupt() = Unit
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override suspend fun close() { alive = false }
        }
        val process = ManagedProcess("gateway-service", 1L, session, "gateway")
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "reclaimListeningPort" -> { reclaims++; occupied.close(); 1 }
                "getSupportsBackgroundInstanceStop" -> true
                "stopBackgroundInstance" -> { alive = false; true }
                "stopBackground" -> { alive = false; true }
                "getBackgroundLogs" -> emptyList<String>()
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime
        val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "startService" -> process
                else -> error("Unexpected adapter call: ${method.name}")
            }
        } as ToolRuntimeAdapter
        try {
            ToolServiceController(runtime).start("gateway", adapter, LocalServiceSpec("gateway", port, startupTimeoutMs = 400, pollIntervalMs = 50))
            fail("expected readiness timeout")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("未在"))
            assertFalse(expected.message.orEmpty().contains("端口已被占用"))
        }
        assertEquals(1, reclaims)
        assertFalse(alive)
    }

    @Test fun anotherDistributionProcessIsNeitherHealthyNorStopped() = runBlocking {
        val session = Proxy.newProxyInstance(LinuxSession::class.java.classLoader, arrayOf(LinuxSession::class.java)) { _, method, _ ->
            when (method.name) { "isAlive" -> true; "getPid" -> 1L; else -> error("Unexpected ${method.name}") }
        } as LinuxSession
        val process = ManagedProcess("tool-service:ubuntu:gateway", 1L, session, "gateway", distroId = "ubuntu")
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            when (method.name) { "listBackground" -> listOf(process); else -> error("Unexpected ${method.name}") }
        } as LinuxRuntime
        val port = ServerSocket(0).use { it.localPort }
        val spec = LocalServiceSpec("gateway", port, distroId = "debian")
        val controller = ToolServiceController(runtime)
        assertFalse(controller.isRunning("gateway", spec))
        controller.stop("gateway", spec)
        assertTrue(session.isAlive)
    }

    @Test fun cancellingReadinessReclaimsTheExactUnreadyServiceInstance() = runBlocking {
        var alive = true
        val session = object : LinuxSession {
            override val isAlive get() = alive
            override val pid: Long? = 123L
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun interrupt() = Unit
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override suspend fun close() { alive = false }
        }
        val process = ManagedProcess("gateway-service", 1L, session, "gateway")
        var stopped: ManagedProcess? = null
        val started = CompletableDeferred<Unit>()
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getSupportsBackgroundInstanceStop" -> true
                "stopBackgroundInstance" -> { stopped = args[0] as ManagedProcess; alive = false; true }
                else -> error("Unexpected call: ${method.name}")
            }
        } as LinuxRuntime
        val adapter = Proxy.newProxyInstance(ToolRuntimeAdapter::class.java.classLoader, arrayOf(ToolRuntimeAdapter::class.java)) { _, method, _ ->
            when (method.name) {
                "startService" -> { started.complete(Unit); process }
                else -> error("Unexpected call: ${method.name}")
            }
        } as ToolRuntimeAdapter
        val port = ServerSocket(0).use { it.localPort }
        val controller = ToolServiceController(runtime)
        val job = launch { controller.start("gateway", adapter, LocalServiceSpec("gateway", port)) }
        started.await()
        job.cancelAndJoin()
        assertSame(process, stopped)
        assertFalse(alive)
    }
}
