package top.tianyan.app.harness.environment

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.harness.*
import top.tianyan.app.harness.workflow.*
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

class BuildArtifactPipelineTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun unusedRuntime() = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
        _, method, _ -> error("Unexpected local runtime fallback ${method.name}")
    } as LinuxRuntime

    @Test fun stdoutClaimsCannotCreateArtifactsOrAnnounceOldFilesAsNew() = runBlocking {
        val root = temporary.newFolder()
        val file = File(root, "app/build/outputs/apk/debug/app.apk").apply { parentFile.mkdirs(); writeText("old") }
        val events = mutableListOf<WorkflowSignal>()
        val signals = WorkflowSignalBus()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { signals.events.collect { events += it } }
        val base = FakeWorld(ExecutionEnvironmentId("test-remote", "linux", "/workspace/p", "s"))
        val world = object : ExecutionEnvironment by base {
            override val files = WorkspaceFileAccess(root)
            override suspend fun execute(command: ShellCommand) = CommandResult(0,
                "generated /workspace/p/app/build/outputs/apk/debug/app.apk\ngenerated /fake/claimed.apk", "", 1)
        }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        try {
            val metadata = mutableMapOf<String, String>()
            LinuxCommandToolBackend(unusedRuntime(), HarnessPathResolver(), workflowSignals = signals, environments = registry)
                .execute(LinuxCommandRequest(HarnessTool.BASE, buildJsonObject { put("command", "gradle assembleDebug") }, "/workspace/p", "s", metadata))
            yield()
            val artifact = ToolFileArtifacts.decode(metadata.getValue(ToolFileArtifacts.METADATA_KEY))!!.files.single()
            assertEquals(FileArtifactOrigin.UNCHANGED_AFTER_COMMAND, artifact.origin)
            assertEquals(file.length(), artifact.bytes)
            assertTrue(events.isEmpty())
            assertFalse(metadata.values.single().contains("claimed.apk"))
        } finally { watcher.cancelAndJoin(); registry.closeSession("s") }
    }

    @Test fun realFileChangeCarriesItsObservedDigestIntoTheWorkflowSignal() = runBlocking {
        val root = temporary.newFolder()
        val file = File(root, "build/outputs/apk/app.apk").apply { parentFile.mkdirs(); writeText("before") }
        val signals = WorkflowSignalBus()
        val events = mutableListOf<WorkflowSignal>()
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { signals.events.collect { events += it } }
        val base = FakeWorld(ExecutionEnvironmentId("test", "linux", "/workspace/p", "s"))
        val world = object : ExecutionEnvironment by base {
            override val files = WorkspaceFileAccess(root)
            override suspend fun execute(command: ShellCommand): CommandResult { file.writeText("new binary version"); return CommandResult(0, "done", "", 1) }
        }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        try {
            val metadata = mutableMapOf<String, String>()
            LinuxCommandToolBackend(unusedRuntime(), HarnessPathResolver(), workflowSignals = signals, environments = registry)
                .execute(LinuxCommandRequest(HarnessTool.BASE, buildJsonObject { put("command", "gradle build") }, "/workspace/p", "s", metadata))
            yield()
            val artifact = ToolFileArtifacts.decode(metadata.getValue(ToolFileArtifacts.METADATA_KEY))!!.files.single()
            assertEquals(FileArtifactOrigin.CHANGED_AFTER_COMMAND, artifact.origin)
            val signal = events.filterIsInstance<WorkflowSignal.ApkGenerated>().single()
            assertEquals(artifact.sha256, signal.variables["APK_SHA256"])
            assertEquals(artifact.bytes.toString(), signal.variables["APK_BYTES"])
            assertTrue(signal.description.contains("尚未验证"))
        } finally { watcher.cancelAndJoin(); registry.closeSession("s") }
    }

    @Test fun missingBackendCapabilityMarksIncompleteInsteadOfUsingPhoneFiles() = runBlocking {
        val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "s"))
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> world })
        try {
            val metadata = mutableMapOf<String, String>()
            val result = LinuxCommandToolBackend(unusedRuntime(), HarnessPathResolver(), environments = registry)
                .execute(LinuxCommandRequest(HarnessTool.BASE, buildJsonObject { put("command", "gradle build") }, "/workspace/p", "s", metadata))
            assertTrue(result.first)
            val batch = ToolFileArtifacts.decode(metadata.getValue(ToolFileArtifacts.METADATA_KEY))!!
            assertFalse(batch.complete)
            assertTrue(batch.files.isEmpty())
        } finally { registry.closeSession("s") }
    }
}
