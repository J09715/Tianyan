package top.tianyan.app.runtime

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import top.tianyan.app.core.network.DownloadEvent
import top.tianyan.app.core.network.DownloadRequest
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.runtime.rootfs.RootfsValidator
import top.tianyan.app.runtime.service.LocalServiceHandle
import top.tianyan.app.runtime.service.LocalServiceLauncher
import top.tianyan.app.runtime.service.LocalServiceSpec
import top.tianyan.app.runtime.shell.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalLlmContextTest {
    @Test fun configured128kReachesTheServerCommandAndUnchangedStartReusesProcess() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.manager.start("model.gguf", 128_000)
            assertTrue(fixture.commands.single().commandLine.contains("--ctx-size 128000 "))
            fixture.manager.start("model.gguf", 128_000)
            assertEquals(1, fixture.commands.size)
        } finally { fixture.manager.stop() }
    }

    @Test fun changedContextReplacesTheRunningProcessInsteadOfIgnoringTheNewLimit() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.manager.start("model.gguf", 4096)
            val first = fixture.processes.single()
            fixture.manager.start("model.gguf", 128_000)
            assertFalse(first.session.isAlive)
            assertTrue(fixture.processes.last().session.isAlive)
            assertTrue(fixture.commands.last().commandLine.contains("--ctx-size 128000 "))
        } finally { fixture.manager.stop() }
    }

    @Test fun invalidLimitDoesNotStopTheRunningServiceOrAllocateAnotherProcess() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.manager.start("model.gguf", 32_768)
            for (invalid in listOf(0, -1)) {
                try {
                    fixture.manager.start("model.gguf", invalid)
                    fail("Invalid context must be rejected")
                } catch (_: IllegalArgumentException) { }
            }
            assertEquals(1, fixture.commands.size)
            assertTrue(fixture.processes.single().session.isAlive)
        } finally { fixture.manager.stop() }
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>().apply {
            applicationInfo.nativeLibraryDir = File(cacheDir, "test-native-libs").absolutePath
        }
        private val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
        val commands = mutableListOf<ShellCommand>()
        val processes = mutableListOf<ManagedProcess>()
        private val distro = MutableStateFlow("ubuntu")
        private val runtime = Proxy.newProxyInstance(
            LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getActiveDistroId" -> distro
                "execute" -> CommandResult(0, "", "", 0)
                "startBackground" -> {
                    commands += args[1] as ShellCommand
                    ManagedProcess(args[0] as String, 0, Session()).also { processes += it }
                }
                else -> error("Unexpected runtime call: ${method.name}")
            }
        } as LinuxRuntime
        private val launcher = object : LocalServiceLauncher {
            private var handle: LocalServiceHandle? = null
            override suspend fun start(
                spec: LocalServiceSpec, startProcess: suspend () -> ManagedProcess,
            ) = LocalServiceHandle(spec, startProcess()).also { handle = it }
            override suspend fun stop(serviceId: String): Boolean {
                val owned = handle ?: return false
                owned.process.session.close()
                handle = null
                return true
            }
            override suspend fun stopAll() { stop("llama-cpp") }
        }
        val manager: LocalLlmManager

        init {
            ReflectionHelpers.setStaticField(Build::class.java, "SUPPORTED_ABIS", arrayOf("arm64-v8a"))
            File(paths.tianyanDataDir("ubuntu"), "llama-cpp/models/model.gguf").apply {
                parentFile!!.mkdirs()
                writeBytes("GGUF".toByteArray() + ByteArray(20))
            }
            manager = LocalLlmManager(context, paths, runtime, object : FileDownloader {
                override fun download(request: DownloadRequest) = emptyFlow<DownloadEvent>()
            }, launcher)
        }
    }

    private class Session : LinuxSession {
        private var alive = true
        override val isAlive get() = alive
        override val output = emptyFlow<TerminalOutput>()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
        override suspend fun close() { alive = false }
    }
}
