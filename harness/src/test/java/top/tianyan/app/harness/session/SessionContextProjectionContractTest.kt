package top.tianyan.app.harness.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.compaction.*
import top.tianyan.app.harness.core.*

/** The same contracts run against interface-default queries and optimized Room recursive queries. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionContextProjectionContractTest {
    private class RecordingRepository(val delegate: HarnessRuntimeRepository) : HarnessRuntimeRepository by delegate {
        var fullReads = 0
        var windowReads = 0
        var beforeLatest: (suspend () -> Unit)? = null
        override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
            fullReads++
            return delegate.branch(sessionId, leafId)
        }
        override suspend fun branchWindow(sessionId: String, leafId: String?, minSequence: Long): List<HarnessEntryEntity> {
            windowReads++
            return delegate.branchWindow(sessionId, leafId, minSequence)
        }
        override suspend fun latestBranchEntryOfType(sessionId: String, leafId: String?, entryType: String): HarnessEntryEntity? {
            beforeLatest?.also { beforeLatest = null }?.invoke()
            return delegate.latestBranchEntryOfType(sessionId, leafId, entryType)
        }
    }

    private class Fixture(val repository: RecordingRepository, context: Context) {
        val store = SessionTreeStore(repository, Json, AppLogger(context, SensitiveDataRedactor { it }))
        val manager = CompactionManager(repository, Json, store)
        suspend fun append(message: HarnessMessage, lane: String = "main") = store.append("session", message, lane)
        suspend fun raw(id: String, type: String, payload: String, customType: String? = null, lane: String = "main") {
            val leaf = repository.ensureLane("session", lane).leafId
            repository.appendToLane("session", lane, HarnessEntryEntity(id = id, sessionId = "session", parentId = leaf,
                createdAt = 1, entryType = type, customType = customType, payloadJson = payload))
        }
        suspend fun checkpoint(id: String, retained: List<HarnessMessage>, watermark: Long? = null, legacy: Boolean = false) {
            val payload = CompactionPayload(repository.findLane("session", "main")?.leafId, "summary-$id",
                retainedMessagesJson = if (legacy) Json.encodeToString(ListSerializer(HarnessMessage.serializer()), retained) else null,
                compactedMessageCount = 1, retainedMessageCount = retained.size, estimatedTokensBefore = 100, createdAt = 1,
                sourceWatermarkSequence = watermark, retainedMessages = if (legacy) null else retained)
            raw(id, CompactionManager.ENTRY_TYPE, Json.encodeToString(CompactionPayload.serializer(), payload))
        }
        suspend fun branchSummary(id: String, text: String) = raw(id, CompactionManager.BRANCH_SUMMARY_ENTRY_TYPE,
            Json.encodeToString(BranchSummaryPayload.serializer(), BranchSummaryPayload(text, null, 1, 1)))
    }

    private fun contracts(block: suspend (Fixture) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (room in listOf(false, true)) {
            val database = if (room) Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build() else null
            val repository = RecordingRepository(database?.let { RoomHarnessRuntimeRepository(it.harnessRuntimeDao()) } ?: MemoryRepository())
            try { block(Fixture(repository, context)) } finally { database?.close() }
        }
    }

    @Test fun `inspection of an absent lane does not create storage state`() = contracts { f ->
        val inspection = f.manager.inspect("session")
        assertNull(inspection.leafId)
        assertTrue(inspection.projection.messages.isEmpty())
        assertNull(f.repository.findLane("session", "main"))
        assertNull(f.manager.latestSnapshot("session"))
    }

    @Test fun `branch switching isolates sibling content and keeps the source leaf`() = contracts { f ->
        f.append(UserMessage("root", 1, "shared"))
        f.append(AssistantText("main", 2, "main answer"))
        f.repository.ensureLane("session", "other", "root")
        f.append(AssistantText("other", 3, "other answer"), "other")
        val main = f.manager.inspect("session")
        val other = f.manager.inspect("session", "other")
        assertEquals("main", main.leafId)
        assertEquals("other", other.leafId)
        assertEquals(listOf("root", "main"), main.context().messages.map { it.id })
        assertEquals(listOf("root", "other"), other.context().messages.map { it.id })
        assertEquals(main.context(), f.manager.project("session"))
    }

    @Test fun `lane move during reads cannot combine one branch checkpoint with another branch`() = contracts { f ->
        f.append(UserMessage("root", 1, "shared"))
        f.append(UserMessage("kept", 2, "main"))
        f.checkpoint("c", listOf(UserMessage("kept", 2, "main")))
        f.repository.beforeLatest = { f.repository.moveLane("session", "main", "root") }
        val captured = f.manager.inspect("session")
        assertEquals("c", captured.leafId)
        assertEquals(listOf("kept"), captured.context().messages.map { it.id })
        assertEquals(listOf("root"), f.manager.project("session").messages.map { it.id })
    }

    @Test fun `latest checkpoint replaces older summaries and stays on the window query`() = contracts { f ->
        f.append(UserMessage("old", 1, "folded"))
        f.append(UserMessage("kept", 2, "kept"))
        f.checkpoint("c1", listOf(UserMessage("kept", 2, "kept")))
        f.append(UserMessage("new", 3, "new"))
        f.checkpoint("c2", listOf(UserMessage("new", 3, "new")))
        f.repository.fullReads = 0
        val inspection = f.manager.inspect("session")
        assertEquals("c2", inspection.projection.checkpointId)
        assertEquals("summary-c2", inspection.context().summary)
        assertEquals(listOf("new"), inspection.context().messages.map { it.id })
        assertEquals("c2", inspection.projection.messages.single().sourceEntryId)
        assertEquals(ContextMessageOrigin.RETAINED_SNAPSHOT, inspection.projection.messages.single().origin)
        assertEquals(0, f.repository.fullReads)
        assertTrue(f.repository.windowReads > 0)
    }

    @Test fun `compact returns concurrent direct writes immediately and records their recovery provenance`() = contracts { f ->
        f.append(UserMessage("old", 1, "old"))
        f.append(UserMessage("kept", 2, "kept"))
        f.manager.beforeCompactionWriteForTest = {
            f.raw("late", "message", Json.encodeToString(HarnessMessage.serializer(), UserMessage("late", 3, "late arrival")))
            f.branchSummary("late-summary", "recovered branch")
        }
        val compacted = f.manager.compact("session", f.manager.project("session"), 1)
        val inspection = f.manager.inspect("session")
        assertEquals(inspection.context(), compacted)
        assertEquals(listOf("kept", "late"), compacted.messages.map { it.id })
        assertEquals(listOf("recovered branch"), compacted.branchSummaries)
        assertEquals(ContextMessageOrigin.WATERMARK_RECOVERY, inspection.projection.messages.last().origin)
        assertEquals("late", inspection.projection.messages.last().sourceEntryId)
        assertEquals(ProjectionContribution.RECOVERED_BRANCH_SUMMARY,
            inspection.projection.entries.single { it.entryId == "late-summary" }.contribution)
        assertTrue(compacted.sourceMaxSequence > 0)
    }

    @Test fun `retained recall stays frozen and folded branch summaries are not injected twice`() = contracts { f ->
        f.append(UserMessage("old", 1, "old"))
        f.branchSummary("prior-summary", "old branch facts")
        f.append(UserMessage("kept", 2, "kept"))
        f.store.appendRecallBlock("session", "kept", "frozen recall")
        f.manager.compact("session", f.manager.project("session"), 1)
        f.branchSummary("new-summary", "new branch facts")
        val inspection = f.manager.inspect("session")
        assertEquals("frozen recall", inspection.context().recallBlocks["kept"])
        assertEquals(listOf("new branch facts"), inspection.context().branchSummaries)
        assertTrue(inspection.context().summary!!.contains("old branch facts"))
        assertEquals(SessionTreeStore.RECALL_ENTRY_PREFIX + "kept", inspection.projection.recallBlocks.getValue("kept").sourceEntryId)
    }

    @Test fun `legacy retained JSON uses the same context and provenance rules`() = contracts { f ->
        val kept = UserMessage("kept", 2, "legacy")
        f.append(UserMessage("old", 1, "old"))
        f.checkpoint("legacy", listOf(kept), legacy = true)
        val inspection = f.manager.inspect("session")
        assertEquals(listOf(kept), inspection.context().messages)
        assertEquals("legacy", inspection.projection.messages.single().sourceEntryId)
        assertTrue(inspection.projection.issues.isEmpty())
    }

    @Test fun `malformed legacy retention falls back to raw active branch instead of dropping kept history`() = contracts { f ->
        f.append(UserMessage("old", 1, "old"))
        f.append(UserMessage("kept", 2, "important"))
        val payload = CompactionPayload("kept", "summary", retainedMessagesJson = "invalid secret-payload",
            compactedMessageCount = 1, retainedMessageCount = 1, estimatedTokensBefore = 1, createdAt = 1)
        f.raw("broken", CompactionManager.ENTRY_TYPE, Json.encodeToString(CompactionPayload.serializer(), payload))
        val inspection = f.manager.inspect("session")
        assertNull(inspection.context().summary)
        assertEquals(listOf("old", "kept"), inspection.context().messages.map { it.id })
        assertEquals(listOf(ProjectionIssue("broken", ProjectionIssueCode.MALFORMED_RETAINED_MESSAGES)), inspection.projection.issues)
        assertFalse(inspection.projection.issues.toString().contains("secret-payload"))
    }

    @Test fun `corrupt checkpoint has explicit fallback diagnostics`() = contracts { f ->
        f.append(UserMessage("old", 1, "important"))
        f.raw("broken", CompactionManager.ENTRY_TYPE, "invalid")
        val inspection = f.manager.inspect("session")
        assertEquals(listOf("old"), inspection.context().messages.map { it.id })
        assertEquals(listOf(ProjectionIssue("broken", ProjectionIssueCode.MALFORMED_CHECKPOINT)), inspection.projection.issues)
    }

    @Test fun `invalid future watermark cannot hide active messages`() = contracts { f ->
        f.append(UserMessage("old", 1, "important"))
        f.checkpoint("broken", emptyList(), watermark = Long.MAX_VALUE)
        val inspection = f.manager.inspect("session")
        assertEquals(listOf("old"), inspection.context().messages.map { it.id })
        assertEquals(ProjectionIssueCode.INVALID_WATERMARK, inspection.projection.issues.single().code)
    }

    @Test fun `malformed messages and branch summaries remain visible in inspection without entering context`() = contracts { f ->
        f.append(UserMessage("valid", 1, "valid"))
        f.raw("bad-message", "message", "invalid")
        f.raw("bad-summary", CompactionManager.BRANCH_SUMMARY_ENTRY_TYPE, "invalid")
        f.raw("custom", "usage", "not model input")
        val inspection = f.manager.inspect("session")
        assertEquals(listOf("valid"), inspection.context().messages.map { it.id })
        assertEquals(2, inspection.projection.issues.size)
        assertEquals(ProjectionContribution.STATE, inspection.projection.entries.last().contribution)
        assertTrue(inspection.context().branchSummaries.isEmpty())
    }

    @Test fun `native and text tool projections replay the retained call result pair identically`() = contracts { f ->
        f.append(UserMessage("old", 1, "fold me"))
        val kept = listOf(UserMessage("kept", 2, "read"),
            ToolCall("call", 3, HarnessTool.READ, JsonObject(emptyMap()), rawToolName = "read"),
            ToolResult("result", 4, "call", true, "file contents"))
        kept.forEach { f.append(it) }
        f.manager.compact("session", f.manager.project("session"), 1)
        for (mode in listOf(ToolCallMode.NATIVE, ToolCallMode.JSON_TEXT)) {
            assertEquals(ApiMessageProjector.project(kept, mode, false),
                ApiMessageProjector.project(f.manager.inspect("session").context().messages, mode, false))
        }
    }

    /** Interface defaults exercise a full in-memory path while Room uses SQL windows. */
    private class MemoryRepository : HarnessRuntimeRepository by unusedRepository() {
        private val entries = linkedMapOf<String, HarnessEntryEntity>()
        private val lanes = mutableMapOf<Pair<String, String>, HarnessLaneEntity>()
        private var nextSequence = 0L
        override suspend fun findLane(sessionId: String, laneName: String) = lanes[sessionId to laneName]
        override suspend fun ensureLane(sessionId: String, laneName: String, atEntryId: String?): HarnessLaneEntity =
            lanes.getOrPut(sessionId to laneName) { HarnessLaneEntity(sessionId, laneName, atEntryId, updatedAt = 1) }
        override suspend fun branch(sessionId: String, leafId: String?): List<HarnessEntryEntity> {
            val branch = mutableListOf<HarnessEntryEntity>()
            var id = leafId
            while (id != null) {
                val entry = entries[id]?.takeIf { it.sessionId == sessionId } ?: break
                branch += entry
                id = entry.parentId
            }
            return branch.asReversed()
        }
        override suspend fun branchWindow(sessionId: String, leafId: String?, minSequence: Long) =
            super<HarnessRuntimeRepository>.branchWindow(sessionId, leafId, minSequence)
        override suspend fun latestBranchEntryOfType(sessionId: String, leafId: String?, entryType: String) =
            super<HarnessRuntimeRepository>.latestBranchEntryOfType(sessionId, leafId, entryType)
        override suspend fun appendToLane(sessionId: String, laneName: String, entry: HarnessEntryEntity) {
            val lane = ensureLane(sessionId, laneName, null)
            entries[entry.id] = entry.copy(sequence = ++nextSequence, parentId = lane.leafId)
            lanes[sessionId to laneName] = lane.copy(leafId = entry.id)
        }
        override suspend fun moveLane(sessionId: String, laneName: String, leafId: String?) {
            lanes[sessionId to laneName] = ensureLane(sessionId, laneName, null).copy(leafId = leafId)
        }
    }

    companion object {
        private fun unusedRepository(): HarnessRuntimeRepository = Proxy.newProxyInstance(
            HarnessRuntimeRepository::class.java.classLoader, arrayOf(HarnessRuntimeRepository::class.java),
        ) { _, method, _ -> error("Unexpected persistence operation: ${method.name}") } as HarnessRuntimeRepository
    }
}
