package top.tianyan.app.harness.redteam

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject as JsonObj
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
import top.tianyan.app.core.model.RedTeamRole
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

    @Test
    fun `asset detail fields survive into the stored payload`() = runBlocking {
        val store = FakeFacts()
        val coordinator = RedTeamCoordinator(
            store,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )

        val args = buildJsonObject {
            put("action", "asset_add")
            put("id", "10.0.0.5:443")
            put("title", "web-01")
            put("ip", "10.0.0.5")
            put("port", "443")
            put("service", "https")
            put("fingerprint", "nginx/1.24")
            put("provenance", "passive")
        }
        val (ok, _) = coordinator.execute(args, "s1")
        assertTrue(ok)

        val stored = store.recent("s1").single()
        assertEquals("10.0.0.5", stored.target)
        assertEquals("10.0.0.5:443", stored.id)
        listOf("port", "service", "fingerprint", "provenance").forEach { field ->
            assertTrue("payload lost $field -> ${stored.payload}", stored.payload.contains("\"$field\""))
        }
    }

    @Test
    fun `rewriting the same id replaces the existing fact`() = runBlocking {
        val store = FakeFacts()
        val coordinator = RedTeamCoordinator(
            store,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )

        coordinator.execute(buildJsonObject { put("action", "asset_add"); put("id", "a1"); put("title", "web-01") }, "s1")
        coordinator.execute(buildJsonObject { put("action", "asset_add"); put("id", "a1"); put("title", "web-02") }, "s1")

        assertEquals(listOf("web-02"), store.recent("s1").map { it.title })
    }

    /** schema 自身必须能干净解析，且 action 清单无重复（重复会让 provider 端校验行为不确定）。 */
    @Test
    fun `tool schema is valid and action names are unique`() {
        val names = top.tianyan.app.harness.redteam.RedTeamToolSchema.actionNames()
        assertEquals("action names must be unique", names.size, names.distinct().size)
        assertTrue(names.containsAll(listOf("asset_graph", "attack_path", "role_prompt", "poc_use", "domain_index")))

        val params = top.tianyan.app.harness.redteam.RedTeamToolSchema.parameters()
        val actionEnum = params["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.let { it as JsonArray }
            .map { it.jsonPrimitive.content }
        assertEquals(names.size, actionEnum.size)
    }

    /**
     * 契约测试：schema 对外宣告的每个 action 都必须被协调器真实处理。
     * 之前 asset_assess / asset_test 进了枚举却没有分支，模型一调就是 unsupported，
     * 这条断言把「宣告与实现漂移」钉死在测试里。
     */
    @Test
    fun `every advertised action is handled or explicitly rejected as invalid input`() = runBlocking {
        val advertised = RedTeamToolSchema.actionNames()
        assertTrue("schema advertised no actions", advertised.isNotEmpty())
        assertTrue("advertised surface shrank unexpectedly: ${advertised.size}", advertised.size >= 55)

        for (action in advertised) {
            val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
            val args = buildJsonObject {
                put("action", action)
                // 给所有条件必填字段喂最小合法值，确保走到真正的实现分支而不是前置校验。
                put("title", "probe")
                put("kind", "asset")
                put("id", "probe-id")
                put("sub_action", "status")
                put("role", "recon")
                put("src_id", "a")
                put("dst_id", "b")
                put("relation", "resolves")
            }
            val (_, message) = fresh.execute(args, "s1")
            assertFalse(
                "action '$action' is advertised but not implemented: $message",
                message.contains("unsupported redteam action"),
            )
        }
    }

    /**
     * 反向对照：证明上面那条契约断言的字符串判据真的会命中。
     * 没有这条，契约测试可能因为判据写错而永远为真。
     */
    @Test
    fun `unknown action is reported as unsupported`() = runBlocking {
        val (ok, message) = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
            .execute(call("definitely_not_a_real_action"), "s1")
        assertFalse(ok)
        assertTrue("guard string changed: $message", message.contains("unsupported redteam action"))
    }

    /**
     * 报告与评分报告必须给出同一个总分：上游为此把规则抽成唯一实现，
     * 三处各写一遍必然漂移成不同口径。
     */
    @Test
    fun `report and score report agree on the total`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        // 同一主机同端口的两条服务器权限：system 口径下只有高分那条计分。
        fresh.execute(call("score_hit", "title" to "server-host", "id" to "h1", "points" to "50",
            "code" to "server-host", "asset_id" to "1", "port" to "22", "target" to "10.0.0.5"), "s1")
        fresh.execute(call("score_hit", "title" to "server-host", "id" to "h2", "points" to "10",
            "code" to "server-host", "asset_id" to "1", "port" to "22", "target" to "10.0.0.5"), "s1")

        val (okScore, scoreOut) = fresh.execute(call("score_report"), "s1")
        assertTrue(okScore)

        val (okReport, reportOut) = fresh.execute(call("report"), "s1")
        assertTrue(okReport)

        // 只有 50 分那条计分：10 分那条被同口径压住。
        assertTrue("score report should count only the best hit: $scoreOut", scoreOut.contains("total=50"))
        assertTrue("report must embed the same total: $reportOut", reportOut.contains("total=50"))
        assertTrue("capped hit must be explained: $scoreOut", scoreOut.contains("不计分 10分"))
    }

    @Test
    fun `score points listing exposes upstream catalog`() = runBlocking {
        val (ok, out) = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
            .execute(call("score_points"), "s1")
        assertTrue(ok)
        assertTrue("missing server-host: $out", out.contains("server-host"))
        assertTrue("missing boundary-physical: $out", out.contains("boundary-physical"))
        assertEquals(
            top.tianyan.app.core.model.RedTeamScoring.DEFAULT_POINTS.size,
            top.tianyan.app.core.model.RedTeamScoring.DEFAULT_POINTS.map { it.code }.distinct().size,
        )
    }

    /** 角色 code 必须与上游 ROLE_TITLES 一致：拼错就等于该角色查不到提示词。 */
    @Test
    fun `role registry matches upstream codes`() {
        assertEquals(
            listOf("plan", "recon", "assess", "vuln-scan", "exploit", "internal"),
            RedTeamRole.entries.map { it.id },
        )
        assertEquals(
            listOf("recon", "assess", "vuln-scan", "exploit", "internal"),
            RedTeamRole.dispatchable.map { it.id },
        )
    }

    @Test
    fun `role dispatch embeds role constraint and scope and reserves a slot`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (ok, out) = fresh.execute(
            call("role_dispatch", "role" to "vuln-scan", "task" to "对 10.0.0.5 做授权验证"),
            "s1",
        )
        assertTrue(ok)
        assertTrue("dispatch must carry the role duty: $out", out.contains("漏洞发现"))
        assertTrue("dispatch must carry the task: $out", out.contains("对 10.0.0.5 做授权验证"))
        assertTrue("dispatch must carry the bound scope: $out", out.contains("10.0.0.0/24"))
        assertTrue("dispatch must carry the target: $out", out.contains("a.com"))
        assertTrue("dispatch must tell the caller to invoke_subagent: $out", out.contains("invoke_subagent"))

        // 派发即占位：不等调用方再 acquire，避免「先查名额、再派、中间被抢走」的竞态。
        val (_, slots) = fresh.execute(call("group_slot"), "s1")
        assertTrue("slot must be reserved by dispatch: $slots", slots.contains("used=1"))
    }

    @Test
    fun `role dispatch refuses the planner and unknown roles`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (planOk, planMsg) = fresh.execute(call("role_dispatch", "role" to "plan", "task" to "x"), "s1")
        assertFalse("plan is the main session and must not be dispatched", planOk)
        assertTrue("message should list dispatchable roles: $planMsg", planMsg.contains("recon"))

        val (badOk, badMsg) = fresh.execute(call("role_dispatch", "role" to "asset", "task" to "x"), "s1")
        assertFalse("'asset' is not a valid role code", badOk)
        assertTrue("message should name the unknown role: $badMsg", badMsg.contains("unknown role"))
    }

    @Test
    fun `role dispatch honours the concurrency ceiling`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        repeat(3) { index ->
            val (ok, _) = fresh.execute(call("role_dispatch", "role" to "recon", "task" to "t$index"), "s1")
            assertTrue("dispatch $index should fit", ok)
        }
        val (ok, out) = fresh.execute(call("role_dispatch", "role" to "internal", "task" to "t4"), "s1")
        // 数据层拒绝：工具调用本身成功，但 payload 里 ok=false。
        assertTrue(ok)
        assertTrue("fourth dispatch must be refused: $out", out.contains("ok=false"))
    }

    @Test
    fun `asset test keeps an append log and a blocked counter`() = runBlocking {
        val store = FakeFacts()
        val fresh = RedTeamCoordinator(
            store,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )

        fresh.execute(call("asset_test", "id" to "10.0.0.5", "status" to "testing", "test" to "nmap 全端口"), "s1")
        fresh.execute(call("asset_test", "id" to "10.0.0.5", "status" to "blocked", "test" to "nuclei cve", "surface" to "SMB 445 未测", "blocked" to "true"), "s1")

        val stored = store.recent("s1").single().payload
        // test 是追加式：两次记录都要在，不能互相覆盖。
        assertTrue("first test entry lost: $stored", stored.contains("nmap 全端口"))
        assertTrue("second test entry lost: $stored", stored.contains("nuclei cve"))
        assertTrue("blocked counter missing: $stored", stored.contains("\"blocked_count\":\"1\""))
        assertTrue("surface missing: $stored", stored.contains("SMB 445 未测"))
    }

    @Test
    fun `asset test rejects an unknown status`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (ok, message) = fresh.execute(call("asset_test", "id" to "10.0.0.5", "status" to "almost-done"), "s1")
        assertFalse(ok)
        assertTrue("should list valid statuses: $message", message.contains("no_surface"))
    }

    /**
     * 复现段：真实抓包原样给出，合成必须标注来源——
     * 推断的请求与实证在可信度上不是一回事，报告里要能一眼分辨。
     */
    @Test
    fun `replay section labels synthesized requests and preserves real ones`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        // 真实抓包：有 request 字段。
        fresh.execute(call("http_evidence_add", "id" to "e1", "title" to "越权读取用户列表",
            "request" to "GET /api/users HTTP/1.1\r\nHost: a.com\r\n\r\n"), "s1")
        // 只有 URL：需要合成。
        fresh.execute(call("http_evidence_add", "id" to "e2", "title" to "未鉴权接口",
            "target" to "http://a.com:8080/api/x", "method" to "GET"), "s1")

        val (ok, out) = fresh.execute(call("score_report"), "s1")
        assertTrue(ok)
        assertTrue("real capture must be preserved verbatim: $out", out.contains("GET /api/users HTTP/1.1"))
        assertTrue("real capture must be labelled as such: $out", out.contains("真实抓包"))
        assertTrue("synthesized must be labelled: $out", out.contains("synthesized=true"))
        assertTrue("synthesized must carry the Host header: $out", out.contains("Host: a.com:8080"))
    }

    /**
     * 体检必须真的判「能不能跑」，不能只报「有哪些技能」——
     * 缺环境变量/工具要能在这个阶段就找用户要，而不是等进了靶场才发现。
     */
    @Test
    fun `preflight reports unusable skills with fix guidance`() = runBlocking {
        val source = object : RedTeamSkillSource {
            override suspend fun redTeamSkills() = listOf(
                RedTeamSkillSource.SkillSource(
                    name = "recon-passive",
                    content = "use process.env.REDTEAM_TEST_MISSING_KEY and run \$DSH_HOME/redteam/toolkit/nonexistent-tool-xyz/bin",
                ),
            )
        }
        val fresh = RedTeamCoordinator(
            FakeFacts(),
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
            source,
        )

        val (ok, out) = fresh.execute(call("preflight"), "s1")
        assertTrue(ok)
        assertTrue("preflight must report readiness: $out", out.contains("ready=false"))
        assertTrue("preflight must count skills: $out", out.contains("skills=1"))
        assertTrue("preflight must name the missing env var: $out", out.contains("REDTEAM_TEST_MISSING_KEY"))
        assertTrue("preflight must list what the user must provide: $out", out.contains("需要你提供"))
        assertTrue("preflight must give a fix, not just a diagnosis: $out", out.contains("修法："))
    }

    @Test
    fun `preflight is ready when every skill checks out`() = runBlocking {
        val source = object : RedTeamSkillSource {
            override suspend fun redTeamSkills() = listOf(
                RedTeamSkillSource.SkillSource(name = "plain", content = "no env, no paths, no placeholders"),
            )
        }
        val fresh = RedTeamCoordinator(
            FakeFacts(),
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
            source,
        )
        val (ok, out) = fresh.execute(call("preflight"), "s1")
        assertTrue(ok)
        assertTrue("clean skill set should be ready: $out", out.contains("ready=true"))
        assertFalse("no blockers means no fix list", out.contains("不可用技能与修法"))
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