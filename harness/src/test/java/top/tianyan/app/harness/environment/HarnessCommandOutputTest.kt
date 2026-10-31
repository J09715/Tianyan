package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.*
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

class HarnessCommandOutputTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun controlRemovalCannotReconstructKnownSecretsInModelPresentation() = runBlocking {
        val secret = "opaque-environment-secret"
        val fixture = fixture(CommandResult(0, "opa\u001b[0mque-environment-secret\n", "", 1))
        fixture.world.secretValues += secret
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertFalse(result.output.contains(secret))
            assertFalse(result.output.contains('\u001b'))
            assertTrue(result.output.contains("op" + "*".repeat(secret.length - 4) + "et"))
            val saved = fixture.world.contents.values.single()
            assertFalse(top.tianyan.app.harness.effects.TerminalOutputCompressor.normalize(saved).contains(secret))
            assertFalse(saved.contains('\u001b'))
            val artifact = ToolOutputArtifact.decode(result.metadata[ToolOutputArtifact.METADATA_KEY])!!
            assertEquals(ToolOutputArtifact.capture(artifact.path, saved), artifact)
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun progressAndShortVerboseLogsArchiveRedactedEvidenceBeforeFolding() = runBlocking {
        val output = "\u001b[32msetup\u001b[0m\n" + "download\rcomplete\n" +
            "noise\n".repeat(200) + "API_KEY=labelled-secret-value\nfinal-error\n"
        val fixture = fixture(CommandResult(1, output, "", 10))
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertTrue(result.output.contains("setup"))
            assertTrue(result.output.contains("final-error"))
            assertTrue(result.output.contains("已折叠"))
            assertFalse(result.output.contains('\u001b'))
            assertFalse(result.output.contains("labelled-secret-value"))
            val saved = fixture.world.contents.values.single()
            assertTrue(saved.contains("complete"))
            assertFalse(saved.contains('\r'))
            assertFalse(saved.contains('\u001b'))
            assertFalse(saved.contains("labelled-secret-value"))
            val artifact = ToolOutputArtifact.decode(result.metadata[ToolOutputArtifact.METADATA_KEY])!!
            assertEquals(ToolOutputArtifact.capture(artifact.path, saved), artifact)
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun longCommandLogIsRedactedBeforeSpillAndRetainsMiddleEvidence() = runBlocking {
        val secret = "opaque-environment-secret"
        val stdout = "begin\n" + "line\n".repeat(450_000) + "middle-evidence\nAPI_KEY=labelled-secret-value\n$secret\n" + "line\n".repeat(30_000) + "final-error\n"
        val fixture = fixture(CommandResult(1, stdout, "", 10))
        fixture.world.secretValues += secret
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertFalse(result.success)
            assertNotNull(result.recovery)
            assertFalse(result.recovery!!.retrySameArguments)
            assertTrue(result.output.contains("final-error"))
            assertTrue(result.output.contains("完整输出已保存"))
            val saved = fixture.world.contents.filterKeys { it.startsWith(".tianyan-outputs/") }.values.single()
            assertTrue(saved.contains("middle-evidence"))
            assertTrue(saved.contains("final-error"))
            assertFalse(saved.contains(secret))
            assertFalse(saved.contains("labelled-secret-value"))
            val artifact = ToolOutputArtifact.decode(result.metadata[ToolOutputArtifact.METADATA_KEY])!!
            assertEquals(ToolOutputArtifact.capture(artifact.path, saved), artifact)
            assertTrue(fixture.host.listFiles()!!.isEmpty())
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun storageLimitedCaptureDoesNotClaimTheSpilledFileIsComplete() = runBlocking {
        val output = "line\n".repeat(20_000) + "[输出保存达到上限：中段缺失]\nfinal-error\n"
        val fixture = fixture(CommandResult(1, output, "", 10))
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertTrue(result.output.contains("可用输出已保存"))
            assertFalse(result.output.contains("完整输出已保存"))
            assertTrue(fixture.world.contents.values.single().contains("中段缺失"))
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun foldedLongSingleLineStillHasAReadableFullArtifact() = runBlocking {
        val output = "x".repeat(150_000) + "end-evidence"
        val fixture = fixture(CommandResult(0, output, "", 1))
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertTrue(result.success)
            assertTrue(result.output.contains("完整输出已保存"))
            assertTrue(fixture.world.contents.values.single().contains(output))
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun missingArtifactBackendDoesNotFallbackToPhoneStorage() = runBlocking {
        val fixture = fixture(CommandResult(0, "line\n".repeat(20_000), "", 1), artifacts = false)
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            assertTrue(result.success)
            assertFalse(result.output.contains("已保存"))
            assertTrue(fixture.world.contents.isEmpty())
            assertTrue(fixture.host.listFiles()!!.isEmpty())
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun normalizedArchiveKeepsOriginalIncompleteCaptureFlag() = runBlocking<Unit> {
        val fixture = fixture(CommandResult(0, "[输出保存达到上限：中段缺失]\rfinal-error\n", "", 1))
        try {
            val result = fixture.executor.execute(call(), "one", "/workspace/p")
            val saved = fixture.world.contents.values.single()
            val artifact = ToolOutputArtifact.decode(result.metadata[ToolOutputArtifact.METADATA_KEY])!!
            assertFalse(artifact.complete)
            assertTrue(result.output.contains("可用输出已保存"))
            assertEquals(ToolOutputArtifact.capture(artifact.path, saved).copy(complete = false), artifact)
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun buildAdmissionHintSurvivesRtkPreparationAndReportsQueueTime() = runBlocking {
        val fixture = fixture(CommandResult(0, "done", "", 7, resourceWaitMs = 100))
        try {
            val result = fixture.executor.execute(call("gradle assembleDebug"), "one", "/workspace/p")
            assertTrue(result.success)
            assertTrue(fixture.commands.single().memoryHeavy)
            assertTrue(fixture.commands.single().commandLine.contains("_tianyan_rtk_rewritten"))
            assertTrue(result.output.contains("7 ms"))
            assertTrue(result.output.contains("资源排队 100 ms"))
        } finally { fixture.registry.closeSession("one") }
    }

    private data class Fixture(val executor: ToolExecutor, val world: FakeWorld,
        val registry: SessionExecutionEnvironments, val host: java.io.File, val commands: List<ShellCommand>)

    @Test fun nativeSearchUsesBoundWorldAndNeverFallsBackToPhone() = runBlocking<Unit> {
        val fixture = fixture(CommandResult(0, "", "", 1))
        java.io.File(fixture.host, "local.txt").writeText("phone-only-needle")
        try {
            val result = fixture.executor.execute(ToolCall("search", 1, HarnessTool.READ,
                buildJsonObject { put("path", "/workspace"); put("query", "phone-only-needle") }), "one", "/workspace/p")
            assertFalse(result.success)
            assertTrue(result.output.contains("当前执行环境不支持"))
            assertFalse(result.output.contains("local.txt:"))
            assertTrue(fixture.commands.isEmpty())
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun nativeSearchOutputUsesNormalSecretRedactionWithoutCommandExecution() = runBlocking<Unit> {
        val fixture = fixture(CommandResult(0, "", "", 1), localFiles = true)
        val secret = "opaque-search-secret-value"
        java.io.File(fixture.host, "file.txt").writeText("needle $secret\n")
        fixture.world.secretValues += secret
        try {
            val result = fixture.executor.execute(ToolCall("search", 1, HarnessTool.READ,
                buildJsonObject { put("path", "/workspace"); put("query", "needle") }), "one", "/workspace/p")
            assertTrue(result.success)
            assertTrue(result.output.contains("file.txt:1:"))
            assertFalse(result.output.contains(secret))
            assertTrue(fixture.commands.isEmpty())
        } finally { fixture.registry.closeSession("one") }
    }

    @Test fun nativeSearchRedactsWholeLinesBeforeEitherExcerptBoundary() = runBlocking<Unit> {
        val fixture = fixture(CommandResult(0, "", "", 1), localFiles = true)
        val secret = "opaque-search-secret-value"
        java.io.File(fixture.host, "file.txt").writeText(
            "needle" + "x".repeat(385) + secret + "\n" + secret + "x".repeat(70) + "needle\n")
        fixture.world.secretValues += secret
        try {
            val result = fixture.executor.execute(ToolCall("search", 1, HarnessTool.READ,
                buildJsonObject { put("path", "/workspace"); put("query", "needle") }), "one", "/workspace/p")
            assertTrue(result.success)
            assertTrue(result.output.contains("file.txt:1:"))
            assertTrue(result.output.contains("file.txt:2:"))
            assertFalse(result.output.contains("opaque-se"))
            assertFalse(result.output.contains("ret-value"))
            assertFalse(result.output.contains(secret))
        } finally { fixture.registry.closeSession("one") }
    }

    private fun fixture(commandResult: CommandResult, artifacts: Boolean = true, localFiles: Boolean = false): Fixture {
        val host = temporary.newFolder()
        val hostFiles = WorkspaceFileAccess(host)
        val paths = HarnessPathResolver()
        val policy = ApprovalPolicyEngine(paths)
        val world = FakeWorld(ExecutionEnvironmentId("remote", "linux", "/workspace/p", "one"))
        val commands = mutableListOf<ShellCommand>()
        val environment = object : ExecutionEnvironment by world {
            override val files = if (localFiles) hostFiles else world.files
            override val artifacts: ExecutionArtifacts? = if (artifacts) world.artifacts else null
            override suspend fun execute(command: ShellCommand): CommandResult { commands += command; return commandResult }
        }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { _, _, _ -> environment })
        val executor = ToolExecutor(hostFiles, paths, policy, SecretRedactor(),
            HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            LinuxCommandToolBackend(unused<LinuxRuntime>(), paths, environments = registry),
            DownloadToolBackend(unused<FileDownloader>(), hostFiles, WorkspaceMutationSnapshots(), registry),
            ContextMemoryToolBackend(), AskUserToolBackend(policy, null), PromptAssetToolBackend(), HarnessServiceToolBackend(),
            CapabilityToolGateway(null) { _, _, _, _ -> error("host") }, environments = registry)
        return Fixture(executor, world, registry, host, commands)
    }
    private fun call(command: String = "fake build") = ToolCall("command", 1, HarnessTool.BASE, buildJsonObject { put("command", command) })
    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected local call ${method.name}") } as T
}
