package top.tianyan.app.harness.redteam

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.database.RedTeamFactRepository
import top.tianyan.app.core.model.RedTeamFactKind
import top.tianyan.app.harness.events.HarnessEventBus

class RedTeamCoordinatorTest {
    private val events = HarnessEventBus()

    @Test
    fun `agent slots reject the fourth concurrent agent`() = runBlocking {
        val coordinator = coordinator(session("s1", redTeam = true, target = "example.com", scope = "10.0.0.0/24"))

        repeat(MAX_SLOTS) { index ->
            val (ok, out) = coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "任务$index"), "s1")
            assertTrue(ok)
            assertTrue(out.contains("ok=true"))
        }

        // Refusal is data-level (ok=false in the payload), matching the upstream slot gate.
        val (_, out) = coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "第四个"), "s1")
        assertTrue(out.contains("ok=false"))

        val (statusOk, status) = coordinator.execute(call("agent_slot", "sub_action" to "status"), "s1")
        assertTrue(statusOk)
        assertTrue(status.contains("free=0"))
    }

    @Test
    fun `released slot can be acquired again`() = runBlocking {
        val coordinator = coordinator(session("s1", redTeam = true, target = "example.com", scope = "10.0.0.0/24"))
        val (_, acquired) = coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "A"), "s1")
        val slot = acquired.lineSequence().first { it.startsWith("slot=") }.removePrefix("slot=")

        coordinator.execute(call("agent_slot", "sub_action" to "release", "key" to slot), "s1")

        val (ok, out) = coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "B"), "s1")
        assertTrue(ok)
        assertTrue(out.contains("free=${MAX_SLOTS - 1}"))
    }

    @Test
    fun `slots are isolated per session`() = runBlocking {
        val coordinator = coordinator(
            session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"),
            session("s2", redTeam = true, target = "b.com", scope = "10.1.0.0/24"),
        )
        repeat(MAX_SLOTS) { coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "A$it"), "s1") }

        val (ok, out) = coordinator.execute(call("agent_slot", "sub_action" to "acquire", "label" to "B"), "s2")
        assertTrue(ok)
        assertTrue(out.contains("free=${MAX_SLOTS - 1}"))
    }

    @Test
    fun `facts require a bound target and red-team session`() = runBlocking {
        val coordinator = coordinator(
            session("plain", redTeam = false),
            session("unbound", redTeam = true),
        )

        val (plainOk, plainOut) = coordinator.execute(
            call("vuln_add", "title" to "SQLi", "severity" to "high"),
            "plain",
        )
        assertFalse(plainOk)
        assertTrue(plainOut.contains("red-team mode is not enabled"))

        val (unboundOk, unboundOut) = coordinator.execute(
            call("vuln_add", "title" to "SQLi", "severity" to "high"),
            "unbound",
        )
        assertFalse(unboundOk)
        assertTrue(unboundOut.contains("bind a target and scope"))
    }

    @Test
    fun `vulnerability writes land in the calling session only`() = runBlocking {
        val store = FakeFacts()
        val coordinator = RedTeamCoordinator(
            store,
            FakeSessions(
                listOf(
                    session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"),
                    session("s2", redTeam = true, target = "b.com", scope = "10.1.0.0/24"),
                ),
            ),
            events,
        )

        val (ok, _) = coordinator.execute(
            call("vuln_add", "title" to "SQLi", "target" to "10.0.0.9", "severity" to "high"),
            "s1",
        )
        assertTrue(ok)

        assertEquals(listOf("SQLi"), store.recent("s1").map { it.title })
        assertEquals(RedTeamFactKind.VULNERABILITY.id, store.recent("s1").single().kind)
        assertTrue(store.recent("s2").isEmpty())
    }

    @Test
    fun `report aggregates only current session facts`() = runBlocking {
        val store = FakeFacts()
        val coordinator = RedTeamCoordinator(
            store,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )
        coordinator.execute(call("asset_add", "title" to "web-01", "target" to "10.0.0.5"), "s1")
        coordinator.execute(call("vuln_add", "title" to "弱口令", "severity" to "medium"), "s1")

        val (ok, report) = coordinator.execute(call("report"), "s1")
        assertTrue(ok)
        assertTrue(report.contains("Target: a.com"))
        assertTrue(report.contains("web-01"))
        assertTrue(report.contains("弱口令"))
        assertFalse(report.contains("\\n"))
    }

    @Test
    fun `asset query is scoped to asset kind and session`() = runBlocking {
        val store = FakeFacts()
        val coordinator = RedTeamCoordinator(
            store,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )
        coordinator.execute(call("asset_add", "title" to "web-01"), "s1")
        coordinator.execute(call("vuln_add", "title" to "SQLi"), "s1")

        val (ok, out) = coordinator.execute(call("asset_query"), "s1")
        assertTrue(ok)
        assertTrue(out.contains("web-01"))
        assertFalse(out.contains("SQLi"))
    }

    @Test
    fun `roles brief lists every red-team role`() = runBlocking {
        val coordinator = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (ok, out) = coordinator.execute(call("roles"), "s1")

        assertTrue(ok)
        listOf("recon", "asset", "vuln-scan", "exploit", "internal").forEach { role ->
            assertTrue("missing role $role", out.contains(role))
        }
    }

    private fun call(action: String, vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        put("action", action)
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun session(id: String, redTeam: Boolean, target: String? = null, scope: String = "") = HarnessSessionEntity(
        id = id,
        title = id,
        createdAt = 1L,
        updatedAt = 1L,
        modelId = null,
        redTeamMode = if (redTeam) "red_team" else "off",
        redTeamTarget = target,
        redTeamScope = scope,
    )

    private fun coordinator(vararg sessions: HarnessSessionEntity) = RedTeamCoordinator(
        FakeFacts(),
        FakeSessions(sessions.toList()),
        events,
    )

    private class FakeFacts : RedTeamFactRepository {
        private val rows = mutableListOf<RedTeamFactEntity>()
        override fun observeForSession(sessionId: String): Flow<List<RedTeamFactEntity>> =
            flowOf(rows.filter { it.sessionId == sessionId })

        override fun observeKind(sessionId: String, kind: String): Flow<List<RedTeamFactEntity>> =
            flowOf(rows.filter { it.sessionId == sessionId && it.kind == kind })

        override suspend fun recent(sessionId: String, limit: Int): List<RedTeamFactEntity> =
            rows.filter { it.sessionId == sessionId }.sortedByDescending { it.updatedAt }.take(limit)

        override suspend fun upsert(fact: RedTeamFactEntity) {
            rows.removeAll { it.sessionId == fact.sessionId && it.id == fact.id }
            rows += fact
        }

        override suspend fun upsertAll(facts: List<RedTeamFactEntity>) {
            facts.forEach { upsert(it) }
        }

        override suspend fun deleteForSession(sessionId: String) {
            rows.removeAll { it.sessionId == sessionId }
        }
    }

    private class FakeSessions(sessions: List<HarnessSessionEntity>) : HarnessSessionRepository {
        private val rows: List<HarnessSessionEntity> = sessions
        override fun observeAll(): Flow<List<HarnessSessionEntity>> = flowOf(rows)
        override suspend fun findById(id: String): HarnessSessionEntity? = rows.firstOrNull { it.id == id }
        override suspend fun upsert(session: HarnessSessionEntity) = Unit
        override suspend fun touch(id: String, updatedAt: Long) = Unit
        override suspend fun updateWorkspace(id: String, workspace: String) = Unit
        override suspend fun rename(id: String, title: String, updatedAt: Long) = Unit
        override suspend fun setApprovalMode(id: String, approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setApprovalModeForAll(approvalMode: String, updatedAt: Long) = Unit
        override suspend fun setModelSelection(id: String, modelId: String?, modelVariant: String?, updatedAt: Long) = Unit
        override suspend fun deleteSession(id: String) = Unit
        override suspend fun countInRange(start: Long?, end: Long?): Int = rows.size
        override suspend fun listAll(): List<HarnessSessionEntity> = rows
    }

    private companion object {
        const val MAX_SLOTS = 3
    }
}