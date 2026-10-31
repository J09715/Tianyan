package top.tianyan.app.runtime.service

import java.lang.reflect.Proxy
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.*

class ProotToolServiceSourceTest {
    private val html = "<!doctype html><p>工具自持服务</p>".toByteArray()
    private val script = "console.log('service')".toByteArray()

    private fun descriptor(
        port: Int,
        basePath: String = "/",
        owner: String = "sample-tool",
        command: String = "sample-tool --serve",
        environment: Map<String, String> = mapOf("PATH" to "/usr/bin"),
    ) = ProotServiceDescriptor(port, basePath, command, environment, owner, "ubuntu")

    @Test fun remoteNotReadyAndUnsupportedBackendsRejectBeforeAllocatingProcess() {
        MockWebServer().use { server ->
            server.start()
            val fake = Fake()
            fake.local = false
            assertTrue(runCatching { ProotToolServiceSource(fake.runtime, descriptor(server.port), { fake.local }) }.isFailure)
            fake.local = true; fake.state.value = RuntimeState.NotInitialized
            assertTrue(runCatching { ProotToolServiceSource(fake.runtime, descriptor(server.port), { fake.local }) }.isFailure)
            fake.state.value = RuntimeState.Ready; fake.supported = false
            assertTrue(runCatching { ProotToolServiceSource(fake.runtime, descriptor(server.port), { fake.local }) }.isFailure)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun descriptorRejectsForeignPortsPathsCommandsEnvironmentsAndOwners() {
        assertThrows(IllegalArgumentException::class.java) { descriptor(0) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(70000) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, "/ui") }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, "/ui\u0000/") }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, command = "   ") }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, environment = mapOf("BAD KEY" to "v")) }
        assertThrows(IllegalArgumentException::class.java) { descriptor(8000, owner = "") }
        assertThrows(IllegalArgumentException::class.java) { ProotServiceDescriptor(8000, "/", "cmd", emptyMap(), "sample-tool", "") }
    }

    @Test fun readinessProbePinsEntryThenServesPathsAndStopsOnlyItsInstance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(script)))
            server.start()
            val fake = Fake()
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port, "/ui/"), { true })
            assertTrue(fake.processes.isEmpty())
            assertArrayEquals(html, source.snapshot().getValue("index.html"))
            assertEquals("ubuntu", fake.requestedDistro)
            assertEquals("sample-tool --serve", fake.command!!.commandLine)
            assertEquals("/usr/bin", fake.command!!.environment["PATH"])
            assertEquals("/ui/index.html", server.takeRequest().path)
            assertArrayEquals(script, source.resolve("plugin.js"))
            assertTrue(source.isAvailable)
            assertTrue(source.close())
            assertFalse(source.isAvailable)
            assertNull(source.resolve("plugin.js"))
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun aServedPathThatChangesAfterPinningClosesTheWholeSource() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write(script)))
            server.enqueue(MockResponse().setBody(okio.Buffer().write("replacement".toByteArray())))
            server.start()
            val fake = Fake()
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port), { true })
            assertArrayEquals(html, source.snapshot().getValue("index.html"))
            assertArrayEquals(script, source.resolve("plugin.js"))
            assertNull(source.resolve("plugin.js"))
            assertFalse(source.isAvailable)
            assertTrue(source.close())
        }
    }

    @Test fun processExitDuringReadinessFailsInsteadOfPublishingADocument() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(); fake.deadAfterStart = true
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port), { true })
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertFalse(source.isAvailable)
            assertTrue(source.close())
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun readinessTimeoutClosesTheStartedInstance() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val fake = Fake()
            val port = server.port
            server.shutdown()
            val source = ProotToolServiceSource(
                fake.runtime, descriptor(port), { true },
                startupTimeoutMillis = 300, pollIntervalMillis = 50,
            )
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertFalse(source.isAvailable)
            assertTrue(source.close())
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun environmentSwitchDuringStartupInvalidatesTheSourceAndStopsOnlyItsInstance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.start(); val fake = Fake()
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port), { true })
            source.snapshot()
            val unrelated = ManagedProcess("other-instance", 2, fake.Session(), "other-tool")
            fake.processes[unrelated.id] = unrelated
            fake.distro.value = "debian"
            assertFalse(source.isAvailable)
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertTrue(source.close())
            assertSame(unrelated, fake.processes.values.single())
        }
    }

    @Test fun failedStopRetainsInstanceAndRetryDoesNotTouchOtherProcesses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
            server.start(); val fake = Fake()
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port), { true })
            source.snapshot()
            val unrelated = ManagedProcess("unrelated", 2, fake.Session())
            fake.processes[unrelated.id] = unrelated
            fake.stopFailures = 1
            assertFalse(source.close())
            assertFalse(source.isAvailable)
            assertEquals(2, fake.processes.size)
            assertTrue(source.close())
            assertSame(unrelated, fake.processes.values.single())
            assertTrue(unrelated.session.isAlive)
        }
    }

    @Test fun closingPreparedLeasePreventsAnyLaterLaunch() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake()
            val source = ProotToolServiceSource(fake.runtime, descriptor(server.port), { true })
            assertTrue(source.close())
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun staleOrphanListenerIsReclaimedBeforeThePageStartsItsOwnService() = runBlocking {
        val stale = ServerSocket(0)
        val port = stale.localPort
        val server = MockWebServer()
        val fake = Fake()
        fake.reclaim = { stale.close(); 1 }
        fake.onStart = { server.start(InetAddress.getByName("127.0.0.1"), port) }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(html)))
        val source = ProotToolServiceSource(fake.runtime, descriptor(port, "/ui/"), { true })
        assertArrayEquals(html, source.snapshot().getValue("index.html"))
        assertTrue(source.close())
        server.shutdown()
    }

    private class Fake {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        val distro = MutableStateFlow("ubuntu")
        var local = true
        var supported = true
        var stopFailures = 0
        var deadAfterStart = false
        var reclaim: () -> Int = { 0 }
        var onStart: () -> Unit = {}
        var command: ShellCommand? = null
        var requestedDistro: String? = null
        val processes = linkedMapOf<String, ManagedProcess>()

        inner class Session : LinuxSession {
            var alive = true
            override val isAlive get() = alive
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) = Unit
            override suspend fun close() { alive = false }
            override suspend fun resize(columns: Int, rows: Int) = Unit
            override suspend fun interrupt() = Unit
        }

        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getState" -> state
                "getActiveDistroId" -> distro
                "getSupportsBackgroundInstanceStop" -> supported
                "startBackground" -> {
                    command = args[1] as ShellCommand
                    requestedDistro = args[4] as String
                    onStart()
                    val id = args[0] as String
                    ManagedProcess(id, 1, Session(), args[2] as String?, type = args[3] as ProcessType).also {
                        processes[id] = it
                        if (deadAfterStart) (it.session as Session).alive = false
                    }
                }
                "listBackground" -> processes.values.toList()
                "reclaimListeningPort" -> reclaim()
                "stopBackgroundInstance" -> {
                    val owned = args[0] as ManagedProcess
                    if (stopFailures > 0) { stopFailures--; false }
                    else if (processes[owned.id] !== owned) !owned.session.isAlive
                    else { (owned.session as Session).alive = false; processes.remove(owned.id); true }
                }
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime
    }
}
