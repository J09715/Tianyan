package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.core.network.DownloadEvent
import top.tianyan.app.core.network.DownloadRequest
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.runtime.FakeLinuxRuntime
import top.tianyan.app.runtime.shell.CommandResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.database.AgentSkillDao
import top.tianyan.app.core.database.AgentSkillEntity
import top.tianyan.app.core.database.AgentSkillRepository

class ToolExecutorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var workspaceFiles: WorkspaceFileAccess
    private lateinit var runtime: FakeLinuxRuntime
    private lateinit var executor: ToolExecutor
    private lateinit var downloader: RecordingDownloader

    private fun toolCall(tool: HarnessTool, args: JsonObject) =
        ToolCall(UUID.randomUUID().toString(), 0L, tool, args)

    @Before
    fun setUp() {
        workspaceRoot = temporaryFolder.newFolder("workspace")
        workspaceFiles = WorkspaceFileAccess(workspaceRoot)
        runtime = FakeLinuxRuntime()
        downloader = RecordingDownloader()
        val pathResolver = HarnessPathResolver()
        val approvalPolicyEngine = ApprovalPolicyEngine(pathResolver)
        executor = ToolExecutor(
            fileAccess = workspaceFiles,
            pathResolver = pathResolver,
            approvalPolicyEngine = approvalPolicyEngine,
            secretRedactor = SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(runtime, pathResolver),
            downloadToolBackend = DownloadToolBackend(downloader, workspaceFiles, WorkspaceMutationSnapshots()),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(approvalPolicyEngine),
            promptAssetToolBackend = PromptAssetToolBackend(),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
        )
    }

    @Test
    fun `read tool returns file content`() = runBlocking {
        executor.execute(toolCall(HarnessTool.WRITE, buildJsonObject {
            put("path", "hello.txt")
            put("content", "hi")
        }))
        val result = executor.execute(toolCall(HarnessTool.READ, buildJsonObject {
            put("path", "hello.txt")
        }))
        assertTrue(result.success)
        assertEquals("hi", result.output)
    }

    @Test
    fun `edit tool modifies file`() = runBlocking {
        executor.execute(toolCall(HarnessTool.WRITE, buildJsonObject {
            put("path", "a.txt")
            put("content", "one two")
        }))
        val result = executor.execute(toolCall(HarnessTool.EDIT, buildJsonObject {
            put("path", "a.txt")
            put("oldText", "two")
            put("newText", "2")
        }))
        assertTrue(result.success)
        val read = executor.execute(toolCall(HarnessTool.READ, buildJsonObject {
            put("path", "a.txt")
        }))
        assertEquals("one 2", read.output)
    }

    @Test
    fun `read tool failure is structured not thrown`() = runBlocking {
        val result = executor.execute(toolCall(HarnessTool.READ, buildJsonObject {
            put("path", "/etc/passwd")
        }))
        assertFalse(result.success)
        assertTrue(result.output.isNotEmpty())
    }

    @Test
    fun `missing argument produces structured failure`() = runBlocking {
        val result = executor.execute(toolCall(HarnessTool.READ, buildJsonObject {}))
        assertFalse(result.success)
        assertTrue(result.output.contains("缺少参数"))
    }

    @Test
    fun `base tool runs command through runtime`() = runBlocking {
        runtime.commandResults["uname -m"] = CommandResult(0, "aarch64", "", 5)
        val result = executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "uname -m")
        }))
        assertTrue(result.success)
        assertTrue(result.output.contains("aarch64"))
        assertTrue(runtime.executedCommands.contains("uname -m"))
    }

    @Test
    fun `base tool reports non-zero exit`() = runBlocking {
        runtime.commandResults["false"] = CommandResult(1, "", "boom", 3)
        val result = executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "false")
        }))
        assertFalse(result.success)
        assertTrue(result.output.contains("exit 1"))
        assertTrue(result.output.contains("boom"))
    }

    @Test
    fun `base tool passes working directory`() = runBlocking {
        executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "pwd")
            put("cwd", "/workspace/proj")
        }))
        assertEquals("/workspace/proj", runtime.executedShellCommands.single().workingDirectory)
    }

    @Test
    fun `base tool uses configurable default and accepts bounded override`() = runBlocking {
        executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "pwd")
        }))
        assertEquals(600_000L, runtime.executedShellCommands.last().timeoutMs)

        executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "pwd")
            put("timeout_seconds", 900)
        }))
        assertEquals(900_000L, runtime.executedShellCommands.last().timeoutMs)

        val invalid = executor.execute(toolCall(HarnessTool.BASE, buildJsonObject {
            put("command", "pwd")
            put("timeout_seconds", 901)
        }))
        assertFalse(invalid.success)
        assertTrue(invalid.output, invalid.output.contains("1-900"))
        assertEquals(2, runtime.executedShellCommands.size)
    }

    @Test
    fun `process tool manages namespaced background command`() = runBlocking {
        val started = executor.execute(toolCall(HarnessTool.PROCESS, buildJsonObject {
            put("action", "start")
            put("id", "dev-server")
            put("command", "python -m http.server 8080")
            put("cwd", "/workspace/project")
        }))
        assertTrue(started.success)
        assertTrue(runtime.backgroundProcesses.containsKey("agent-process:dev-server"))

        runtime.backgroundLogs["agent-process:dev-server"] = listOf("ready", "request")
        val logs = executor.execute(toolCall(HarnessTool.PROCESS, buildJsonObject {
            put("action", "logs")
            put("id", "dev-server")
            put("tail_lines", 1)
        }))
        assertEquals("request", logs.output)

        val listed = executor.execute(toolCall(HarnessTool.PROCESS, buildJsonObject {
            put("action", "list")
        }))
        assertTrue(listed.output.contains("dev-server · 运行中"))

        val stopped = executor.execute(toolCall(HarnessTool.PROCESS, buildJsonObject {
            put("action", "stop")
            put("id", "dev-server")
        }))
        assertTrue(stopped.success)
        assertTrue(runtime.backgroundProcesses.isEmpty())
    }

    @Test
    fun `download tool uses built in downloader and workspace destination`() = runBlocking {
        val result = executor.execute(toolCall(HarnessTool.DOWNLOAD, buildJsonObject {
            put("url", "https://example.com/archive.tar.gz")
            put("destination", "dist/archive.tar.gz")
            put("max_attempts", 2)
        }))

        assertTrue(result.success)
        assertTrue(result.output.contains("dist/archive.tar.gz"))
        assertTrue(result.output.contains("断点续传"))
        assertEquals("https://example.com/archive.tar.gz", downloader.request.url)
        assertEquals("payload", File(downloader.request.destination.absolutePath).readText())
    }

    @Test
    fun `download tool rejects workspace escape`() = runBlocking {
        val result = executor.execute(toolCall(HarnessTool.DOWNLOAD, buildJsonObject {
            put("url", "https://example.com/archive.tar.gz")
            put("destination", "../outside.tar.gz")
        }))

        assertFalse(result.success)
        assertTrue(result.output.contains("路径越界"))
    }

    @Test
    fun `download tool reports progress while download is active`() = runBlocking {
        val progress = mutableListOf<String>()
        executor.execute(
            toolCall(HarnessTool.DOWNLOAD, buildJsonObject {
                put("url", "https://example.com/archive.tar.gz")
                put("destination", "dist/archive.tar.gz")
            }),
            progressReporter = { progress += it },
        )

        assertTrue(progress.any { it.startsWith("下载中：") })
        assertTrue(progress.any { it.contains("100%") })
    }

    @Test
    fun `read tool bridges image files as multimodal payload`() = runBlocking {
        workspaceRoot.resolve("chart.png").writeBytes(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )

        val result = executor.execute(toolCall(HarnessTool.READ, buildJsonObject { put("path", "chart.png") }))

        assertTrue(result.success)
        val payload = result.imageDataUrl
        assertTrue(payload != null && payload.startsWith("data:image/png;base64,"))
        val artifacts = requireNotNull(ToolFileArtifacts.decode(result.metadata[ToolFileArtifacts.METADATA_KEY]))
        assertTrue(artifacts.complete)
        val artifact = artifacts.files.single()
        assertEquals("chart.png", artifact.path)
        assertEquals(FileArtifactKind.IMAGE, artifact.kind)
        assertEquals(FileArtifactOrigin.IMAGE_READ, artifact.origin)
        assertEquals(8L, artifact.bytes)
        assertEquals(
            java.security.MessageDigest.getInstance("SHA-256").digest(workspaceRoot.resolve("chart.png").readBytes())
                .joinToString("") { "%02x".format(it) },
            artifact.sha256,
        )
        assertTrue(artifact.observedAt > 0L)
    }

    @Test
    fun `load skill reads sub resource and strips root frontmatter`() = runBlocking {
        val skillDir = temporaryFolder.newFolder("skill")
        File(skillDir, "references").mkdirs()
        File(skillDir, "references/spec.md").writeText("SPEC-CONTENT")
        val skillExecutor = executorWithSkill(skillDir, systemPrompt = "---\nname: demo\n---\nBODY")

        val sub = skillExecutor.execute(
            toolCall(
                HarnessTool.LOAD_SKILL,
                buildJsonObject {
                    put("name", "demo")
                    put("path", "references/spec.md")
                },
            ),
        )
        assertTrue(sub.success)
        assertTrue(sub.output.contains("SPEC-CONTENT"))

        val root = skillExecutor.execute(toolCall(HarnessTool.LOAD_SKILL, buildJsonObject { put("name", "demo") }))
        assertTrue(root.success)
        assertTrue(root.output.contains("BODY"))
        assertFalse(root.output.contains("name: demo"))
    }

    @Test
    fun `load skill rejects path traversal`() = runBlocking {
        val skillDir = temporaryFolder.newFolder("skill-traversal")
        val skillExecutor = executorWithSkill(skillDir, systemPrompt = "BODY")

        val result = skillExecutor.execute(
            toolCall(
                HarnessTool.LOAD_SKILL,
                buildJsonObject {
                    put("name", "demo")
                    put("path", "../../etc/passwd")
                },
            ),
        )

        assertFalse(result.success)
    }

    private fun executorWithSkill(skillDir: File, systemPrompt: String): ToolExecutor {
        val dao = FakeSkillDao(
            listOf(
                AgentSkillEntity(
                    id = "s1",
                    name = "demo",
                    description = "demo skill",
                    systemPrompt = systemPrompt,
                    triggerCommand = null,
                    iconName = "",
                    isEnabled = true,
                    isBuiltin = false,
                    isImmutable = false,
                    category = "自定义",
                    resourcePath = skillDir.absolutePath,
                ),
            ),
        )
        return ToolExecutor(
            fileAccess = workspaceFiles,
            pathResolver = HarnessPathResolver(),
            approvalPolicyEngine = ApprovalPolicyEngine(HarnessPathResolver()),
            secretRedactor = SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(runtime, HarnessPathResolver()),
            downloadToolBackend = DownloadToolBackend(downloader, workspaceFiles, WorkspaceMutationSnapshots()),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(HarnessPathResolver())),
            promptAssetToolBackend = PromptAssetToolBackend(skillRepository = AgentSkillRepository(dao)),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
        )
    }

    private class FakeSkillDao(rows: List<AgentSkillEntity>) : AgentSkillDao {
        private val flow = MutableStateFlow(rows)
        override fun observeAll() = flow
        override suspend fun insertAll(skills: List<AgentSkillEntity>) = Unit
        override suspend fun upsert(skill: AgentSkillEntity) = Unit
        override suspend fun setEnabled(id: String, enabled: Boolean) = Unit
        override suspend fun deleteCustom(id: String) = Unit
    }
    private class RecordingDownloader : FileDownloader {
        lateinit var request: DownloadRequest

        override fun download(request: DownloadRequest): Flow<DownloadEvent> = flow {
            this@RecordingDownloader.request = request
            request.destination.parentFile?.mkdirs()
            request.destination.writeText("payload")
            emit(DownloadEvent.Started)
            emit(DownloadEvent.Progress(7L, 7L))
            emit(DownloadEvent.Completed(request.destination))
        }
    }
}
