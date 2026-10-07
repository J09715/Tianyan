package top.tianyan.app.core.model

/**
 * 资产图谱的**纯派生**逻辑：C 段 → 资产，并按内网/外网分组。
 *
 * 放在 `:core:model` 而不是界面文件里，有两个理由：
 *   · 归段必须与智能体侧 `asset_graph` 用同一份实现——界面显示的网段不能与工具报的不一致；
 *   · 纯函数才能被单测直接覆盖（放在 Compose 文件里只能连 UI 一起测）。
 *
 * 界面只负责把事实记录映射成 [GraphNode]。
 */
object RedTeamGraphModel {

    /** 图谱节点的最小输入：界面从事实记录映射而来。 */
    data class GraphNode(
        val id: String,
        val title: String,
        /** IP、域名或 URL；用于归段。 */
        val target: String?,
        val status: String = "observed",
    )

    data class Segment(val cidr: String, val assets: List<GraphNode>) {
        val classified: Boolean get() = cidr != UNCLASSIFIED
    }

    data class Graph(
        val segments: List<Segment>,
        val edgeCount: Int,
    ) {
        val assetCount: Int get() = segments.sumOf { it.assets.size }

        /** 内网 / 外网分组，供面板分区展示。 */
        val byScope: Map<String, List<Segment>>
            get() = segments.groupBy { segment ->
                if (!segment.classified) "unknown"
                else RedTeamIpUtils.scopeOfIp(segment.cidr.substringBefore('/'))
            }
    }

    /** 认不出网段的目标（非 IP，例如纯域名）归到这里，**不编造网段**。 */
    const val UNCLASSIFIED = "未分类"

    /**
     * 归段。
     *
     * 关键点：先把 target 归一化再判断是否真的是 IP ——
     * `cidrOf` 对认不出的输入原样返回，所以「返回值和输入相同」就说明它不是 IP，
     * 这时应归入未分类，而不是把域名当成一个网段。
     */
    fun segmentOf(target: String?): String {
        val raw = target?.trim()?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: return UNCLASSIFIED
        val cidr = RedTeamIpUtils.cidrOf(raw)
        return if (cidr == raw) UNCLASSIFIED else cidr
    }

    fun build(assets: List<GraphNode>, edgeCount: Int = 0): Graph {
        val segments = assets
            .groupBy { segmentOf(it.target) }
            .map { (cidr, rows) -> Segment(cidr, rows) }
            // 段内资产多的排前面；同数量按网段名稳定排序，避免每次重组顺序抖动。
            .sortedWith(compareByDescending<Segment> { it.assets.size }.thenBy { it.cidr })
        return Graph(segments, edgeCount)
    }
}