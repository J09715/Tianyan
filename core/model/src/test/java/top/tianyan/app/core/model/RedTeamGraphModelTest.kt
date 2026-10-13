package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 资产图谱派生测试。
 *
 * 核心约束：界面显示的网段必须与智能体侧 `asset_graph` 一致——两边各写一份归段，
 * 就会出现「工具报 3 个 C 段、面板画 4 个」这种对不上的情况。
 */
class RedTeamGraphModelTest {

    private fun node(target: String?, title: String = "a", status: String = "observed") =
        RedTeamGraphModel.GraphNode(id = title, title = title, target = target, status = status)

    @Test
    fun `ipv4 assets group into C segments`() {
        val graph = RedTeamGraphModel.build(
            listOf(
                node("10.0.0.5", "web-01"),
                node("10.0.0.9", "web-02"),
                node("10.0.1.7", "db-01"),
            ),
        )
        assertEquals(2, graph.segments.size)
        assertEquals("10.0.0.0/24", graph.segments[0].cidr)
        assertEquals(2, graph.segments[0].assets.size)
        assertEquals("10.0.1.0/24", graph.segments[1].cidr)
        assertEquals(3, graph.assetCount)
    }

    /** IPv6 走 /64，且缩写里的十六进制段必须保留。 */
    @Test
    fun `ipv6 assets group into 64 segments`() {
        val graph = RedTeamGraphModel.build(listOf(node("2001:db8::1", "v6-01")))
        assertEquals("2001:db8:0:0::/64", graph.segments.single().cidr)
        assertTrue(graph.segments.single().classified)
    }

    /** 域名等非 IP 目标归入未分类，不编造网段。 */
    @Test
    fun `non ip targets are unclassified rather than invented`() {
        val graph = RedTeamGraphModel.build(
            listOf(node("example.com", "site"), node("10.0.0.5", "web-01")),
        )
        val unclassified = graph.segments.first { !it.classified }
        assertEquals(RedTeamGraphModel.UNCLASSIFIED, unclassified.cidr)
        assertEquals("site", unclassified.assets.single().title)

        // 关键：不能出现把域名拼成网段的脏 CIDR。
        assertTrue(
            graph.segments.none { it.cidr.contains("example.com") },
            "domain must not become a segment: ${graph.segments.map { it.cidr }}",
        )
    }

    @Test
    fun `assets are split by internal and external scope`() {
        val graph = RedTeamGraphModel.build(
            listOf(
                node("10.0.0.5", "internal-1"),
                node("192.168.1.5", "internal-2"),
                node("203.0.113.5", "external-1"),
                node("example.com", "unclassified"),
            ),
        )
        val byScope = graph.byScope
        assertEquals(2, byScope.getValue("internal").size)
        assertEquals(1, byScope.getValue("external").size)
        assertEquals(1, byScope.getValue("unknown").size)
    }

    @Test
    fun `cidr targets and paths still resolve to their segment`() {
        val graph = RedTeamGraphModel.build(
            listOf(
                node("10.0.0.0/24", "scope"),
                node("http://10.0.0.5/x", "url-host-not-ip"),
            ),
        )
        // 带 /24 的写法取其网络号；URL 不是 IP 字面量，归未分类。
        assertTrue(graph.segments.any { it.cidr == "10.0.0.0/24" }, "cidr: ${graph.segments.map { it.cidr }}")
        assertTrue(graph.segments.any { !it.classified }, "URL should be unclassified")
    }

    @Test
    fun `segments are ordered by asset count then cidr for stable rendering`() {
        val graph = RedTeamGraphModel.build(
            listOf(
                node("10.0.2.5", "one"),
                node("10.0.1.5", "a"),
                node("10.0.1.6", "b"),
                node("10.0.3.5", "c"),
                node("10.0.3.6", "d"),
            ),
        )
        assertEquals(listOf("10.0.1.0/24", "10.0.3.0/24", "10.0.2.0/24"), graph.segments.map { it.cidr })
        // 同数量时按网段名排序，保证每次重组顺序不抖动。
        assertEquals(graph.segments.map { it.cidr }, RedTeamGraphModel.build(
            listOf(node("10.0.3.5"), node("10.0.1.5"), node("10.0.1.6"), node("10.0.3.6"), node("10.0.2.5")),
        ).segments.map { it.cidr })
    }

    @Test
    fun `blank and missing targets are unclassified`() {
        val graph = RedTeamGraphModel.build(listOf(node(null, "a"), node("", "b"), node("   ", "c")))
        assertEquals(1, graph.segments.size)
        assertEquals(RedTeamGraphModel.UNCLASSIFIED, graph.segments.single().cidr)
        assertEquals(3, graph.segments.single().assets.size)
    }

    @Test
    fun `segment derivation matches the agent facing cidr utility`() {
        // 与智能体侧 segmentOf 同一口径：IPv4 → /24，IPv6 → /64。
        assertEquals("10.0.0.0/24", RedTeamGraphModel.segmentOf("10.0.0.5"))
        assertEquals("2001:db8:0:0::/64", RedTeamGraphModel.segmentOf("2001:db8::1"))
        assertEquals(RedTeamGraphModel.UNCLASSIFIED, RedTeamGraphModel.segmentOf("example.com"))
        assertEquals(RedTeamGraphModel.UNCLASSIFIED, RedTeamGraphModel.segmentOf(null))
    }
}