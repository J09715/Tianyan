package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RedTeam 控制台（上游 `client.js` 常驻面板）的数据投影。
 *
 * 上游面板直接读 SQLite 的 `asset` / `port` / `fingerprint` / `observation` 表；本移植把一切
 * 存成事实行（资产事实的 payload 里带 `ports` / `fingerprints` / `names` 数组），
 * 所以这里做一层投影：事实行 → 面板要的资产行、网段、统计、图谱。
 *
 * 投影放 `core/model` 而不是放在面板里，是为了让「工具输出的口径」和「面板显示的口径」
 * 共用同一份计算——两边各写一套过滤/排序，迟早会出现同一个靶标在两处数量对不上。
 */
object RedTeamConsoleModel {

    /** 面板查询默认返回 400 条（与上游客户端一致），硬上限 2000（上游 `listAssets`）。 */
    const val DEFAULT_ASSET_LIMIT = 400
    const val HARD_ASSET_LIMIT = 2000

    /** 资产行的事实投影：只带投影需要的列，`core/model` 不依赖 Room 实体。 */
    data class Fact(
        val id: String,
        val kind: String,
        val title: String,
        val target: String?,
        val status: String,
        val payload: String,
        val createdAt: Long,
        val updatedAt: Long,
        /** 严重级；漏洞/战果列表要按它着色与排序，投影里缺了就只能回查实体。 */
        val severity: String? = null,
    ) {
        /** 列表副标题：目标与状态，两样都没有就留空，不显示分隔符。 */
        val subtitle: String
            get() = listOfNotNull(target?.takeIf { it.isNotBlank() }, status.takeIf { it != "observed" })
                .joinToString(" · ")
    }

    /**
     * 控制台的分区（对齐上游 `consoleDigest` 的 sections）。
     *
     * 上游是 12 个页签各自一类数据；本移植把这套分类固化成一个枚举，
     * 让「哪一类事实归哪个页面」只有一处定义——面板、摘要、测试都读它。
     * 各自散写 `filter { it.kind == ... }` 迟早出现同一类数据在两个页面里数量对不上。
     */
    enum class Section(val id: String, val label: String, val kinds: List<String>) {
        TARGETS("targets", "目标", listOf("engagement", "segment")),
        SESSIONS("sessions", "会话", listOf("webshell", "tunnel", "access_session", "credential")),
        VULNS("vulns", "漏洞", listOf("vulnerability")),
        CHAIN("chain", "攻击链", listOf("attack_step", "edge")),
        SCORES("scores", "得分", listOf("score_hit", "score_point")),
        RESULTS("results", "战果", listOf("score_hit", "vulnerability", "webshell", "tunnel", "access_session", "credential")),
        REPORT("report", "报告", listOf("report")),
        KNOWLEDGE("knowledge", "知识", listOf("knowledge")),
        ATTACK_FILES("attackfiles", "攻击文件", listOf("attack_file")),
    }

    /** 按分区取事实；顺序沿用事实库给出的时间倒序，不在 UI 里再排一次。 */
    fun section(facts: List<Fact>, section: Section): List<Fact> =
        facts.filter { it.kind in section.kinds }

    /**
     * 分区摘要：条数 + 最近一条时间。上游 `consoleDigest` 就是给面板点红点用的。
     */
    data class SectionDigest(val id: String, val label: String, val count: Int, val latestAt: Long?)

    fun digest(facts: List<Fact>): List<SectionDigest> = Section.entries.map { section ->
        val rows = section(facts, section)
        SectionDigest(
            id = section.id,
            label = section.label,
            count = rows.size,
            latestAt = rows.maxOfOrNull { it.updatedAt },
        )
    }

    data class Name(val name: String, val source: String?)

    data class Port(
        val port: Int,
        val proto: String,
        val state: String,
        val service: String?,
        val product: String?,
        val version: String?,
        val provenance: String,
    ) {
        val open: Boolean get() = state == "open"
    }

    data class Fingerprint(
        val category: String?,
        val vendor: String?,
        val product: String?,
        val version: String?,
        val evidence: String?,
        val provenance: String,
    )

    data class Observation(
        val attr: String?,
        val value: String?,
        val tool: String?,
        val collectedAt: Long?,
        val provenance: String,
    )

    /** 存活状态：上游是 `asset.state`；本移植优先 payload.state，退回事实 status。 */
    enum class State(val id: String) {
        LIVE("live"),
        DEAD("dead"),
        UNKNOWN("unknown");

        companion object {
            fun from(vararg candidates: String?): State = when {
                candidates.any { it.equals("live", true) || it.equals("alive", true) || it.equals("up", true) } -> LIVE
                candidates.any { it.equals("dead", true) || it.equals("down", true) || it.equals("offline", true) } -> DEAD
                else -> UNKNOWN
            }
        }
    }

    data class Asset(
        val id: String,
        val ip: String,
        val primaryName: String?,
        val state: State,
        val segmentCidr: String?,
        val provenance: String,
        val tool: String?,
        val priority: String?,
        val testStatus: String?,
        val scope: String?,
        val names: List<Name>,
        val ports: List<Port>,
        val fingerprints: List<Fingerprint>,
        val firstSeen: Long,
        val lastSeen: Long,
    ) {
        val openPorts: List<Port> get() = ports.filter { it.open }
        val passivePorts: Int get() = ports.count { it.provenance == "passive" }
        val activePorts: Int get() = ports.count { it.provenance == "active" }
    }

    data class Detail(val asset: Asset, val observations: List<Observation>)

    data class Segment(
        val cidr: String,
        val org: String?,
        val asn: String? = null,
        val country: String? = null,
        val city: String? = null,
        val assets: Int = 0,
        val openPorts: Int = 0,
        val passivePorts: Int = 0,
        val activePorts: Int = 0,
    ) {
        /** 侧栏副标题：归属信息有就显示，没有就退回纯计数，不编造。 */
        val subtitle: String
            get() = buildString {
                org?.let { append("$it · ") }
                append("$assets 资产 · $openPorts 端口")
            }
    }

    data class Stats(
        val segments: Int = 0,
        val assets: Int = 0,
        val liveAssets: Int = 0,
        val openPorts: Int = 0,
        val fingerprints: Int = 0,
        val passiveSignals: Int = 0,
        val activeSignals: Int = 0,
    )

    data class GraphNode(val id: String, val kind: String, val label: String, val state: State)
    data class GraphEdge(val source: String, val target: String, val relation: String)
    data class Graph(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    /** 靶标（上游的 engagement）。本移植不另建靶标表：一个绑定了红队模式的会话就是一个靶标。 */
    data class Engagement(val id: String, val name: String, val target: String?, val scope: String)

    /**
     * 面板首屏（上游 `bootstrap`）。
     *
     * 一次给全：面板开合频繁，分开取会出现「切页签时统计已经变了、资产列表还是旧的」，
     * 底栏数字与列表对不上比慢一点更让人不信任。
     */
    data class Snapshot(
        val engagements: List<Engagement>,
        val current: String?,
        val assets: List<Asset>,
        val segments: List<Segment>,
        val stats: Stats,
    )

    /** 角色提示词行（上游 `prompts`）。`source` 说明这条是内置文案还是会话覆盖。 */
    data class Role(
        val id: String,
        val title: String,
        val source: String,
        val content: String,
        val updatedAt: Long?,
    ) {
        val overridden: Boolean get() = source == SOURCE_OVERRIDE

        companion object {
            const val SOURCE_BUILTIN = "builtin"
            const val SOURCE_OVERRIDE = "session-override"
        }
    }

    /** 排序口径，与上游 `listAssets` 的 `sort` 参数一一对应。 */
    enum class Sort(val id: String, val label: String) {
        PRIORITY("priority", "易打性优先"),
        PORTS("ports", "端口多优先"),
        DISCOVERED("discovered", "最新发现"),
        TODO("todo", "待测优先"),
        IP("ip", "按 IP");

        companion object {
            fun from(id: String?): Sort = entries.firstOrNull { it.id == id } ?: PRIORITY
        }
    }

    /** 面板工具栏的过滤条件；字段名与上游 `assets` op 对齐，少一个都对不上。 */
    data class Filter(
        val cidr: String? = null,
        val state: String? = null,
        val ip: String? = null,
        val port: Int? = null,
        val service: String? = null,
        val fingerprint: String? = null,
        val provenance: String? = null,
        val priority: String? = null,
        val testStatus: List<String> = emptyList(),
        val scope: String? = null,
        val q: String? = null,
        val sort: Sort = Sort.PRIORITY,
        val limit: Int = DEFAULT_ASSET_LIMIT,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun payloadOf(fact: Fact): JsonObject =
        runCatching { json.parseToJsonElement(fact.payload).jsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

    private fun JsonObject.rows(key: String): List<JsonObject> =
        (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }.orEmpty()

    private fun JsonObject.provenanceOf(default: String = "active"): String =
        str("provenance")?.lowercase() ?: default

    /**
     * 资产事实 → 资产行。
     *
     * 身份键取 `target`（IP）优先、退回 `payload.ip`、最后用事实 id：`import_bundle` 写的是
     * `asset:<键>`，而 `asset_add` 直接用调用方给的 id，两种都要认，否则同一台机器会显示成两行。
     */
    fun assetOf(fact: Fact): Asset {
        val payload = payloadOf(fact)
        val ip = fact.target?.takeIf { it.isNotBlank() }
            ?: payload.str("ip")
            ?: payload.str("asset_id")
            ?: fact.id.substringAfter("asset:", fact.id)
        val state = State.from(payload.str("state"), fact.status)
        val ports = payload.rows("ports").map { row ->
            Port(
                port = row.str("port")?.toIntOrNull() ?: row.str("port_number")?.toIntOrNull() ?: 0,
                proto = row.str("proto") ?: row.str("protocol") ?: "tcp",
                state = row.str("state") ?: "open",
                service = row.str("service") ?: row.str("name"),
                product = row.str("product"),
                version = row.str("version"),
                provenance = row.provenanceOf(payload.provenanceOf()),
            )
        }
        // 上游把服务单列一张表；本移植落在 `services` 数组里，这里按端口号并进对应端口，
        // 免得面板上「有服务但端口显示未知服务」。
        val services = payload.rows("services").associateBy { row ->
            row.str("port")?.toIntOrNull() ?: 0
        }
        val mergedPorts = ports.map { p ->
            val s = services[p.port] ?: return@map p
            p.copy(
                service = p.service ?: s.str("name"),
                product = p.product ?: s.str("product"),
                version = p.version ?: s.str("version"),
            )
        }
        val fingerprints = payload.rows("fingerprints").map { row ->
            Fingerprint(
                category = row.str("category"),
                vendor = row.str("vendor"),
                product = row.str("product"),
                version = row.str("version"),
                evidence = row.str("evidence"),
                provenance = row.provenanceOf(payload.provenanceOf()),
            )
        }
        val names = payload.rows("names").mapNotNull { row ->
            row.str("name")?.let { Name(it, row.str("source")) }
        }
        val segment = payload.str("segment_cidr")
            ?: RedTeamGraphModel.segmentOf(ip).takeIf { it != RedTeamGraphModel.UNCLASSIFIED }
        return Asset(
            id = fact.id,
            ip = ip,
            primaryName = payload.str("primary_name") ?: names.firstOrNull()?.name,
            state = state,
            segmentCidr = segment,
            provenance = payload.provenanceOf(),
            tool = payload.str("tool"),
            priority = payload.str("priority"),
            testStatus = payload.str("test_status") ?: payload.str("testStatus"),
            scope = payload.str("scope") ?: RedTeamIpUtils.scopeOfIp(ip),
            names = names,
            ports = mergedPorts,
            fingerprints = fingerprints,
            firstSeen = fact.createdAt,
            lastSeen = fact.updatedAt,
        )
    }

    fun assets(facts: List<Fact>): List<Asset> =
        facts.filter { it.kind == "asset" }.map(::assetOf)

    /** 观测溯源：上游 `observation` 表按实体挂载，这里取该资产相关的 event 事实。 */
    fun observationsOf(facts: List<Fact>, asset: Asset): List<Observation> =
        facts.asSequence()
            .filter { it.kind == "event" }
            .filter { it.target == asset.ip || it.target == asset.id || it.title == asset.ip }
            .map { fact ->
                val payload = payloadOf(fact)
                Observation(
                    attr = payload.str("attr") ?: fact.title,
                    value = payload.str("value") ?: payload.str("result"),
                    tool = payload.str("tool"),
                    collectedAt = payload.long("collected_at") ?: fact.createdAt,
                    provenance = payload.provenanceOf(asset.provenance),
                )
            }
            .sortedByDescending { it.collectedAt ?: 0L }
            .toList()

    /**
     * 查询：过滤 + 排序 + 截断。返回 (命中总数, 当前页)。
     *
     * `total` 是**过滤后、截断前**的条数——面板要显示「共 N 条，显示前 M 条」，
     * 用截断后的 size 当总数会让用户以为记录丢了。
     */
    fun query(facts: List<Fact>, filter: Filter): Pair<Int, List<Asset>> {
        val terms = filter.q.orEmpty().split(Regex("\\s+")).filter { it.isNotBlank() }.map { it.lowercase() }
        val filtered = assets(facts).filter { asset ->
            (filter.cidr == null || asset.segmentCidr == filter.cidr) &&
                (filter.state == null || asset.state.id == filter.state.lowercase()) &&
                (filter.ip == null || asset.ip == filter.ip) &&
                (filter.port == null || asset.ports.any { it.port == filter.port }) &&
                (filter.service == null || asset.ports.any { p ->
                    p.service?.contains(filter.service, true) == true ||
                        p.product?.contains(filter.service, true) == true
                }) &&
                (filter.fingerprint == null || asset.fingerprints.any { f ->
                    listOf(f.product, f.vendor, f.category).any { it?.contains(filter.fingerprint, true) == true }
                }) &&
                (filter.provenance == null || asset.ports.any { it.provenance == filter.provenance.lowercase() }) &&
                (filter.priority == null || asset.priority == filter.priority) &&
                (filter.testStatus.isEmpty() || (asset.testStatus ?: "untested") in filter.testStatus) &&
                (filter.scope == null || asset.scope == filter.scope) &&
                terms.all { term -> asset.matches(term) }
        }
        val sorted = when (filter.sort) {
            Sort.IP -> filtered.sortedWith(compareBy({ RedTeamIpUtils.ipToInt(it.ip) ?: Long.MAX_VALUE }, { it.id }))
            Sort.PORTS -> filtered.sortedWith(
                compareByDescending<Asset> { it.openPorts.size }
                    .thenBy { RedTeamIpUtils.ipToInt(it.ip) ?: Long.MAX_VALUE },
            )
            Sort.DISCOVERED -> filtered.sortedWith(compareByDescending<Asset> { it.firstSeen }.thenByDescending { it.id })
            Sort.TODO -> filtered.sortedWith(
                compareBy<Asset> { testRank(it.testStatus) }
                    .thenBy { priorityRank(it.priority) }
                    .thenByDescending { it.openPorts.size }
                    .thenBy { RedTeamIpUtils.ipToInt(it.ip) ?: Long.MAX_VALUE },
            )
            Sort.PRIORITY -> filtered.sortedWith(
                compareBy<Asset> { priorityRank(it.priority) }
                    .thenBy { testRank(it.testStatus) }
                    .thenByDescending { it.openPorts.size }
                    .thenBy { RedTeamIpUtils.ipToInt(it.ip) ?: Long.MAX_VALUE },
            )
        }
        val limit = filter.limit.coerceIn(1, HARD_ASSET_LIMIT)
        return sorted.size to sorted.take(limit)
    }

    private fun Asset.matches(term: String): Boolean =
        ip.lowercase().contains(term) ||
            primaryName?.lowercase()?.contains(term) == true ||
            names.any { it.name.lowercase().contains(term) } ||
            ports.any { p ->
                listOf(p.service, p.product, p.version, p.port.toString()).any { it?.lowercase()?.contains(term) == true }
            } ||
            fingerprints.any { f ->
                listOf(f.vendor, f.product, f.version, f.category).any { it?.lowercase()?.contains(term) == true }
            }

    private fun testRank(status: String?): Int = when (status ?: "untested") {
        "untested" -> 0
        "testing" -> 1
        "tested" -> 2
        "blocked" -> 3
        "abandoned" -> 4
        "no_surface" -> 5
        else -> 6
    }

    private fun priorityRank(priority: String?): Int = when (priority) {
        "high" -> 0
        "medium" -> 1
        "low" -> 2
        else -> 3
    }

    /**
     * 网段清单（面板左侧栏）。归属组织从段内资产的 `org`/`owner` 取第一个非空值——
     * 上游有独立的 segment 表，本移植没有，只能从资产反推；取不到就留空，不编造。
     */
    fun segments(facts: List<Fact>, assets: List<Asset> = assets(facts)): List<Segment> {
        // 归属信息来自 segment 事实（上游是独立的 segment 表，由 import_bundle 写入）。
        // 不从资产 payload 反推：上游的 org 本来就不挂在资产上，反推出来的值十有八九是空的，
        // 而面板上「有组织名」和「查不到组织名」是两回事，混在一起会让人以为数据丢了。
        val metaByCidr = facts.filter { it.kind == "segment" }.associate { fact ->
            val payload = payloadOf(fact)
            val cidr = payload.str("cidr") ?: fact.target ?: fact.title
            cidr to payload
        }
        return assets.mapNotNull { it.segmentCidr }
            .distinct()
            .sortedWith(compareBy({ RedTeamIpUtils.ipToInt(it.substringBefore('/')) ?: Long.MAX_VALUE }, { it }))
            .map { cidr ->
                val rows = assets.filter { it.segmentCidr == cidr }
                val meta = metaByCidr[cidr]
                Segment(
                    cidr = cidr,
                    org = meta?.str("org"),
                    asn = meta?.str("asn"),
                    country = meta?.str("country"),
                    city = meta?.str("city"),
                    assets = rows.size,
                    openPorts = rows.sumOf { it.openPorts.size },
                    passivePorts = rows.sumOf { it.passivePorts },
                    activePorts = rows.sumOf { it.activePorts },
                )
            }
    }

    /** 底栏统计：段数 / 资产（存活）/ 端口 / 指纹 / 被动·主动信号。 */
    fun stats(facts: List<Fact>, assets: List<Asset> = assets(facts)): Stats = Stats(
        segments = assets.mapNotNull { it.segmentCidr }.distinct().size,
        assets = assets.size,
        liveAssets = assets.count { it.state == State.LIVE },
        openPorts = assets.sumOf { it.openPorts.size },
        fingerprints = assets.sumOf { it.fingerprints.size },
        passiveSignals = assets.sumOf { it.passivePorts },
        activeSignals = assets.sumOf { it.activePorts },
    )

    /**
     * 图谱：网段 → 资产 → 端口/域名。
     *
     * 与上游 canvas 的节点种类保持一致（segment / asset / port / domain），
     * 因为面板的图例与配色是按这四类写死的。
     */
    fun graph(facts: List<Fact>, cidr: String? = null, maxNodes: Int = 600): Graph {
        val rows = assets(facts).filter { cidr == null || it.segmentCidr == cidr }
        val nodes = mutableListOf<GraphNode>()
        val edges = mutableListOf<GraphEdge>()
        rows.mapNotNull { it.segmentCidr }.distinct().forEach { segment ->
            nodes += GraphNode("segment:$segment", "segment", segment, State.UNKNOWN)
        }
        rows.forEach { asset ->
            val assetNode = "asset:${asset.id}"
            nodes += GraphNode(assetNode, "asset", asset.ip, asset.state)
            asset.segmentCidr?.let { edges += GraphEdge("segment:$it", assetNode, "contains") }
            asset.primaryName?.let { name ->
                val domainNode = "domain:$name"
                nodes += GraphNode(domainNode, "domain", name, State.UNKNOWN)
                edges += GraphEdge(domainNode, assetNode, "resolves")
            }
            asset.openPorts.forEach { port ->
                val portNode = "port:$assetNode:${port.port}"
                nodes += GraphNode(portNode, "port", "${port.port}/${port.proto}", State.UNKNOWN)
                edges += GraphEdge(assetNode, portNode, "exposes")
            }
        }
        // 显式登记的关系边（asset_link / import_bundle）优先于推导出来的结构边，
        // 它们承载的是「谁指向谁」这类人工判断，图谱上不能丢。
        facts.filter { it.kind == "edge" }.forEach { fact ->
            val payload = payloadOf(fact)
            val src = payload.str("src") ?: payload.str("src_id") ?: return@forEach
            val dst = payload.str("dst") ?: payload.str("dst_id") ?: return@forEach
            val relation = payload.str("relation") ?: "related"
            val srcNode = nodes.firstOrNull { it.id == "asset:$src" || it.id == "domain:$src" || it.id == "segment:$src" }
                ?: GraphNode("asset:$src", "asset", src, State.UNKNOWN).also { nodes += it }
            val dstNode = nodes.firstOrNull { it.id == "asset:$dst" || it.id == "domain:$dst" || it.id == "segment:$dst" }
                ?: GraphNode("asset:$dst", "asset", dst, State.UNKNOWN).also { nodes += it }
            edges += GraphEdge(srcNode.id, dstNode.id, relation)
        }
        return Graph(
            nodes = nodes.distinctBy { it.id }.take(maxNodes),
            edges = edges.distinct().take(maxNodes * 2),
        )
    }

    /** 攻击路径视图（上游 `attackPath`）：只保留显式关系边，按起点聚合。 */
    fun attackPaths(facts: List<Fact>): List<Triple<String, String, String>> =
        facts.filter { it.kind == "edge" }.mapNotNull { fact ->
            val payload = payloadOf(fact)
            val src = payload.str("src") ?: payload.str("src_id") ?: return@mapNotNull null
            val dst = payload.str("dst") ?: payload.str("dst_id") ?: return@mapNotNull null
            Triple(src, dst, payload.str("relation") ?: "related")
        }
}
