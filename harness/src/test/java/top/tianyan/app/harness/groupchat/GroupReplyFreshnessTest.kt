package top.tianyan.app.harness.groupchat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.*
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.harness.*
import top.tianyan.app.harness.core.TurnOutcome
import top.tianyan.app.harness.projection.LiveMessagePort

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupReplyFreshnessTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val groups = RoomGroupChatRepository(db.groupChatDao(), Json)
    private val port = RecordingPort()
    private val freshness = GroupReplyFreshness(groups, port)
    private var timestamp = 0L

    @After fun close() = db.close()

    private suspend fun seed() {
        db.personaBotDao().upsert(BotEntity("bot", "Chief", "lead", "persona", "seed", null, null, "dm", true, "#123456", 1, 1))
        db.personaBotDao().upsert(BotEntity("admin", "Admin", "lead", "", "seed", null, null, "dm-admin", false, "#123456", 1, 1))
        for (id in listOf("group", "other")) {
            db.groupChatDao().upsertGroup(GroupChatEntity(id, id, "admin", "", 1))
            db.groupChatDao().addMember(GroupMemberEntity(id, "bot", 0, 1))
            db.groupChatDao().addMember(GroupMemberEntity(id, "admin", 1, 1))
        }
    }
    private suspend fun post(id: String, text: String, type: GroupSenderType = GroupSenderType.USER,
        streaming: Boolean = false, groupId: String = "group") {
        groups.postMessage(GroupMessage(id, groupId, "sender", "Sender", type, text, ++timestamp, isStreaming = streaming))
    }
    private suspend fun begin(session: String = "session", placeholder: String = "own") =
        freshness.begin(session, groups.getGroup("group")!!, "bot", placeholder)

    @Test fun `request preparation sees current scoped history and only settled peer updates`() = runBlocking {
        seed()
        post("old", "old question")
        post("queued-correction", "corrected before queue was acquired")
        post("foreign", "PRIVATE OTHER GROUP", groupId = "other")
        val binding = begin()
        assertTrue(binding.history.contains("corrected before queue was acquired"))
        assertFalse(binding.history.contains("PRIVATE"))
        post("own", "own streaming checkpoint", GroupSenderType.BOT)
        post("peer", "unfinished peer", GroupSenderType.BOT, true)
        post("noise", "ordinary system noise", GroupSenderType.SYSTEM)
        assertEquals(0, freshness.refresh("session"))
        post("peer", "verified peer result", GroupSenderType.BOT)
        assertEquals(1, freshness.refresh("session"))
        val text = port.rows.single().second.text
        assertTrue(text.contains("verified peer result"))
        assertFalse(text.contains("own streaming"))
        assertFalse(text.contains("PRIVATE"))
        assertEquals(0, freshness.refresh("session"))
        assertEquals(0, freshness.refresh("dm"))
    }

    @Test fun `new correction after text publication continues original run without replaying successful tool`() = runBlocking {
        seed(); begin()
        val runner = TurnRunner(ProviderResponseNormalizer(Json), freshness)
        var tools = 0
        var calls = 0
        suspend fun turn(text: String, afterText: suspend () -> Unit = {}) = runner.run(
            sessionId = "session", toolsEnabled = true,
            callProvider = { calls++; TurnProviderOutcome.Success(ChatResult(text, emptyList()), text) },
            persistAssistant = { afterText() }, consumeFollowUps = { 0 },
            enforceToolLimit = { requests, _ -> requests }, executeTools = { _, _ -> tools++; true },
        )
        val tool = "[[tool_call]]{\"name\":\"read\",\"arguments\":{\"path\":\"output.txt\"}}[[/tool_call]]"
        assertTrue(turn(tool) is TurnOutcome.Continue)
        assertEquals(1, tools)
        val stale = turn("old draft") {
            post("correction", "use the existing output, only fix the conclusion")
            post("peer", "output already verified", GroupSenderType.BOT)
        }
        assertTrue(stale is TurnOutcome.Continue)
        assertTrue(port.rows.last().second.text.contains("only fix the conclusion"))
        assertEquals(TurnOutcome.Complete, turn("corrected conclusion"))
        assertEquals(1, tools)
        assertEquals(3, calls)
        assertEquals(1, port.rows.size)
    }

    @Test fun `content edits are reread but unchanged snapshots and finished bindings do not replay`() = runBlocking {
        seed(); post("source", "first"); val binding = begin()
        post("source", "edited correction")
        assertEquals(1, freshness.refresh("session", true))
        assertEquals(0, freshness.refresh("session", true))
        freshness.end("session", binding)
        post("late", "not for a finished run")
        assertEquals(0, freshness.refresh("session", true))
        val replacement = begin()
        freshness.end("session", binding)
        post("new", "belongs to replacement")
        assertEquals(1, freshness.refresh("session", true))
        freshness.end("session", replacement)
    }

    @Test fun `final refresh is bounded under continuous peer chatter and user corrections are prioritized`() = runBlocking {
        seed(); begin()
        repeat(3) { post("peer-$it", "peer result $it", GroupSenderType.BOT); assertEquals(1, freshness.refresh("session", true)) }
        post("too-late", "another peer", GroupSenderType.BOT)
        assertEquals(0, freshness.refresh("session", true))
        assertEquals(3, port.rows.size)
        // A subsequent real provider round may still import new facts, without buying more final revisions.
        repeat(20) { post("long-$it", "x".repeat(4_000), GroupSenderType.BOT) }
        post("user", "IMPORTANT CORRECTION")
        assertEquals(1, freshness.refresh("session"))
        val text = port.rows.last().second.text
        assertTrue(text.indexOf("IMPORTANT CORRECTION") < text.indexOf("x".repeat(100)))
        assertTrue(text.contains("未展开"))
        assertTrue(text.length < 15_000)
        assertEquals(0, freshness.refresh("session", true))
    }

    @Test fun `removed members cannot import later group messages`() = runBlocking {
        seed(); begin()
        db.groupChatDao().removeMember("group", "bot")
        post("private", "revoked member must not read this")
        assertEquals(0, freshness.refresh("session", true))
        assertTrue(port.rows.isEmpty())
    }

    @Test fun `a burst of corrections retains the newest requirements within the context limit`() = runBlocking {
        seed(); begin()
        repeat(15) { post("user-$it", "CORRECTION-$it-END") }
        assertEquals(1, freshness.refresh("session", true))
        val text = port.rows.single().second.text
        assertTrue(text.contains("CORRECTION-14-END"))
        assertFalse(text.contains("CORRECTION-0-END"))
        assertTrue(text.indexOf("CORRECTION-14-END") < text.indexOf("CORRECTION-13-END"))
        assertTrue(text.contains("另有 3 条更新未展开"))
    }

    @Test fun `failed durable publication does not consume the update`() = runBlocking {
        seed(); begin(); post("new", "correction")
        port.fail = true
        try { freshness.refresh("session", true); fail("expected storage failure") } catch (_: IllegalStateException) { }
        port.fail = false
        assertEquals(1, freshness.refresh("session", true))
        assertEquals(0, freshness.refresh("session", true))
        assertEquals(1, port.rows.size)
    }

    private class RecordingPort : LiveMessagePort {
        var fail = false
        val rows = mutableListOf<Pair<String, UserMessage>>()
        override suspend fun append(sessionId: String, message: HarnessMessage) {
            check(!fail) { "storage unavailable" }; rows += sessionId to message as UserMessage
        }
        override suspend fun publishPersisted(sessionId: String, message: HarnessMessage) = Unit
        override fun snapshot(sessionId: String): List<HarnessMessage> = rows.filter { it.first == sessionId }.map { it.second }
    }
}
