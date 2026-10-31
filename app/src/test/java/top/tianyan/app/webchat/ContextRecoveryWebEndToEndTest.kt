package top.tianyan.app.webchat

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.core.qualifier.named
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.di.tianyanModule
import top.tianyan.app.harness.*
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.core.LlmApi
import top.tianyan.app.harness.plugins.*
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.plugin.api.*
import top.tianyan.app.runtime.FakeLinuxRuntime
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.webchat.WebChatBridgeServer
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

/** Real REST/SSE, shared HarnessLoop, persistence, compaction, tools and approvals; no model network. */
@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class ContextRecoveryWebEndToEndTest {
    @Test fun recoveredFactWaitsForApprovalAndCompletesOnWebExactlyOnce() = scenario(true)
    @Test fun deniedActionIsNeverExecutedAndTheAnswerStillReachesWeb() = scenario(false)
    @Test fun expiredArtifactCompletesWithMissingEvidenceWithoutReplayingTheOldAction() = scenario(false, "expired")
    @Test fun failedArtifactWriteCompletesWithMissingEvidenceWithoutInventingTheFact() = scenario(false, "write-failed")
    @Test fun verifiedArtifactPagesRecoverFactBeforeApprovalAndWebCompletion() = scenario(true, "available")
    @Test fun changedArtifactNeverBecomesHistoricalEvidenceOrTriggersAnAction() = scenario(false, "changed")

    private fun scenario(approve: Boolean, artifactState: String? = null) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.applicationInfo.nativeLibraryDir = context.filesDir.path
        val effects = AtomicInteger()
        val reads = AtomicInteger()
        val calls = AtomicInteger()
        val artifactReads = AtomicInteger()
        val fact = "deployment_target=region-seven"
        var searched = false
        var actionRequested = false
        var artifactPath = ""
        var artifactHash = ""
        val plugin = HarnessPlugin(
            PluginDescriptor("example.context", "1.0.0", extensions = listOf(
                PluginExtension("record", PluginExtensionPoint.TOOL), PluginExtension("fake", PluginExtensionPoint.PROVIDER))),
            tools = listOf(PluginTool("record", "Record the recovered target",
                Json.parseToJsonElement("""{"type":"object","properties":{"target":{"type":"string"}},"required":["target"]}""").jsonObject) {
                assertEquals("region-seven", it.arguments["target"]!!.jsonPrimitive.content)
                effects.incrementAndGet()
                PluginToolOutput(true, "context-effect-recorded")
            }),
            providers = listOf(PluginProvider("fake", LlmApi.OPENAI_COMPLETIONS, "context-test") { _, _ -> object : LlmApiAdapter {
                override val api = LlmApi.OPENAI_COMPLETIONS
                override suspend fun chat(model: ModelConfig, messages: List<ApiMessage>): ChatResult {
                    assertNull(model.apiKey)
                    val number = calls.incrementAndGet()
                    check(number < 25) { "Recovery did not converge" }
                    val text = messages.joinToString("\n") { it.content.orEmpty() }
                    if (actionRequested) return ChatResult(if (text.contains("context-effect-recorded"))
                        "Recovered region-seven and recorded it" else "Recovered region-seven; action was denied", emptyList())
                    if (text.contains(fact)) {
                        actionRequested = true
                        return ChatResult(null, listOf(ApiToolCallSpec("effect", "use_capability",
                            """{"action":"call","server":"plugin.example.context","tool":"record","arguments":{"target":"region-seven"}}""")))
                    }
                    if (!searched) {
                        assertFalse("The fact must actually be missing from the model context", text.contains(fact))
                        searched = true
                        return ChatResult(null, listOf(ApiToolCallSpec("search", "history_search", """{"query":"report.log"}""")))
                    }
                    val lastPage = messages.lastOrNull { it.role == "tool" }?.content.orEmpty()
                    if (artifactState != null) {
                        if (lastPage.contains("artifact_status=UNAVAILABLE") || lastPage.contains("artifact_status=CHANGED") ||
                            (lastPage.contains("[history_read") && lastPage.contains("next_offset=null") &&
                                !lastPage.contains("history_artifact"))) {
                            return ChatResult("Evidence unavailable; deployment target cannot be verified", emptyList())
                        }
                        if (lastPage.contains("history_artifact")) {
                            artifactPath = Regex("path=(\\S+)").find(lastPage)!!.groupValues[1]
                            artifactHash = Regex("sha256=([a-f0-9]{64})").find(lastPage)!!.groupValues[1]
                        }
                        if (lastPage.contains("history_artifact") || lastPage.contains("artifact_status=VERIFIED")) {
                            val line = if (lastPage.contains("artifact_status=VERIFIED"))
                                Regex("offset=(\\d+)").find(lastPage)?.groupValues?.get(1)?.toInt()
                                    ?: error("Artifact exhausted without the fact") else 1
                            artifactReads.incrementAndGet()
                            return ChatResult(null, listOf(ApiToolCallSpec("artifact-read-$number", "read",
                                buildJsonObject { put("path", artifactPath); put("expected_sha256", artifactHash)
                                    put("offset", line); put("limit", 200) }.toString())))
                        }
                    }
                    val offset = if (lastPage.contains("[history_read")) {
                        Regex("next_offset=(\\d+)").find(lastPage)?.groupValues?.get(1)?.toInt()
                            ?: error("History exhausted without the fact")
                    } else {
                        assertTrue(lastPage.contains("id=old-result"))
                        0
                    }
                    reads.incrementAndGet()
                    return ChatResult(null, listOf(ApiToolCallSpec("page-$number", "history_read",
                        """{"message_id":"old-result","offset":$offset}""")))
                }
                override suspend fun chatStream(model: ModelConfig, messages: List<ApiMessage>, onReasoning: (String) -> Unit,
                    onToolProgress: (ToolCallStreamProgress) -> Unit, onDelta: (String) -> Unit): ChatResult =
                    chat(model, messages).also { it.content?.let(onDelta) }
            } }),
        )
        val application = koinApplication {
            androidContext(context)
            modules(tianyanModule, module {
                single<LinuxRuntime> {
                    val paths = get<top.tianyan.app.runtime.RuntimePathManager>()
                    object : LinuxRuntime by FakeLinuxRuntime() { override fun workspacePath() = paths.workspaceDir }
                }
                single { OkHttpClient.Builder().addInterceptor { error("Provider network is forbidden") }.build() }
                single<HarnessPlugin>(named("example.context")) { plugin }
            })
        }
        val koin = application.koin
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val server = koin.get<WebChatBridgeServer>()
        val events = Channel<JsonObject>(Channel.UNLIMITED)
        var session: String? = null
        var stream: Call? = null
        var reader: Job? = null
        try {
            koin.get<AiModelRepository>().upsert(AiModelEntity("context-fake", "Fake", "context-test", "fixture",
                "https://example.invalid", isActive = true, createdAt = 1))
            val gateway = koin.get<TianyanWebChatAgentGateway>()
            val testWorkspace = java.io.File(koin.get<top.tianyan.app.runtime.RuntimePathManager>().workspaceDir,
                "context-${java.util.UUID.randomUUID()}").apply { mkdirs() }
            val id = gateway.createSession("Context recovery", "/workspace/${testWorkspace.name}")
            session = id
            val sessions = koin.get<HarnessSessionRepository>()
            sessions.upsert(sessions.findById(id)!!.copy(approvalMode = ApprovalMode.REQUEST.id))
            val store = koin.get<SessionTreeStore>()
            store.append(id, UserMessage("old-user", 1, "Inspect report.log"))
            store.append(id, ToolCall("old-call", 2, HarnessTool.READ, buildJsonObject { put("path", "report.log") }, rawToolName = "read"))
            val original = if (artifactState == "available") "padding".repeat(10).plus("\n").repeat(900) + fact + "\n" +
                "padding\n".repeat(100) else "padding".repeat(9_000) + fact + "padding".repeat(1_000)
            val oldResult = if (artifactState == null) ToolResult("old-result", 3, "old-call", true, original) else {
                val workspace = testWorkspace
                val files = WorkspaceFileAccess(workspace)
                if (artifactState == "write-failed") {
                    val directory = java.io.File(workspace, ToolOutputSpillStore.DIR)
                    directory.parentFile!!.mkdirs()
                    assertTrue(directory.createNewFile()) // A real I/O failure: directory path is a file.
                }
                val path = ToolOutputSpillStore.spill(files, "read", original)
                if (artifactState != "write-failed") {
                    assertNotNull(path)
                    val artifact = ToolOutputArtifact.capture(path!!, original)
                    if (artifactState == "expired") {
                        ToolOutputSpillStore.cleanup(files, System.currentTimeMillis() + ToolOutputSpillStore.RETENTION_MS + 60_000)
                        assertNull(files.fileSizeOrNull(path))
                    } else if (artifactState == "changed") {
                        assertTrue(files.write(path, "replacement_target=untrusted-region").isSuccess)
                    }
                    ToolResult("old-result", 3, "old-call", true, "report.log preview; full output archived",
                        metadata = mapOf(ToolOutputArtifact.METADATA_KEY to Json.encodeToString(ToolOutputArtifact.serializer(), artifact)))
                } else {
                    assertNull(path)
                    ToolResult("old-result", 3, "old-call", true, "report.log preview; archive write failed; remaining evidence unavailable")
                }
            }
            store.append(id, oldResult)
            val compaction = koin.get<CompactionManager>()
            compaction.compact(id, compaction.project(id), 3)
            assertFalse(compaction.project(id).summaryLayer.contains(fact))
            val port = ServerSocket(0).use { it.localPort }
            assertTrue(server.start(port, "654321"))
            val base = "http://127.0.0.1:$port/webchat/api"
            suspend fun request(path: String, body: String? = null): JsonElement = withContext(Dispatchers.IO) {
                val builder = Request.Builder().url(base + path).header("Authorization", "Bearer 654321")
                if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
                client.newCall(builder.build()).execute().use {
                    val content = it.body.string()
                    assertEquals(content, 200, it.code)
                    Json.parseToJsonElement(content)
                }
            }
            val ready = CompletableDeferred<Unit>()
            val connection = client.newCall(Request.Builder().url("$base/events").header("Authorization", "Bearer 654321").build())
            stream = connection
            reader = launch(Dispatchers.IO) {
                try {
                    connection.execute().use { response ->
                        check(response.code == 200)
                        val source = response.body.source()
                        var name = ""
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("event: ")) {
                                name = line.removePrefix("event: ")
                                if (name == "ping") ready.complete(Unit)
                            }
                            if (line.startsWith("data: ") && name == "chat_task_event")
                                events.send(Json.parseToJsonElement(line.removePrefix("data: ")).jsonObject)
                        }
                    }
                } catch (failure: Exception) {
                    if (!ready.isCompleted) ready.completeExceptionally(failure)
                    events.close(failure)
                }
            }
            withTimeout(30_000) {
                ready.await()
                val receipt = request("/conversations/$id/runs", """{"taskId":"context-wire","userMessage":"Recover the deployment target and record it"}""").jsonObject
                assertEquals("context-wire", receipt["taskId"]!!.jsonPrimitive.content)
                assertNotNull(receipt["input"]!!.jsonObject["durableTaskId"])
                if (artifactState != null && artifactState != "available") {
                    val completed = events.receive()
                    assertEquals("$completed ${koin.get<top.tianyan.app.harness.projection.SessionStateMirrors>().errorOf(id)}",
                        "completed", completed["kind"]!!.jsonPrimitive.content)
                    assertEquals("context-wire", completed["taskId"]!!.jsonPrimitive.content)
                    assertEquals(0, effects.get())
                    val committed = koin.get<SessionControl>().persistedMessages(id)
                    assertTrue(committed.filterIsInstance<AssistantText>().last().text.contains("Evidence unavailable"))
                    assertFalse(committed.filterIsInstance<AssistantText>().last().text.contains("region-seven"))
                    assertFalse(committed.filterIsInstance<ToolResult>().any { it.output.contains("untrusted-region") })
                    if (artifactState == "changed") {
                        assertEquals(1, artifactReads.get())
                        assertTrue(committed.filterIsInstance<ToolResult>().any { it.output.contains("artifact_status=CHANGED") })
                    }
                    assertEquals(1, committed.filterIsInstance<ToolCall>().count {
                        it.tool == HarnessTool.READ && it.args["path"]?.jsonPrimitive?.content == "report.log"
                    })
                    assertTrue(committed.filterIsInstance<ToolCall>().none { it.rawToolName == "use_capability" })
                    return@withTimeout
                }
                val waiting = events.receive()
                assertEquals("$waiting ${koin.get<top.tianyan.app.harness.projection.SessionStateMirrors>().errorOf(id)}",
                    "waiting_approval", waiting["kind"]!!.jsonPrimitive.content)
                assertEquals("context-wire", waiting["taskId"]!!.jsonPrimitive.content)
                assertEquals(0, effects.get())
                if (artifactState == "available") assertEquals(5, artifactReads.get()) else assertTrue(reads.get() >= 8)
                val pending = waiting["approvals"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content
                val settled = request("/conversations/$id/approvals/$pending", """{"approved":$approve}""").jsonObject
                assertEquals("true", settled["accepted"]!!.jsonPrimitive.content)
                val completed = events.receive()
                assertEquals("completed", completed["kind"]!!.jsonPrimitive.content)
                assertEquals("context-wire", completed["taskId"]!!.jsonPrimitive.content)
                assertEquals(id, completed["conversationId"]!!.jsonPrimitive.content)
                assertEquals(if (approve) 1 else 0, effects.get())
                val history = request("/conversations/$id/messages").toString()
                assertTrue(history.contains(if (approve) "Recovered region-seven and recorded it" else "action was denied"))
                val committed = koin.get<SessionControl>().persistedMessages(id)
                assertEquals(1, committed.filterIsInstance<ToolCall>().count {
                    it.rawToolName == "use_capability" && it.args["server"]?.jsonPrimitive?.content == "plugin.example.context"
                })
                assertEquals(1, committed.filterIsInstance<ToolCall>().count {
                    it.tool == HarnessTool.READ && it.args["path"]?.jsonPrimitive?.content == "report.log"
                })
                if (artifactState == "available") {
                    val verifiedPages = committed.filterIsInstance<ToolResult>().filter { it.output.contains("artifact_status=VERIFIED") }
                    assertEquals(5, verifiedPages.size)
                    assertTrue(verifiedPages.last().output.contains(fact))
                    val offsets = committed.filterIsInstance<ToolCall>().filter { "expected_sha256" in it.args }
                        .map { it.args["offset"]!!.jsonPrimitive.int }
                    assertEquals(listOf(1, 201, 401, 601, 801), offsets)
                }
            }
        } finally {
            reader?.cancel()
            stream?.cancel()
            reader?.join()
            server.stop()
            session?.let { koin.get<SessionControl>().cancel(it) }
            runCatching { koin.get<AppDatabase>().close() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            application.close()
        }
    }
}
