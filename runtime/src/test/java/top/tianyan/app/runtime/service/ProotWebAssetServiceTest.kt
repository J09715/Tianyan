package top.tianyan.app.runtime.service

import java.io.ByteArrayInputStream
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.*

class ProotWebAssetServiceTest {
    private val html = "<p>Owned PRoot service</p>".toByteArray()

    @Test fun externalPackageOwnerSurvivesStartupFailureAndCleanupRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server); fake.failAfterRegistration = true
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local }, ownerPluginId = "example.web")
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertEquals("example.web", fake.processes.values.single().toolId)
            assertEquals("example.web", fake.command?.pluginIdentity?.pluginId)
            assertEquals("host-static", fake.command?.pluginIdentity?.version)
            fake.stopFailures = 1
            assertFalse(source.close())
            assertTrue(source.close())
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun packageRevocationAfterLoadingInvalidatesSourceAndReleasesOnlyItsInstance() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html))); server.start(); val fake = Fake(server)
            var current = true
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local && current }, ownerPluginId = "example.web")
            source.snapshot()
            val unrelated = ManagedProcess("other-package", 2, fake.Session(), "example.other")
            fake.processes[unrelated.id] = unrelated
            current = false
            assertFalse(source.isAvailable)
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertTrue(source.close())
            assertSame(unrelated, fake.processes.values.single())
        }
    }

    private class Fake(val server: MockWebServer) {
        val state = MutableStateFlow<RuntimeState>(RuntimeState.Ready)
        val distro = MutableStateFlow("ubuntu")
        var local = true
        var supported = true
        var stops = 0
        var stopFailures = 0
        var logReady = true
        var failAfterRegistration = false
        var afterWrite: suspend () -> Unit = {}
        var input: JsonObject? = null
        var inputSize = 0
        var command: ShellCommand? = null
        var requestedDistro: String? = null
        val processes = linkedMapOf<String, ManagedProcess>()
        var logs = emptyList<String>()

        inner class Session : LinuxSession {
            var alive = true
            override val isAlive get() = alive
            override val output = emptyFlow<TerminalOutput>()
            override suspend fun write(data: ByteArray) {
                inputSize = data.size
                val fields = data.toString(Charsets.US_ASCII).trimEnd('\n').split('\t')
                val compressed = Base64.getDecoder().decode(fields[2])
                val assets = Json.parseToJsonElement(InflaterInputStream(ByteArrayInputStream(compressed)).readBytes().toString(Charsets.UTF_8))
                input = buildJsonObject {
                    put("instance", fields[0]); put("prefix", "/${fields[0]}/"); put("authorization", "Bearer ${fields[1]}"); put("assets", assets)
                }
                afterWrite()
                if (logReady) {
                    val instance = input!!.getValue("instance").jsonPrimitive.content
                    // Exercise a marker split across process-output chunks.
                    logs = listOf("[starting]", "TIANYAN_WEB_RE", "ADY_$instance:${server.port}:END")
                }
            }
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
                    val id = args[0] as String
                    ManagedProcess(id, 1, Session(), args[2] as String?, type = args[3] as ProcessType).also {
                        processes[id] = it
                        check(!failAfterRegistration) { "simulated startup failure after registration" }
                    }
                }
                "getBackgroundLogs" -> logs
                "stopBackgroundInstance" -> {
                    stops++
                    val owned = args[0] as ManagedProcess
                    if (stopFailures > 0) { stopFailures--; false }
                    else if (processes[owned.id] !== owned) !owned.session.isAlive
                    else { (owned.session as Session).alive = false; processes.remove(owned.id); true }
                }
                "listBackground" -> processes.values.toList()
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime
    }

    @Test fun pinnedProcessReceivesOnlyPipeConfigurationAndServesVerifiedAssets() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html))); server.start()
            val fake = Fake(server)
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            assertTrue(fake.processes.isEmpty())
            assertArrayEquals(html, source.snapshot().getValue("index.html"))
            assertEquals("ubuntu", fake.requestedDistro)
            assertTrue(fake.command!!.environment.isEmpty())
            assertTrue(fake.command!!.commandLine.contains("env -i"))
            val auth = fake.input!!.getValue("authorization").jsonPrimitive.content
            assertFalse(fake.command!!.commandLine.contains(auth))
            assertFalse(fake.logs.joinToString().contains(auth))
            assertTrue(fake.inputSize <= 4096)
            val request = server.takeRequest()
            assertEquals(auth, request.getHeader("Authorization"))
            assertEquals(fake.input!!.getValue("prefix").jsonPrimitive.content + "index.html", request.path)
            assertTrue(source.close())
            assertFalse(source.isAvailable)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun remoteNotReadyAndUnsupportedBackendsRejectBeforeAllocatingProcess() {
        MockWebServer().use { server ->
            server.start()
            val fake = Fake(server)
            fake.local = false
            assertTrue(runCatching { ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local }) }.isFailure)
            fake.local = true; fake.state.value = RuntimeState.NotInitialized
            assertTrue(runCatching { ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local }) }.isFailure)
            fake.state.value = RuntimeState.Ready; fake.supported = false
            assertTrue(runCatching { ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local }) }.isFailure)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun closingPreparedLeasePreventsAnyLaterLaunch() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server)
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            assertTrue(source.close())
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun failedStartRetainsRegisteredHandleForPageCleanupAndRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server); fake.failAfterRegistration = true
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            assertTrue(runCatching { source.snapshot() }.isFailure)
            val retained = fake.processes.values.single()
            fake.stopFailures = 1
            assertFalse(source.close())
            assertSame(retained, fake.processes.values.single())
            assertTrue(source.close())
            assertTrue(fake.processes.isEmpty())
            assertFalse(retained.session.isAlive)
        }
    }

    @Test fun environmentSwitchDuringStartupCannotRedirectOrPublishSource() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server)
            fake.afterWrite = { fake.distro.value = "debian" }
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            assertTrue(runCatching { source.snapshot() }.isFailure)
            assertEquals("ubuntu", fake.requestedDistro)
            assertEquals(0, server.requestCount)
            assertTrue(source.close())
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun timeoutAndMissingPythonProcessAreClosedByTheOwner() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server); fake.logReady = false
            val timeout = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local }, startupTimeoutMillis = 100)
            assertTrue(runCatching { timeout.snapshot() }.isFailure)
            assertFalse(timeout.isAvailable)
            assertTrue(timeout.close())
            fake.afterWrite = { (fake.processes.values.single().session as Fake.Session).alive = false }
            val missing = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            assertTrue(runCatching { missing.snapshot() }.isFailure)
            assertTrue(missing.close())
        }
    }

    @Test fun failedStopRetainsInstanceAndRetryDoesNotTouchOtherProcesses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(okio.Buffer().write(html))); server.start(); val fake = Fake(server)
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
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

    @Test fun cancellationAfterProcessAllocationStillAllowsConfirmedInstanceCleanup() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server)
            val entered = CompletableDeferred<Unit>(); val waiting = CompletableDeferred<Unit>()
            fake.afterWrite = { entered.complete(Unit); waiting.await() }
            val source = ProotWebAssetService(fake.runtime, mapOf("index.html" to html), { fake.local })
            val pending = async { try { source.snapshot() } finally { source.close() } }
            entered.await(); pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertTrue(fake.processes.isEmpty())
        }
    }

    @Test fun oversizedBootstrapRejectsBeforeAnyProcessOrPipeAllocation() {
        MockWebServer().use { server ->
            server.start(); val fake = Fake(server)
            val random = ByteArray(8192).also { java.util.Random(123).nextBytes(it) }
            assertTrue(runCatching { ProotWebAssetService(fake.runtime, mapOf("index.html" to random), { fake.local }) }.isFailure)
            assertTrue(fake.processes.isEmpty())
        }
    }
}
