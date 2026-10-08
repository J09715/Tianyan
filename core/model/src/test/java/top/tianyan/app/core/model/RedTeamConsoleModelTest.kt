package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 控制台投影的行为锁定。
 *
 * 面板是照着上游 `client.js` 的三页签做的，它的过滤/排序口径直接决定演练方看到什么：
 * 这里钉住的是「同一份事实行，工具侧与面板侧必须算出同一个数」——
 * 两边各写一套，迟早出现「工具说 12 台资产、面板显示 9 台」这种对不上账。
 */
class RedTeamConsoleModelTest {

    private fun assetFact(
        id: String,
        ip: String,
        payload: String = "{}",
        status: String = "observed",
        createdAt: Long = 1_000L,
        updatedAt: Long = 2_000L,
    ) = RedTeamConsoleModel.Fact(
        id = id,
        kind = "asset",
        title = ip,
        target = ip,
        status = status,
        payload = payload,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    @Test
    fun `asset payload projects ports names and fingerprints`() {
        val fact = assetFact(
            id = "asset:10.0.0.5",
            ip = "10.0.0.5",
            payload = """
                {"provenance":"passive","tool":"fscan","primary_name":"portal.example.test",
                 "names":[{"name":"portal.example.test"}],
                 "ports":[{"port":443,"proto":"tcp","state":"open","provenance":"active"},
                          {"port":22,"proto":"tcp","state":"closed"}],
                 "services":[{"port":443,"name":"https","product":"nginx","version":"1.25"}],
                 "fingerprints":[{"category":"web","vendor":"nginx","product":"nginx","version":"1.25"}]}
            """.trimIndent(),
        )

        val asset = RedTeamConsoleModel.assetOf(fact)
        assertEquals("10.0.0.5", asset.ip)
        assertEquals("portal.example.test", asset.primaryName)
        assertEquals(RedTeamConsoleModel.State.UNKNOWN, asset.state)
        assertEquals("10.0.0.0/24", asset.segmentCidr)
        assertEquals(2, asset.ports.size)
        // services 数组要并进同号端口，否则面板显示「未知服务」而库里其实有。
        assertEquals("https", asset.ports.first { it.port == 443 }.service)
        assertEquals("nginx", asset.ports.first { it.port == 443 }.product)
        assertEquals(1, asset.openPorts.size)
        assertEquals(1, asset.passivePorts)
        assertEquals(1, asset.activePorts)
        assertEquals(1, asset.fingerprints.size)
        assertEquals(listOf("portal.example.test"), asset.names.map { it.name })
    }

    @Test
    fun `liveness comes from payload state then fact status`() {
        assertEquals(
            RedTeamConsoleModel.State.LIVE,
            RedTeamConsoleModel.assetOf(assetFact("a1", "10.0.0.1", """{"state":"live"}""")).state,
        )
        assertEquals(
            RedTeamConsoleModel.State.DEAD,
            RedTeamConsoleModel.assetOf(assetFact("a2", "10.0.0.2", """{"state":"down"}""")).state,
        )
        // 没有 state 时退回事实 status，认不出来就是未知——不默认算存活。
        assertEquals(
            RedTeamConsoleModel.State.LIVE,
            RedTeamConsoleModel.assetOf(assetFact("a3", "10.0.0.3", status = "live")).state,
        )
        assertEquals(
            RedTeamConsoleModel.State.UNKNOWN,
            RedTeamConsoleModel.assetOf(assetFact("a4", "10.0.0.4")).state,
        )
    }

    @Test
    fun `query reports the filtered total before the limit is applied`() {
        val facts = (1..5).map { assetFact("asset:10.0.0.$it", "10.0.0.$it") }
        val (total, page) = RedTeamConsoleModel.query(
            facts,
            RedTeamConsoleModel.Filter(limit = 2),
        )
        // total 是截断前的条数：用 size 当总数会让用户以为记录丢了。
        assertEquals(5, total)
        assertEquals(2, page.size)
    }

    @Test
    fun `query filters by segment port service provenance and free text`() {
        val facts = listOf(
            assetFact(
                "asset:10.0.0.5",
                "10.0.0.5",
                """{"provenance":"passive","ports":[{"port":443,"service":"https","product":"nginx","state":"open"}]}""",
            ),
            assetFact(
                "asset:192.168.1.7",
                "192.168.1.7",
                """{"provenance":"active","ports":[{"port":22,"service":"ssh","state":"open"}]}""",
            ),
        )

        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(cidr = "10.0.0.0/24")).first)
        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(port = 22)).first)
        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(service = "ngin")).first)
        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(provenance = "passive")).first)
        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(q = "192.168")).first)
        // 多个词是 AND 关系（上游按空白切词后逐词收窄）。
        assertEquals(0, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(q = "10.0.0.5 ssh")).first)
        assertEquals(1, RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter(q = "10.0.0.5 nginx")).first)
    }

    @Test
    fun `priority sort puts untested high priority assets first`() {
        val facts = listOf(
            assetFact("asset:10.0.0.9", "10.0.0.9", """{"priority":"low","test_status":"tested"}"""),
            assetFact("asset:10.0.0.8", "10.0.0.8", """{"priority":"high","test_status":"untested"}"""),
            assetFact("asset:10.0.0.7", "10.0.0.7", """{"priority":"medium","test_status":"untested"}"""),
        )
        val (_, rows) = RedTeamConsoleModel.query(facts, RedTeamConsoleModel.Filter())
        assertEquals(listOf("10.0.0.8", "10.0.0.7", "10.0.0.9"), rows.map { it.ip })

        val (_, byIp) = RedTeamConsoleModel.query(
            facts,
            RedTeamConsoleModel.Filter(sort = RedTeamConsoleModel.Sort.IP),
        )
        assertEquals(listOf("10.0.0.7", "10.0.0.8", "10.0.0.9"), byIp.map { it.ip })
    }

    @Test
    fun `segments and stats aggregate the same asset set`() {
        val facts = listOf(
            assetFact(
                "asset:10.0.0.5",
                "10.0.0.5",
                """{"state":"live","ports":[{"port":80,"state":"open","provenance":"passive"},
                                            {"port":443,"state":"open","provenance":"active"}],
                    "fingerprints":[{"product":"nginx"}]}""",
            ),
            assetFact("asset:10.0.0.6", "10.0.0.6", """{"state":"dead"}"""),
            assetFact("asset:192.168.1.7", "192.168.1.7", """{"state":"live"}"""),
        )

        val assets = RedTeamConsoleModel.assets(facts)
        val segments = RedTeamConsoleModel.segments(facts, assets)
        assertEquals(listOf("10.0.0.0/24", "192.168.1.0/24"), segments.map { it.cidr })
        assertEquals(2, segments.first().assets)
        assertEquals(2, segments.first().openPorts)

        val stats = RedTeamConsoleModel.stats(facts, assets)
        assertEquals(2, stats.segments)
        assertEquals(3, stats.assets)
        assertEquals(2, stats.liveAssets)
        assertEquals(2, stats.openPorts)
        assertEquals(1, stats.fingerprints)
        assertEquals(1, stats.passiveSignals)
        assertEquals(1, stats.activeSignals)
    }

    /** 段归属来自 segment 事实（上游独立 segment 表），不是从资产 payload 反推。 */
    @Test
    fun `segment metadata comes from segment facts`() {
        val facts = listOf(
            assetFact("asset:10.0.0.5", "10.0.0.5"),
            RedTeamConsoleModel.Fact(
                id = "segment:10.0.0.0/24",
                kind = "segment",
                title = "10.0.0.0/24",
                target = "10.0.0.0/24",
                status = "observed",
                payload = """{"org":"示例科技有限公司","asn":"AS64500","country":"CN","city":"杭州"}""",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val segment = RedTeamConsoleModel.segments(facts).single()
        assertEquals("示例科技有限公司", segment.org)
        assertEquals("AS64500", segment.asn)
        assertEquals("杭州", segment.city)
        assertEquals("示例科技有限公司 · 1 资产 · 0 端口", segment.subtitle)
    }

    /** 没有 segment 事实时归属留空——「查不到组织名」和「有组织名」是两回事，不能编。 */
    @Test
    fun `segment without metadata leaves org empty`() {
        val facts = listOf(assetFact("asset:10.0.0.5", "10.0.0.5"))
        val segment = RedTeamConsoleModel.segments(facts).single()
        assertNull(segment.org)
        assertNull(segment.asn)
        assertEquals("1 资产 · 0 端口", segment.subtitle)
    }

    @Test
    fun `graph links segments assets domains and ports`() {
        val facts = listOf(
            assetFact(
                "asset:10.0.0.5",
                "10.0.0.5",
                """{"primary_name":"portal.example.test","ports":[{"port":443,"state":"open"}]}""",
            ),
        )
        val graph = RedTeamConsoleModel.graph(facts)
        assertTrue(graph.nodes.any { it.kind == "segment" }, "segment node missing: ${graph.nodes}")
        assertTrue(graph.nodes.any { it.kind == "asset" }, "asset node missing: ${graph.nodes}")
        assertTrue(graph.nodes.any { it.kind == "domain" }, "domain node missing: ${graph.nodes}")
        assertTrue(graph.nodes.any { it.kind == "port" }, "port node missing: ${graph.nodes}")
        assertTrue(graph.edges.any { it.relation == "contains" }, "contains edge missing: ${graph.edges}")
        assertTrue(graph.edges.any { it.relation == "resolves" }, "resolves edge missing: ${graph.edges}")
    }

    @Test
    fun `explicit edge facts survive into the graph`() {
        val facts = listOf(
            assetFact("asset:10.0.0.5", "10.0.0.5"),
            RedTeamConsoleModel.Fact(
                id = "e1",
                kind = "edge",
                title = "10.0.0.5 -> 10.0.0.9",
                target = null,
                status = "observed",
                payload = """{"src":"10.0.0.5","dst":"10.0.0.9","relation":"pivots"}""",
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val graph = RedTeamConsoleModel.graph(facts)
        val edge = graph.edges.firstOrNull { it.relation == "pivots" }
        assertNotNull(edge, "explicit relation must not be dropped: ${graph.edges}")
        // 只被边引用的对端也要补成节点，否则画布上这条线无处可落。
        assertTrue(graph.nodes.any { it.id == "asset:10.0.0.9" }, "peer node missing: ${graph.nodes}")
    }

    @Test
    fun `observations are attached from matching event facts`() {
        val asset = RedTeamConsoleModel.assetOf(assetFact("asset:10.0.0.5", "10.0.0.5"))
        val facts = listOf(
            RedTeamConsoleModel.Fact(
                id = "o1",
                kind = "event",
                title = "banner",
                target = "10.0.0.5",
                status = "observed",
                payload = """{"attr":"banner","value":"nginx/1.25","tool":"httpx","collected_at":"3000"}""",
                createdAt = 3_000L,
                updatedAt = 3_000L,
            ),
            RedTeamConsoleModel.Fact(
                id = "o2",
                kind = "event",
                title = "other",
                target = "10.0.0.9",
                status = "observed",
                payload = """{"attr":"banner","value":"apache"}""",
                createdAt = 4_000L,
                updatedAt = 4_000L,
            ),
        )
        val rows = RedTeamConsoleModel.observationsOf(facts, asset)
        assertEquals(1, rows.size)
        assertEquals("nginx/1.25", rows.first().value)
        assertEquals("httpx", rows.first().tool)
    }

    @Test
    fun `import bundle ids and asset add ids are both recognised as assets`() {
        // import_bundle 写 asset:<键>，asset_add 直接用调用方给的 id —— 两种都要认。
        val bundled = RedTeamConsoleModel.assetOf(assetFact("asset:10.0.0.5", "10.0.0.5"))
        val direct = RedTeamConsoleModel.assetOf(
            RedTeamConsoleModel.Fact("10.0.0.6", "asset", "web-02", "10.0.0.6", "observed", "{}", 1L, 1L),
        )
        assertEquals("10.0.0.5", bundled.ip)
        assertEquals("10.0.0.6", direct.ip)
        assertEquals("10.0.0.0/24", direct.segmentCidr)
    }
}
