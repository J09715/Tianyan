package top.tianyan.app.harness

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.core.*
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.runtime.LinuxRuntime

/** Fake model drives the real turn runner and history backend after recreating the session adapters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryContextRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Reply(val text: String = "", override val toolCalls: List<ToolCall> = emptyList()) : TurnResponse<ToolCall> {
        override val failureMessage: String? = null
    }

    @Test fun `compacted omitted fact beyond 48K is recovered by search and paged tool turns`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repository = RoomHarnessRuntimeRepository(db.harnessRuntimeDao())
            val logger = AppLogger(context, SensitiveDataRedactor { it })
            var store = SessionTreeStore(repository, Json, logger)
            var manager = CompactionManager(repository, Json, store)
            val session = "recover-fact"
            val fact = "deployment_target=region-seven"
            store.append(session, UserMessage("old-user", 0, "Inspect report.log"))
            store.append(session, ToolCall("old-call", 1, HarnessTool.READ,
                buildJsonObject { put("path", "report.log") }, rawToolName = "read"))
            store.append(session, ToolResult("old-result", 2, "old-call", true,
                "padding".repeat(9_000) + fact + "padding".repeat(1_000)))
            store.append(session, UserMessage("new-user", 3, "Find the deployment target in the earlier report"))
            manager.compact(session, manager.project(session), 3)
            assertFalse(manager.project(session).summaryLayer.contains(fact))
            // Recreate adapters; recovery cannot rely on their in-memory branch cache.
            store = SessionTreeStore(repository, Json, logger)
            manager = CompactionManager(repository, Json, store)
            val backend = ContextMemoryToolBackend(store, manager)
            val executor = executor(backend)
            val runner = AgentTurnRunner()
            var sequence = 10L
            var lastOutput = ""
            var searchDone = false
            var readId: String? = null
            var readOffset = 0
            var pageCount = 0
            var outcome: TurnOutcome = TurnOutcome.Continue(0, true)
            while (outcome != TurnOutcome.Complete && sequence < 100) {
                outcome = runner.run(
                    callProvider = {
                        val projected = manager.project(session)
                        assertEquals("new-user", projected.messages.filterIsInstance<UserMessage>().first().id)
                        val response = when {
                            !searchDone -> Reply(toolCalls = listOf(ToolCall("search", sequence++, HarnessTool.HISTORY_SEARCH,
                                buildJsonObject { put("query", "report.log") })))
                            lastOutput.contains(fact) -> Reply(text = "Deployment target: region-seven")
                            else -> {
                                if (readId == null) {
                                    // Related call/result search hits carry the original branch index, not their rank.
                                    assertTrue(lastOutput.contains("[index=2] id=old-result"))
                                    readId = "old-result"
                                } else {
                                    readOffset = Regex("next_offset=(\\d+)").find(lastOutput)!!.groupValues[1].toInt()
                                }
                                Reply(toolCalls = listOf(ToolCall("read-${sequence++}", sequence, HarnessTool.HISTORY_READ,
                                    buildJsonObject { put("message_id", readId); put("offset", readOffset) })))
                            }
                        }
                        ProviderTurnResult.Success(response)
                    },
                    persistAssistant = { reply ->
                        if (reply.text.isNotEmpty()) store.append(session, AssistantText("final", sequence++, reply.text))
                        reply.toolCalls.forEach { store.append(session, it) }
                    },
                    consumeFollowUps = { 0 },
                    enforceToolLimit = { calls, _ -> calls },
                    executeTools = { calls, _ ->
                        calls.forEach { call ->
                            assertNotNull(store.read(session, call.id))
                            val result = executor.execute(call, session)
                            assertTrue(result.success)
                            lastOutput = result.output
                            assertFalse(lastOutput.contains("单行内容过长已折叠"))
                            if (call.tool == HarnessTool.HISTORY_SEARCH) searchDone = true else pageCount++
                            store.append(session, result)
                        }
                        true
                    },
                )
            }
            assertEquals(TurnOutcome.Complete, outcome)
            assertTrue("Fact must require multiple pages beyond the former 48K cap", pageCount >= 8)
            assertEquals("Deployment target: region-seven", (store.read(session, "final") as AssistantText).text)
            assertEquals(1, store.loadStrict(session).filterIsInstance<ToolCall>().count { it.tool == HarnessTool.READ })
            // The original call and its result are addressable by either index or stable ID.
            val byIndex = backend.execute(ContextMemoryRequest(HarnessTool.HISTORY_READ, buildJsonObject { put("index", 2) }, session))
            val byId = backend.execute(ContextMemoryRequest(HarnessTool.HISTORY_READ, buildJsonObject { put("message_id", "old-result") }, session))
            assertEquals(byId, byIndex)
            assertFalse(backend.execute(ContextMemoryRequest(HarnessTool.HISTORY_READ,
                buildJsonObject { put("message_id", "missing") }, session)).first)
            assertTrue(runCatching { backend.execute(ContextMemoryRequest(HarnessTool.HISTORY_READ,
                buildJsonObject { put("message_id", "old-result"); put("offset", -1) }, session)) }.isFailure)
            assertEquals(byId, backend.execute(ContextMemoryRequest(HarnessTool.HISTORY_READ,
                buildJsonObject { put("message_id", "old-result"); put("index", -1) }, session)))
        } finally {
            db.close()
        }
    }

    private fun executor(backend: ContextMemoryToolBackend): ToolExecutor {
        val files = WorkspaceFileAccess(temporary.newFolder())
        val paths = HarnessPathResolver()
        val policy = ApprovalPolicyEngine(paths)
        return ToolExecutor(files, paths, policy, SecretRedactor(),
            HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            LinuxCommandToolBackend(unused<LinuxRuntime>(), paths),
            DownloadToolBackend(unused<FileDownloader>(), files, WorkspaceMutationSnapshots()),
            backend, AskUserToolBackend(policy, null), PromptAssetToolBackend(), HarnessServiceToolBackend(),
            CapabilityToolGateway(null) { _, _, _, _ -> error("Unexpected capability") })
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader,
        arrayOf(T::class.java)) { _, method, _ -> error("Unexpected operation: ${method.name}") } as T
}
