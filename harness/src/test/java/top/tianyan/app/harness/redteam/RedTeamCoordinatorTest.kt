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
import top.tianyan.app.core.model.RedTeamSettings
import top.tianyan.app.harness.events.HarnessEventBus

/** 模板库文件系统接缝的短别名：用例里要反复构造匿名实现。 */
private typealias TplFs = top.tianyan.app.core.model.RedTeamNucleiIndex.TemplateFs
private typealias TplEntry = top.tianyan.app.core.model.RedTeamNucleiIndex.Entry

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
                // 新增的条件必填字段也要喂：否则 skill_* / role_prompt_save 只在前置校验里
                // 就被挡下，契约测试看着过了，实际分支从没跑过。
                put("name", "probe-skill")
                put("body", "# probe")
                put("content", "probe prompt")
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

    /** 并发上限必须真的可调：写死 3 就等于阉掉了上游最常改的一项设置。 */
    @Test
    fun `concurrency ceiling follows the configured limit`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.maxAgentsOverride = 2.0

        repeat(2) { index ->
            val (ok, out) = fresh.execute(call("role_dispatch", "role" to "recon", "task" to "t$index"), "s1")
            assertTrue(ok)
            assertFalse("dispatch $index should fit under 2: $out", out.contains("ok=false"))
        }
        val (_, refused) = fresh.execute(call("role_dispatch", "role" to "internal", "task" to "t3"), "s1")
        assertTrue("third dispatch must be refused at limit 2: $refused", refused.contains("ok=false"))

        val (_, slots) = fresh.execute(call("group_slot"), "s1")
        assertTrue("slot report must show the configured ceiling: $slots", slots.contains("max=2"))

        // 调高后立刻可以再派。
        fresh.maxAgentsOverride = 5.0
        val (_, more) = fresh.execute(call("role_dispatch", "role" to "internal", "task" to "t4"), "s1")
        assertFalse("raising the ceiling must take effect: $more", more.contains("ok=false"))
    }

    /** 上限收敛到 1..10，越界值不应把闸门撑开。 */
    @Test
    fun `max agents override is clamped`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        assertEquals(10, fresh.saveMaxAgents(99))
        assertEquals(1, fresh.saveMaxAgents(0))
        assertEquals(3, fresh.saveMaxAgents("abc"))
    }

    /** 马类型/状态拼错必须当场报错，而不是静默入库让界面判定失效。 */
    @Test
    fun `webshell writes reject invalid type and status`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (badTypeOk, badType) = fresh.execute(
            call("webshell_add", "title" to "shell", "shell_type" to "totally-unknown"), "s1",
        )
        assertFalse("unknown shell_type must be refused", badTypeOk)
        assertTrue("error should explain the accepted values: $badType", badType.contains("custom"))

        val (badStatusOk, badStatus) = fresh.execute(
            call("webshell_add", "title" to "shell", "status" to "almost-online"), "s1",
        )
        assertFalse("unknown shell status must be refused", badStatusOk)
        assertTrue("error should list valid statuses: $badStatus", badStatus.contains("offline"))

        // 中文别名与合法状态应当放行。
        val (okAlias, _) = fresh.execute(
            call("webshell_add", "title" to "shell", "shell_type" to "冰蝎", "status" to "up"), "s1",
        )
        assertTrue("chinese alias must be accepted", okAlias)
    }

    /**
     * 报告必须按五个作战阶段分桶：演练方就是按这条推进线读的。
     * 一条靶标得分落进互联网侧桶，整份报告的叙事就废了——而且不会报错。
     */
    @Test
    fun `score report buckets hits by engagement stage`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        // 显式阶段、类型特判（靶标系统）、内网地址兜底各来一条。
        fresh.execute(call("score_hit", "title" to "h1", "code" to "weak-password", "points" to "100", "stage_code" to "boundary"), "s1")
        fresh.execute(call("score_hit", "title" to "h2", "code" to "mail-admin", "points" to "200", "target" to "203.0.113.5"), "s1")
        fresh.execute(call("score_hit", "title" to "h3", "code" to "weak-password", "points" to "50", "target" to "10.1.2.3"), "s1")

        val (ok, out) = fresh.execute(call("score_report"), "s1")
        assertTrue(ok)
        assertTrue("report must show stage progress: $out", out.contains("## 阶段进度"))
        // 靶标系统必须落在靶标桶，而不是它地址所在的互联网侧。
        assertTrue("mail system must land in target stage: $out", out.contains("靶标权限（target）1 条"))
        assertTrue("explicit boundary stage must be honored: $out", out.contains("边界突破（boundary）1 条"))
        assertTrue("private ip must land in internal stage: $out", out.contains("内网资产权限（internal）1 条"))
        // 前置阶段如实标出不计分。
        assertTrue("recon must be flagged as unscored: $out", out.contains("前置阶段不计分"))
    }

    /** 资产登记为外网、但得分点是演练靶标系统时，仍须落在靶标桶。 */
    @Test
    fun `engagement target scores stay in target stage despite external asset`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "0.0.0.0/0"))
        val (assetOk, assetOut) = fresh.execute(
            call("asset_add", "title" to "mail", "target" to "203.0.113.5", "id" to "77"), "s1",
        )
        assertTrue(assetOut, assetOk)
        fresh.execute(
            call("score_hit", "title" to "mail admin", "code" to "mail-admin", "points" to "300", "asset_id" to "77", "target" to "203.0.113.5"),
            "s1",
        )
        val (_, out) = fresh.execute(call("score_report"), "s1")
        assertTrue("target system score must not fall into the internet bucket: $out", out.contains("靶标权限（target）1 条"))
    }

    /** 非法阶段码不能静默丢桶：退回老 stage 并明确告警。 */
    @Test
    fun `invalid chain stage falls back with a warning in the write path`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (ok, out) = fresh.execute(
            call("chain_add", "title" to "step", "stage_code" to "external", "stage" to "access"), "s1",
        )
        assertTrue(ok)
        assertTrue("must warn about the bad stage code: $out", out.contains("warning="))
        assertTrue("warning must quote the rejected value: $out", out.contains("external"))
        // 归一后的码要落库，供报告分桶。
        assertTrue("normalized stage must be reported: $out", out.contains("阶段 internal"))
    }

    @Test
    fun `valid chain stage passes without warning`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (ok, out) = fresh.execute(call("chain_add", "title" to "step", "stage_code" to "boundary"), "s1")
        assertTrue(ok)
        assertFalse("valid stage code needs no warning: $out", out.contains("warning="))
        assertTrue(out, out.contains("阶段 boundary"))
    }

    /**
     * 攻击文件必须真落盘：上游这一块的意义是「供复用与交付」，
     * 只记一条元数据事实的话，交付时手上什么都没有。
     */
    @Test
    fun `attack files are written to disk and can be read back`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("rt-attack").toFile()
        try {
            val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
            fresh.filesRootOverride = root.path

            val (ok, out) = fresh.execute(
                call(
                    "attack_file_add", "title" to "cve-2021-22893.sh", "target" to "10.0.0.5",
                    "name" to "cve-2021-22893.sh", "evidence" to "回显 uid=0",
                    "content" to "#!/bin/sh\nid\n",
                ),
                "s1",
            )
            assertTrue(out, ok)
            assertTrue("must report the on-disk path: $out", out.contains("已落盘"))

            val written = java.io.File(root, "engagements/s1/attack-files/10.0.0.5/cve-2021-22893.sh")
            assertTrue("file must exist at the guarded path: ${written.path}", written.isFile)
            assertEquals("#!/bin/sh\nid\n", written.readText())

            val id = out.substringAfter("事实 ").substringBefore(" ·").trim()
            val (readOk, readOut) = fresh.execute(call("read_attack_file", "id" to id), "s1")
            assertTrue(readOut, readOk)
            assertTrue("content must round trip: $readOut", readOut.contains("uid=0") || readOut.contains("id"))
        } finally {
            root.deleteRecursively()
        }
    }

    /** 证据必填：没打通的脚本不该进交付目录。 */
    @Test
    fun `attack file without evidence is refused`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("rt-attack2").toFile()
        try {
            val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
            fresh.filesRootOverride = root.path
            val (ok, out) = fresh.execute(
                call("attack_file_add", "title" to "x.sh", "target" to "10.0.0.5", "name" to "x.sh", "content" to "echo hi"),
                "s1",
            )
            assertFalse("missing evidence must be refused", ok)
            assertTrue("error should explain why: $out", out.contains("evidence"))
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * 这条是安全断言：库里存的 path 是智能体写过的，不可信。
     * 越界必须拒绝，**不能静默把文件内容读出来**。
     */
    @Test
    fun `reading an attack file outside the engagement root is refused`() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("rt-attack3").toFile()
        try {
            val fakeFacts = FakeFacts()
            val fresh = RedTeamCoordinator(
                fakeFacts,
                FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
                events,
            )
            fresh.filesRootOverride = root.path
            val secret = java.io.File(root, "outside-secret.txt")
            secret.writeText("TOP-SECRET")

            // 直接往库里种一条 path 指向靶标目录之外的事实，模拟被污染的库。
            // 不能走 attack_file_add：写入侧会把 path 重写成服务端算出的合法路径，
            // 那样读到的就是合法文件，这条断言会「因为没读到机密」而假通过。
            val poisoned = RedTeamFactEntity(
                sessionId = "s1",
                id = "poisoned-1",
                kind = "attack_file",
                title = "leak.sh",
                target = "10.0.0.5",
                payload = """{"name":"leak.sh","path":"${secret.path}"}""",
                createdAt = 1L,
                updatedAt = 1L,
            )
            fakeFacts.upsert(poisoned)

            val (_, readOut) = fresh.execute(call("read_attack_file", "id" to "poisoned-1"), "s1")
            // 必须明确拒绝，并且**不能**把内容带出来。
            assertFalse("outside path must not be read: $readOut", readOut.contains("TOP-SECRET"))
            assertTrue("refusal must be explicit rather than a silent empty result: $readOut", readOut.contains("拒绝读取"))

            // 反向对照：把同一条事实的 path 改到靶标目录内，读取必须成功。
            // 没有这一步，上面那条断言在「防护根本没跑」时也会通过。
            val inside = java.io.File(root, "engagements/s1/attack-files/10.0.0.5/leak.sh")
            inside.parentFile.mkdirs()
            inside.writeText("INSIDE-CONTENT")
            fakeFacts.upsert(poisoned.copy(payload = """{"name":"leak.sh","path":"${inside.path}"}"""))
            val (insideOk, insideOut) = fresh.execute(call("read_attack_file", "id" to "poisoned-1"), "s1")
            assertTrue("reading inside the engagement root must work: $insideOut", insideOk)
            assertTrue("content must be returned for an allowed path: $insideOut", insideOut.contains("INSIDE-CONTENT"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `poc delete removes the record`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (addOk, addOut) = fresh.execute(call("poc_add", "title" to "cve-x", "code" to "CVE-X", "content" to "p"), "s1")
        assertTrue(addOut, addOk)

        val (ok, out) = fresh.execute(call("poc_delete", "code" to "CVE-X"), "s1")
        assertTrue(out, ok)
        assertTrue("delete must report the removed record: $out", out.contains("deleted="))

        val (_, listOut) = fresh.execute(call("poc_list"), "s1")
        assertFalse("deleted poc must be gone: $listOut", listOut.contains("CVE-X"))
    }

    /**
     * 自建得分点必须真的参与计分，不能只是「列表里看得见」。
     * 只存不合并的话，界面能新增、算分时当不存在——这比没有这个功能更难查。
     */
    @Test
    fun `custom score points actually change the total`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (saveOk, saveOut) = fresh.execute(
            call("score_point_save", "code" to "custom-flag", "name" to "自定义成果", "points" to "77"),
            "s1",
        )
        assertTrue(saveOut, saveOk)
        assertTrue("must report the created code: $saveOut", saveOut.contains("custom-flag"))

        // 命中不自带分值 → 应当走自建点的 77 分。
        fresh.execute(call("score_hit", "title" to "h1", "code" to "custom-flag"), "s1")
        val (_, report) = fresh.execute(call("score_report"), "s1")
        assertTrue("custom point value must be applied: $report", report.contains("total=77"))

        val (_, list) = fresh.execute(call("score_points"), "s1")
        assertTrue("custom point must be listed: $list", list.contains("自定义成果"))
    }

    /** 内置点只能停用：改分值会被锁定，且停用后必须真的不计分。 */
    @Test
    fun `builtin score points are locked and disabling them stops scoring`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (ok, out) = fresh.execute(
            call("score_point_save", "code" to "domain-control", "name" to "改名尝试", "points" to "9999"),
            "s1",
        )
        assertTrue(out, ok)
        assertTrue("builtin must report locked fields: $out", out.contains("locked_fields="))
        assertTrue("builtin must stay enabled-neutral: $out", out.contains("builtin=true"))

        // 分值没有被改掉：命中的分值仍按规则目录。
        fresh.execute(call("score_hit", "title" to "d", "code" to "domain-control"), "s1")
        val (_, before) = fresh.execute(call("score_report"), "s1")
        assertTrue("locked value must remain the rule value: $before", before.contains("total=50"))

        // 停用后不再计分。
        fresh.execute(call("score_point_save", "code" to "domain-control", "enabled" to "false"), "s1")
        val (_, after) = fresh.execute(call("score_report"), "s1")
        assertTrue("disabled point must stop scoring: $after", after.contains("total=0"))
    }

    /** 内置点不能删除：删掉会让规则表缺一条。 */
    @Test
    fun `builtin score points cannot be deleted`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (ok, out) = fresh.execute(call("delete_score_point", "code" to "domain-control"), "s1")
        assertFalse("builtin deletion must be refused", ok)
        assertTrue("error should explain why: $out", out.contains("停用"))
    }

    @Test
    fun `custom score points can be deleted`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(call("score_point_save", "code" to "custom-x", "name" to "临时点", "points" to "10"), "s1")
        val (ok, out) = fresh.execute(call("delete_score_point", "code" to "custom-x"), "s1")
        assertTrue(out, ok)
        val (_, list) = fresh.execute(call("score_points"), "s1")
        assertFalse("deleted custom point must be gone: $list", list.contains("临时点"))
    }

    /**
     * 阶段编辑是**部分补丁**：没传的字段必须沿用当前值。
     * 少了这层回落，界面上只改一句「目标」就会把这一阶段的名称、手段分组、工具全清空。
     */
    @Test
    fun `stage edits are partial patches`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))

        val (ok, out) = fresh.execute(call("save_stage", "code" to "recon", "goal" to "只改目标"), "s1")
        assertTrue(out, ok)
        assertTrue("goal must be updated: $out", out.contains("sections=3"))

        val (_, list) = fresh.execute(call("stages"), "s1")
        assertTrue("edited goal must show: $list", list.contains("只改目标"))
        // 关键：名称与手段分组不能被清空。
        assertTrue("name must survive a partial patch: $list", list.contains("信息收集"))
        assertTrue("sections must survive a partial patch: $list", list.contains("资产测绘"))
        assertTrue("tools must survive a partial patch: $list", list.contains("fscan"))
        assertTrue("stage must be marked edited: $list", list.contains("已编辑"))
    }

    @Test
    fun `stages list keeps the five engagement stages in order`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (_, out) = fresh.execute(call("stages"), "s1")
        assertTrue("must report five stages: $out", out.contains("count=5"))
        val order = listOf("信息收集", "互联网资产权限", "边界突破", "内网资产权限", "靶标权限")
        val positions = order.map { out.indexOf(it) }
        assertTrue("every stage must be present: $out", positions.all { it >= 0 })
        assertEquals("stage order must follow the engagement flow", positions.sorted(), positions)
    }

    /** 自建阶段要能新增并出现在链路里。 */
    @Test
    fun `custom stages can be added`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        val (ok, out) = fresh.execute(
            call("save_stage", "code" to "reporting", "name" to "复盘归档", "goal" to "固化成果"),
            "s1",
        )
        assertTrue(out, ok)
        val (_, list) = fresh.execute(call("stages"), "s1")
        assertTrue("custom stage must be listed: $list", list.contains("复盘归档"))
        assertTrue("custom stage adds to the count: $list", list.contains("count=6"))
    }

    /**
     * 本机模板库检索：只抽 path/name/severity/tags 四个字段，命中即返回。
     * 用注入的假文件系统固定内容——真实机器上装没装 nuclei 模板不该决定单测成败。
     */
    @Test
    fun `template search matches name tags and cve from the local index`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.filesRootOverride = "/rt"
        fresh.templateFsOverride = fakeTemplateFs(
            mapOf(
                "http/cves/2021/CVE-2021-44228.yaml" to """
                    id: CVE-2021-44228
                    info:
                      name: Log4Shell
                      severity: critical
                      tags: cve,rce,log4j
                """.trimIndent(),
                "http/misconfiguration/nginx-status.yaml" to """
                    info:
                      name: Nginx Status
                      severity: low
                      tags: nginx,exposure
                """.trimIndent(),
            ),
        )

        val (ok, out) = fresh.execute(call("template_search", "query" to "log4j"), "s1")
        assertTrue(out, ok)
        assertTrue("hit must be returned: $out", out.contains("CVE-2021-44228.yaml"))
        assertTrue("severity must be surfaced: $out", out.contains("critical"))
        assertFalse("non-matching template must be filtered out: $out", out.contains("nginx-status.yaml"))

        // 空查询只报总数不列条目（上游语义）：界面据此显示「库里有 N 个模板」。
        val (_, empty) = fresh.execute(call("template_search", "query" to ""), "s1")
        assertTrue("empty query must report total: $empty", empty.contains("total=2"))
        assertTrue("empty query must not list entries: $empty", empty.contains("returned=0"))
    }

    /** CVE 计数走文件名：上游 templateStats 的 cve 字段就是按路径里的 CVE 编号数的。 */
    @Test
    fun `template stats counts templates and cve entries`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.filesRootOverride = "/rt"
        fresh.templateFsOverride = fakeTemplateFs(
            mapOf(
                "http/cves/2021/CVE-2021-44228.yaml" to "info:\n  name: Log4Shell\n",
                "http/cves/2022/CVE-2022-1388.yaml" to "info:\n  name: F5 iControl\n",
                "http/exposures/git-config.yaml" to "info:\n  name: Git Config\n",
            ),
        )

        val (ok, out) = fresh.execute(call("template_stats"), "s1")
        assertTrue(out, ok)
        assertTrue("total must count every yaml: $out", out.contains("total=3"))
        assertTrue("cve must count only CVE-named paths: $out", out.contains("cve=2"))
    }

    /** 没装模板库不是错误：检索不可用要说清楚，而不是抛异常让整轮工具调用失败。 */
    @Test
    fun `template search reports a missing index instead of failing`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.filesRootOverride = "/rt"
        fresh.templateFsOverride = fakeTemplateFs(emptyMap(), dirs = emptySet())

        val (ok, out) = fresh.execute(call("template_search", "query" to "log4j"), "s1")
        assertTrue("missing template dir is data, not a failure: $out", ok)
        assertTrue("dir must be reported as absent: $out", out.contains("dir=-"))
        assertTrue("the message must say how to fix it: $out", out.contains("nuclei-templates"))
    }

    /**
     * 索引缓存：第二次调用不得重建（否则 13k 个 yaml 每次工具调用都重读一遍盘）。
     * 断言的是读盘次数，不是耗时——耗时在 CI 上不稳定。
     */
    @Test
    fun `template index is cached across calls`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.filesRootOverride = "/rt"
        val fs = countingTemplateFs(
            mapOf("http/cves/2021/CVE-2021-44228.yaml" to "info:\n  name: Log4Shell\n"),
        )
        fresh.templateFsOverride = fs

        fresh.execute(call("template_search", "query" to "log4shell"), "s1")
        val afterFirst = fs.listCalls
        fresh.execute(call("template_search", "query" to "log4shell"), "s1")

        assertEquals("the second call must reuse the cached index", afterFirst, fs.listCalls)
        assertTrue("the first call must actually walk the tree: $afterFirst", afterFirst > 0)
    }

    /** 模板库是靶标无关的本机资源，但红队工具不该在非红队会话里应答。 */
    @Test
    fun `template search requires red-team mode`() = runBlocking {
        val fresh = coordinator(session("plain", redTeam = false))
        fresh.templateFsOverride = fakeTemplateFs(mapOf("a.yaml" to "info:\n  name: A\n"))

        val (ok, out) = fresh.execute(call("template_search", "query" to "a"), "plain")
        assertFalse("non-red-team session must be refused: $out", ok)
        assertTrue("refusal must name the mode gate: $out", out.contains("red-team mode"))
    }

    /**
     * 连通性实测：WebShell 走 HTTP、隧道走 TCP，结果必须写回**同一条**事实
     * （面板与智能体读的是同一份数据，另开一条记录会让两边各看各的）。
     */
    @Test
    fun `probe sessions records status latency and note on the same fact`() = runBlocking {
        val facts = FakeFacts()
        val fresh = RedTeamCoordinator(
            facts,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )
        fresh.execute(
            call("webshell_add", "title" to "shell-1", "target" to "http://10.0.0.5/x.jsp", "id" to "ws1"),
            "s1",
        )
        fresh.execute(
            call("tunnel_add", "title" to "tun-1", "listen" to "socks5://127.0.0.1:1080", "id" to "tn1"),
            "s1",
        )
        fresh.sessionProbeOverride = object : RedTeamSessionProbe {
            override fun http(url: String, timeoutMs: Int) = "online" to "HTTP 200"
            override fun tcp(host: String, port: Int, timeoutMs: Int) = "active" to ""
        }

        val (ok, out) = fresh.execute(call("probe_sessions"), "s1")
        assertTrue(out, ok)
        assertTrue("webshell must be counted online: $out", out.contains("webshells=1 online=1"))
        assertTrue("tunnel must be counted active: $out", out.contains("tunnels=1 active=1"))

        // 关键：写回原事实而不是新增记录——id 必须还是 ws1/tn1，条数不变。
        val rows = facts.recent("s1", 100)
        assertEquals("probe must not add new facts", 2, rows.size)
        val shell = rows.first { it.id == "ws1" }
        assertTrue("status must be persisted: ${shell.payload}", shell.payload.contains("\"status\":\"online\""))
        assertTrue("note must be persisted: ${shell.payload}", shell.payload.contains("HTTP 200"))
        assertTrue("latency must be persisted: ${shell.payload}", shell.payload.contains("latency_ms"))
        assertTrue("last_check must be persisted: ${shell.payload}", shell.payload.contains("last_check"))
    }

    /**
     * 探不通也必须落库：上游把失败原因写进 check_note，
     * 否则面板只会显示「上次探测过」而看不出是超时还是拒绝。
     */
    @Test
    fun `probe sessions persists failure reasons`() = runBlocking {
        val facts = FakeFacts()
        val fresh = RedTeamCoordinator(
            facts,
            FakeSessions(listOf(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))),
            events,
        )
        fresh.execute(call("webshell_add", "title" to "shell-1", "target" to "http://10.0.0.5/x.jsp", "id" to "ws1"), "s1")
        // 隧道只给裸端口：host 回落 127.0.0.1，端口仍要能解析出来。
        fresh.execute(call("tunnel_add", "title" to "tun-1", "listen" to "1080", "id" to "tn1"), "s1")
        fresh.sessionProbeOverride = object : RedTeamSessionProbe {
            override fun http(url: String, timeoutMs: Int) = "offline" to "超时 >${timeoutMs}ms"
            override fun tcp(host: String, port: Int, timeoutMs: Int) =
                if (host == "127.0.0.1" && port == 1080) "down" to "连接被拒绝" else "down" to "解析错了"
        }

        val (ok, out) = fresh.execute(call("probe_sessions", "timeout_ms" to "500"), "s1")
        assertTrue(out, ok)
        assertTrue("timeout must be clamped to the 1s floor: $out", out.contains("timeout_ms=1000"))
        assertTrue("refusal reason must be reported: $out", out.contains("tunnels=1 active=0"))
        val shell = facts.recent("s1", 100).first { it.id == "ws1" }
        assertTrue("timeout note must be persisted: ${shell.payload}", shell.payload.contains("超时 >1000ms"))
    }

    /** 没登记任何会话时给出补录指引，而不是一条空结果。 */
    @Test
    fun `probe sessions explains how to register sessions when there are none`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.sessionProbeOverride = object : RedTeamSessionProbe {
            override fun http(url: String, timeoutMs: Int) = "online" to ""
            override fun tcp(host: String, port: Int, timeoutMs: Int) = "active" to ""
        }
        val (ok, out) = fresh.execute(call("probe_sessions"), "s1")
        assertTrue(out, ok)
        assertTrue("must point at the registration tools: $out", out.contains("webshell_add"))
    }

    /**
     * 靶标总览（上游 snapshot）：元信息、事实计数、测试态、网段一次给齐。
     * 缺任一块，面板首屏或报告开头就要再跑一次工具调用。
     */
    @Test
    fun `snapshot bundles engagement stats tests and segments`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(call("asset_add", "title" to "web-01", "target" to "10.0.0.5", "id" to "a1"), "s1")
        fresh.execute(call("asset_add", "title" to "web-02", "target" to "10.0.0.6", "id" to "a2"), "s1")
        fresh.execute(call("asset_test", "id" to "a1", "status" to "tested", "test" to "已验证"), "s1")

        val (ok, out) = fresh.execute(call("snapshot"), "s1")
        assertTrue(out, ok)
        assertTrue("target must be present: $out", out.contains("target=a.com"))
        assertTrue("scope must be present: $out", out.contains("scope=10.0.0.0/24"))
        // 资产测试是就地更新同一条资产事实，不该多出一条。
        assertTrue("facts must be counted without duplicates: $out", out.contains("facts=2"))
        assertTrue("assets must be counted: $out", out.contains("assets=2"))
        assertTrue("test status must be broken out: $out", out.contains("tested=1"))
        assertTrue("untested must be broken out: $out", out.contains("untested=1"))
        // 两台机器同属 10.0.0.0/24，网段必须收敛成一条而不是两条。
        assertTrue("segments must be aggregated: $out", out.contains("## 网段（1）"))
        assertTrue("segment cidr must be listed: $out", out.contains("10.0.0.0/24 assets=2"))
    }

    /** 面板首屏（上游 bootstrap）：数据根 + 当前靶标 + 靶标清单。 */
    @Test
    fun `bootstrap lists red team engagements and the current one`() = runBlocking {
        val fresh = coordinator(
            session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"),
            session("s2", redTeam = true, target = "b.com", scope = "10.1.0.0/24"),
            session("plain", redTeam = false),
        )
        val (ok, out) = fresh.execute(call("bootstrap"), "s1")
        assertTrue(out, ok)
        assertTrue("current must be the calling session: $out", out.contains("current=s1"))
        // 非红队会话不该出现在靶标清单里。
        assertTrue("red-team engagements must be listed: $out", out.contains("engagements=2"))
        assertTrue("s1 must be listed: $out", out.contains("s1 | a.com"))
        assertTrue("s2 must be listed: $out", out.contains("s2 | b.com"))
        assertFalse("non-red-team sessions must be excluded: $out", out.contains("plain |"))
    }

    /**
     * 上游 pocSearch 一次返回「本机 POC + 本机 nuclei 模板」。
     * 少了模板那一段，模型会以为本机没有现成 POC 而白跑一次互联网检索。
     */
    @Test
    fun `poc search also returns local nuclei templates`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.filesRootOverride = "/rt"
        fresh.templateFsOverride = fakeTemplateFs(
            mapOf("http/cves/2021/CVE-2021-44228.yaml" to "info:\n  name: Log4Shell\n  severity: critical\n"),
        )
        fresh.execute(call("poc_add", "title" to "自研 EXP", "code" to "EXP-1", "content" to "payload"), "s1")

        // 查询词走 cve 字段（模型最常见的填法），不是 q。
        val (ok, out) = fresh.execute(call("poc_search", "cve" to "2021-44228"), "s1")
        assertTrue(out, ok)
        assertTrue("saved poc must still be listed: $out", out.contains("自研 EXP"))
        assertTrue("template section must be appended: $out", out.contains("本机 nuclei 模板"))
        assertTrue("matching template must be returned: $out", out.contains("CVE-2021-44228.yaml"))
    }

    /** 漏洞统计：分桶之外，「有证据」要认证据表里的 vuln_id，而不只是漏洞自己的 evidence 字段。 */
    @Test
    fun `vuln stats counts evidence from the evidence table`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(
            call("vuln_add", "title" to "log4shell", "id" to "v1", "target" to "http://a.com/x", "severity" to "critical", "gained" to "shell"),
            "s1",
        )
        fresh.execute(
            call("vuln_add", "title" to "info leak", "id" to "v2", "target" to "http://a.com/y", "severity" to "low"),
            "s1",
        )
        // 证据只挂在 v1 上：v2 不该被算成「有证据」。
        fresh.execute(
            call("http_evidence_add", "title" to "200 OK", "id" to "e1", "vuln_id" to "v1", "request" to "GET /x HTTP/1.1"),
            "s1",
        )

        // 再补一条不同主机的漏洞：targetGroups 是「按站点归并」的组数，
        // 同一站点的不同路径必须收敛成一组（上游 targetKey 会剥掉 path）。
        fresh.execute(
            call("vuln_add", "title" to "other host", "id" to "v3", "target" to "http://b.com/z", "severity" to "medium"),
            "s1",
        )

        val (ok, out) = fresh.execute(call("vuln_stats"), "s1")
        assertTrue(out, ok)
        assertTrue("total must count all three: $out", out.contains("total=3"))
        assertTrue("severity buckets must be present: $out", out.contains("critical=1") && out.contains("low=1"))
        assertTrue("medium must be bucketed too: $out", out.contains("medium=1"))
        assertTrue("gained must be counted: $out", out.contains("withGained=1"))
        assertTrue("evidence must be counted via the evidence table: $out", out.contains("withEvidence=1"))
        // a.com 的两条路径归成一组，b.com 另算一组。
        assertTrue("target groups must collapse by site: $out", out.contains("targetGroups=2"))
    }

    /** HTTP 证据清单：按 vuln_id 过滤，默认只给摘要行。 */
    @Test
    fun `http evidence list filters by vuln and defaults to a summary`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(
            call("http_evidence_add", "title" to "ev-1", "id" to "e1", "vuln_id" to "v1", "request" to "GET /x HTTP/1.1\nHost: a.com\n\nBODY-SECRET"),
            "s1",
        )
        fresh.execute(
            call("http_evidence_add", "title" to "ev-2", "id" to "e2", "vuln_id" to "v2", "request" to "GET /y HTTP/1.1"),
            "s1",
        )

        val (ok, out) = fresh.execute(call("http_evidence_list", "vuln_id" to "v1"), "s1")
        assertTrue(out, ok)
        assertTrue("only the matching evidence must be listed: $out", out.contains("count=1"))
        assertTrue("the summary line must be shown: $out", out.contains("GET /x HTTP/1.1"))
        assertFalse("full body must not be dumped by default: $out", out.contains("BODY-SECRET"))
        assertFalse("other vuln evidence must be filtered out: $out", out.contains("ev-2"))

        // full=true 才给完整报文。
        val (_, full) = fresh.execute(call("http_evidence_list", "vuln_id" to "v1", "full" to "true"), "s1")
        assertTrue("full request must be included on demand: $full", full.contains("BODY-SECRET"))
    }

    /**
     * 计分链归因：显式 step_id 最可信，其次同资产同得分点，最后才是「同资产最近一步」的推断——
     * 推断必须标出来，否则报告里「怎么拿到的」经不起复盘。
     */
    @Test
    fun `score chain attributes hits to steps and marks inference`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(call("asset_add", "title" to "web-01", "target" to "10.0.0.5", "id" to "10.0.0.5"), "s1")
        fresh.execute(call("chain_add", "title" to "显式步骤", "id" to "st1", "target" to "10.0.0.5"), "s1")
        fresh.execute(call("chain_add", "title" to "被推断的步骤", "id" to "st2", "target" to "10.0.0.5"), "s1")
        fresh.execute(call("score_hit", "title" to "POINT-A", "id" to "h1", "target" to "10.0.0.5", "step_id" to "st1"), "s1")
        fresh.execute(call("score_hit", "title" to "POINT-B", "id" to "h2", "target" to "10.0.0.5"), "s1")

        val (ok, out) = fresh.execute(call("score_chain"), "s1")
        assertTrue(out, ok)
        assertTrue("both hits must be listed: $out", out.contains("count=2"))
        assertTrue("explicit step must be attributed: $out", out.contains("归因=显式步骤"))
        assertTrue("fallback attribution must be marked as inferred: $out", out.contains("归因=推断"))
    }

    /** 知识库统计：按归类/来源/组件分桶，面板据此分组。 */
    @Test
    fun `poc stats buckets knowledge by category and source`() = runBlocking {
        val fresh = coordinator(session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        fresh.execute(
            call("poc_add", "title" to "exp-1", "id" to "p1", "code" to "EXP-1", "category" to "rce", "source" to "self", "component" to "log4j", "verified" to "1"),
            "s1",
        )
        fresh.execute(
            call("poc_add", "title" to "exp-2", "id" to "p2", "code" to "EXP-2", "category" to "rce", "source" to "web", "component" to "log4j"),
            "s1",
        )
        fresh.execute(call("poc_add", "title" to "exp-3", "id" to "p3", "code" to "EXP-3", "category" to "lfi"), "s1")

        val (ok, out) = fresh.execute(call("poc_stats"), "s1")
        assertTrue(out, ok)
        assertTrue("total must count all pocs: $out", out.contains("total=3"))
        assertTrue("verified must be counted: $out", out.contains("verified=1"))
        assertTrue("categories must be bucketed: $out", out.contains("rce=2") && out.contains("lfi=1"))
        // 未写 source 的那条按上游默认归到 self。
        assertTrue("sources must be bucketed with a default: $out", out.contains("self=2") && out.contains("web=1"))
        assertTrue("components must be tallied: $out", out.contains("log4j=2"))
    }

    /**
     * 假模板库：相对路径 → 文件头内容；只有 `dirs` 里的目录才被认作模板库根。
     * 键写成相对路径（`http/cves/x.yaml`）是为了让用例读起来像模板库的目录结构，
     * 内部统一补成绝对路径——真实 `TemplateFs` 收的就是绝对路径。
     */
    private fun fakeTemplateFs(files: Map<String, String>, dirs: Set<String> = setOf(TPL_ROOT)): TplFs =
        object : TplFs {
            private val abs = files.entries.associate { (k, v) -> absolute(k) to v }

            override fun isDirectory(path: String): Boolean = path in dirs

            override fun list(path: String): List<TplEntry> = children(abs.keys, path)

            override fun readHead(path: String, maxChars: Int): String? = abs[path]?.take(maxChars)
        }

    /** 与上面同构，但统计读盘次数，用来钉住缓存行为。 */
    private fun countingTemplateFs(files: Map<String, String>) =
        object : TplFs {
            private val abs = files.entries.associate { (k, v) -> absolute(k) to v }
            var listCalls = 0

            override fun isDirectory(path: String): Boolean = path == TPL_ROOT

            override fun list(path: String): List<TplEntry> {
                listCalls++
                return children(abs.keys, path)
            }

            override fun readHead(path: String, maxChars: Int): String? = abs[path]?.take(maxChars)
        }

    private fun absolute(path: String): String = if (path.startsWith("/")) path else "$TPL_ROOT/$path"

    /** 目录列举：把绝对路径集合投影成「当前层有哪些子项、哪个是目录」。 */
    private fun children(paths: Set<String>, path: String): List<TplEntry> {
        val prefix = path.trimEnd('/') + "/"
        val seen = linkedMapOf<String, Boolean>()
        paths.forEach { file ->
            if (!file.startsWith(prefix)) return@forEach
            val rest = file.removePrefix(prefix)
            val head = rest.substringBefore('/')
            seen[head] = seen[head] == true || rest.contains('/')
        }
        return seen.map { (name, isDir) -> TplEntry(name, isDir) }
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

    /**
     * 共享同一份事实库的构造器：验证「保存后重启仍在」必须换一个协调器实例去读，
     * 用同一个实例读等于在测内存缓存，测不出有没有落库。
     */
    private fun coordinatorOn(
        facts: RedTeamFactRepository,
        vararg sessions: HarnessSessionEntity,
        skillStore: RedTeamSkillStore = RedTeamSkillStore.Unsupported,
    ) = RedTeamCoordinator(
        facts,
        FakeSessions(sessions.toList()),
        events,
        RedTeamSkillSource.Empty,
        skillStore,
    )

    /** 内存技能库：只记状态，不碰 Room，用来验证四个技能动作的分支行为。 */
    private class FakeSkillStore : RedTeamSkillStore {
        private val rows = linkedMapOf<String, RedTeamSkillStore.Skill>()
        var failNextSave = false

        override suspend fun list() = rows.values.toList()

        override suspend fun read(id: String) = rows[id]

        override suspend fun save(skill: RedTeamSkillStore.Skill): RedTeamSkillStore.Skill {
            if (failNextSave) error("磁盘满了")
            val id = skill.id.takeIf { it.isNotBlank() } ?: "custom_${rows.size + 1}"
            val stored = skill.copy(id = id)
            rows[id] = stored
            return stored
        }

        override suspend fun delete(id: String) = rows.remove(id) != null

        fun seed(skill: RedTeamSkillStore.Skill) {
            rows[skill.id] = skill
        }
    }

    /**
     * import_bundle 的 segments 必须落库。
     *
     * 原来这里只 `segments++` 计数，行被直接丢掉——上游 #upsertSegment 是写进 segment 表的，
     * 面板左侧栏的「归属组织」就从那里读。丢行不影响计数，所以看不出异常，
     * 只是导入的网段归属信息全部静默消失。
     */
    @Test
    fun `import bundle persists segment metadata`() = runBlocking {
        val store = FakeFacts()
        val session = session("s1", redTeam = true, target = "10.0.0.0/24", scope = "10.0.0.0/24")
        val bundle = """
            {"segments":[{"cidr":"10.0.0.0/24","org":"示例科技有限公司","asn":"AS64500","country":"CN"},
                         {"cidr":"10.0.1.0/24"}],
             "assets":[{"id":"10.0.0.5","ip":"10.0.0.5","ports":[{"port":443,"state":"open"}]}]}
        """.trimIndent()
        val (ok, out) = coordinatorOn(store, session).execute(
            call("import_bundle", "bundle" to bundle),
            "s1",
        )
        assertTrue(out, ok)
        assertTrue("segments must be counted: $out", out.contains("segments=2"))

        val segments = store.recent("s1").filter { it.kind == "segment" }
        assertEquals("每个网段都要落一条事实", 2, segments.size)
        val first = segments.first { it.id == "segment:10.0.0.0/24" }
        assertTrue("org must be persisted: ${first.payload}", first.payload.contains("示例科技有限公司"))
        assertTrue("asn must be persisted: ${first.payload}", first.payload.contains("AS64500"))
    }

    /** 后一次导入没带 org 时不能把先前的抹掉（上游 COALESCE(excluded.org, segment.org)）。 */
    @Test
    fun `import bundle keeps prior segment metadata when the new row omits it`() = runBlocking {
        val store = FakeFacts()
        val session = session("s1", redTeam = true, target = "10.0.0.0/24", scope = "10.0.0.0/24")
        val fresh = coordinatorOn(store, session)
        fresh.execute(
            call("import_bundle", "bundle" to """{"segments":[{"cidr":"10.0.0.0/24","org":"示例科技有限公司"}]}"""),
            "s1",
        )
        fresh.execute(
            call("import_bundle", "bundle" to """{"segments":[{"cidr":"10.0.0.0/24","asn":"AS64500"}]}"""),
            "s1",
        )
        val segment = store.recent("s1").first { it.id == "segment:10.0.0.0/24" }
        assertTrue("org must survive a later import without it: ${segment.payload}", segment.payload.contains("示例科技有限公司"))
        assertTrue("asn must be merged in: ${segment.payload}", segment.payload.contains("AS64500"))
    }

    /**
     * 并发上限可改、改完立即生效、来源如实上报。
     *
     * 这条控件的价值全在「立即生效」上：派活时每次现读上限，所以不需要重启。
     * 如果哪天有人把上限缓存进字段，这里会红。
     */
    @Test
    fun `agents max is adjustable and takes effect immediately`() = runBlocking {
        val store = FakeFacts()
        val session = session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24")
        val fresh = coordinatorOn(store, session)
        // 必须指向临时目录：默认设置根是真实的 ~/.dsh/redteam，
        // 那里可能已经有一份 settings.json，读出来会让「默认值」断言变成在测开发机。
        fresh.filesRootOverride = java.nio.file.Files.createTempDirectory("redteam-agents").toString()

        fresh.maxAgentsOverride = null
        val before = fresh.consoleAgentsStatus("s1")
        assertEquals(RedTeamSettings.DEFAULT_MAX_AGENTS, before.max)
        assertEquals("default", before.source)

        // 覆盖层当设置项用：不落盘也能验「改完立即生效」。
        fresh.maxAgentsOverride = 7.0
        val after = fresh.consoleAgentsStatus("s1")
        assertEquals(7, after.max)
        assertEquals(RedTeamSettings.MAX_AGENTS_LIMIT, after.limit)
        assertEquals(7, after.free)

        // 闸门必须跟着走，否则「立即生效」只是一句面板文案。
        val (_, full) = fresh.execute(call("agent_slot", "sub_action" to "status"), "s1")
        assertTrue("slot gate must read the new max: $full", full.contains("max=7"))
    }

    /** 越界输入收敛到 1..10，不报错也不静默忽略。 */
    @Test
    fun `agents max clamps out of range values`() = runBlocking {
        val fresh = coordinatorOn(FakeFacts(), session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"))
        // consoleSetAgentsMax 会落盘，必须改到临时目录——否则这条测试会写真实的
        // ~/.dsh/redteam/settings.json，把开发机上的并发上限改掉。
        fresh.filesRootOverride = java.nio.file.Files.createTempDirectory("redteam-agents-clamp").toString()
        assertEquals(RedTeamSettings.MAX_AGENTS_LIMIT, fresh.consoleSetAgentsMax(99))
        assertEquals(1, fresh.consoleSetAgentsMax(0))
        assertEquals(RedTeamSettings.DEFAULT_MAX_AGENTS, fresh.consoleSetAgentsMax("abc"))
    }

    /** 角色提示词覆盖必须落库：只放内存的话重启后用户改过的提示词会静默变回内置文案。 */
    @Test
    fun `role prompt override persists as a fact and survives a new coordinator`() = runBlocking {
        val facts = FakeFacts()
        val session = session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24")
        coordinatorOn(facts, session).execute(
            call("role_prompt_save", "role" to "recon", "content" to "只做被动测绘，不许碰目标"),
            "s1",
        )

        // 换一个协调器实例读：证明真值在事实库里，不在内存缓存里。
        val (ok, out) = coordinatorOn(facts, session).execute(call("role_prompt", "role" to "recon"), "s1")
        assertTrue(out, ok)
        assertTrue("override must survive: $out", out.contains("只做被动测绘，不许碰目标"))
        assertTrue("source must say override: $out", out.contains("source=session-override"))
    }

    @Test
    fun `saving a blank role prompt restores the builtin text`() = runBlocking {
        val facts = FakeFacts()
        val session = session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24")
        val first = coordinatorOn(facts, session)
        first.execute(call("role_prompt_save", "role" to "recon", "content" to "自定义"), "s1")
        first.execute(call("role_prompt_save", "role" to "recon", "content" to ""), "s1")

        val (_, out) = coordinatorOn(facts, session).execute(call("role_prompt", "role" to "recon"), "s1")
        assertTrue("blank content must fall back to builtin: $out", out.contains("source=builtin"))
        assertFalse("override must be gone: $out", out.contains("自定义"))
    }

    @Test
    fun `role prompt reset drops the persisted override`() = runBlocking {
        val facts = FakeFacts()
        val session = session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24")
        coordinatorOn(facts, session).execute(
            call("role_prompt_save", "role" to "exploit", "content" to "只打一次"),
            "s1",
        )
        val (resetOk, reset) = coordinatorOn(facts, session).execute(call("role_prompt_reset", "role" to "exploit"), "s1")
        assertTrue(reset, resetOk)
        assertTrue("reset must report the role: $reset", reset.contains("exploit"))

        val (_, out) = coordinatorOn(facts, session).execute(call("role_prompt", "role" to "exploit"), "s1")
        assertTrue("reset must clear the fact, not just the cache: $out", out.contains("source=builtin"))
    }

    @Test
    fun `prompts lists every role with its source`() = runBlocking {
        val facts = FakeFacts()
        val session = session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24")
        coordinatorOn(facts, session).execute(
            call("role_prompt_save", "role" to "assess", "content" to "按优先级排序"),
            "s1",
        )
        val (ok, out) = coordinatorOn(facts, session).execute(call("prompts"), "s1")
        assertTrue(out, ok)
        assertTrue("all roles must be listed: $out", out.contains("count=${RedTeamRole.entries.size}"))
        assertTrue("overridden role must be marked: $out", out.contains("assess") && out.contains("session-override"))
        assertTrue("builtin roles must be marked: $out", out.contains("source=builtin"))
    }

    @Test
    fun `skill actions list read save and delete`() = runBlocking {
        val store = FakeSkillStore()
        val fresh = coordinatorOn(FakeFacts(), session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"), skillStore = store)

        val (saveOk, saved) = fresh.execute(
            call("skill_save", "name" to "内网横向", "body" to "# 横向\n用 fscan", "when_to_use" to "拿到边界权限后", "role" to "internal"),
            "s1",
        )
        assertTrue(saved, saveOk)
        assertTrue("save must report the id: $saved", saved.contains("name=内网横向"))

        val (listOk, list) = fresh.execute(call("skill_list"), "s1")
        assertTrue(list, listOk)
        assertTrue("saved skill must be listed: $list", list.contains("内网横向"))

        val (getOk, one) = fresh.execute(call("skill_get", "name" to "内网横向"), "s1")
        assertTrue(one, getOk)
        assertTrue("body must round-trip: $one", one.contains("用 fscan"))
        assertTrue("when_to_use must be kept: $one", one.contains("拿到边界权限后"))

        val (delOk, deleted) = fresh.execute(call("skill_delete", "name" to "内网横向"), "s1")
        assertTrue(deleted, delOk)
        assertTrue("delete must be reported: $deleted", deleted.contains("deleted="))

        val (_, after) = fresh.execute(call("skill_list"), "s1")
        assertFalse("deleted skill must be gone: $after", after.contains("内网横向"))
    }

    /** 内置技能不可改不可删：改了就回不到出厂文案，而面板上没有「恢复默认」。 */
    @Test
    fun `builtin skills are rejected instead of silently skipped`() = runBlocking {
        val store = FakeSkillStore()
        store.seed(
            RedTeamSkillStore.Skill(
                id = "builtin_1",
                name = "内置技能",
                role = null,
                enabled = true,
                description = "出厂",
                whenToUse = null,
                body = "# 出厂",
                path = null,
                builtin = true,
            ),
        )
        val fresh = coordinatorOn(FakeFacts(), session("s1", redTeam = true, target = "a.com", scope = "10.0.0.0/24"), skillStore = store)

        val (_, del) = fresh.execute(call("skill_delete", "name" to "内置技能"), "s1")
        assertTrue("builtin delete must report failure: $del", del.contains("ok=false"))
        assertTrue("the reason must be explicit: $del", del.contains("内置技能不可删除"))

        val (_, missing) = fresh.execute(call("skill_get", "name" to "不存在"), "s1")
        assertTrue("missing skill must say so: $missing", missing.contains("技能不存在"))
    }

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

        override suspend fun deleteById(sessionId: String, id: String) {
            rows.removeAll { it.sessionId == sessionId && it.id == id }
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

        /** 假模板库根：与 `filesRootOverride = "/rt"` 下的首选候选目录一致。 */
        const val TPL_ROOT = "/rt/toolkit/nuclei-templates"
    }
}