package top.tianyan.app.core.model

import kotlin.math.roundToInt

/** 计分口径：去重的粒度。 */
enum class ScoreDedupScope(val id: String) {
    SERVICE("service"),
    SYSTEM("system"),
    TARGET("target"),
    NONE("none");

    companion object {
        fun fromId(id: String?): ScoreDedupScope =
            entries.firstOrNull { it.id == id?.trim()?.lowercase() } ?: SERVICE
    }
}

/** 一个得分点。`rule` 是上限分组键，`src` 只是与合并版原表对账用的原序号。 */
data class ScorePoint(
    val src: Int,
    val rule: Int,
    val tier: String,
    val code: String,
    val name: String,
    val category: String,
    val points: Int,
    val cap: Int,
    val dedupScope: ScoreDedupScope,
    val description: String = "",
)

/** 命中行。`points` 必须是**本条命中的实际分值**（合并版同一条含多档，传默认值会把哪条计分判反）。 */
data class ScoreHit(
    val id: Long,
    val code: String,
    val points: Int? = null,
    val assetId: Long? = null,
    val port: Int? = null,
    val target: String? = null,
    val recordedAt: Long = 0L,
    val selfCreated: Boolean = false,
    /** 调用方显式指定的系统键；只有能通过服务键校验时才采信。 */
    val systemKey: String? = null,
    val evidence: String? = null,
    val multiplier: Double? = null,
    /** 声明为 6 时触发 G6 ×3。 */
    val ipVersion: Int? = null,
    /** `large`（超 1 亿条/10TB）或 `knowledge-base` 触发 G5 ×2。 */
    val dataScale: String? = null,
)

/** 打过分值上限/去重标记后的命中行。 */
data class ScoredHit(
    val hit: ScoreHit,
    val points: Int,
    val rule: Int?,
    val ruleCap: Int,
    val dedupScope: ScoreDedupScope,
    val capGroup: String,
    val systemKey: String,
    val capped: Boolean = false,
    val cappedById: Long? = null,
    val cappedByPoints: Int? = null,
    val cappedReasonKind: String? = null,
)

data class ScoreCapUsage(val rule: Int?, val cap: Int, val used: Int, val cappedCount: Int)

data class ScoreBoard(
    val items: List<ScoredHit>,
    val caps: Map<String, ScoreCapUsage>,
    val cappedCount: Int,
) {
    /** 计分总分：只累计未被封顶的条目。 */
    val totalPoints: Int get() = items.filterNot { it.capped }.sumOf { it.points }
    fun byId(id: Long): ScoredHit? = items.firstOrNull { it.hit.id == id }
}

/**
 * 评分引擎：封顶、去重、倍率、服务键。
 *
 * 上游把这份逻辑当**唯一实现**——面板、报告、攻击链三处都调它，因为三处各写一遍必然漂移成
 * 不同口径，同一份战果会算出三个总分。移植时保持同样的约束：任何算分的地方都只能调这里。
 *
 * 已忠实复刻上游的两处已知怪癖（不是笔误，是对齐行为）：
 *   · `parseTargetPort("2001:db8::1")` 返回 1 —— 裸写 IPv6 会被 `:(\d{1,5})$` 当成端口；
 *   · 认不出服务信息时**不参与去重**（而非退化成每条自成一键），否则同一口径的命中会互相顶掉。
 */
object RedTeamScoring {

    val DEFAULT_POINTS: List<ScorePoint> = RedTeamScoreCatalog.POINTS

    /** code → 得分点。 */
    val POINTS_BY_CODE: Map<String, ScorePoint> = DEFAULT_POINTS.associateBy { it.code }

    /** 旧口径：账号类与数据库类按「同资产同端口」封顶（兼容 v0.10 及以前的数据）。 */
    private val LEGACY_SERVICE_CAPPED = setOf("web-account-user", "web-account-admin", "db-access")

    const val SENSITIVE_DATA_MIN_ROWS = 1_000_000L

    // ────────────────────────────── 隧道判定 ──────────────────────────────

    /** 什么算「真隧道」：必须跨越靶标边界，即通道一端在目标侧。 */
    val TUNNEL_ENTRY_KINDS: Map<String, String> = linkedMapOf(
        "target-outbound" to "目标主动连出（反弹 shell / 目标上跑 frp 客户端）",
        "target-http" to "经目标 WebShell/HTTP 通道（suo5 / Neo-ReGeorg）",
        "target-agent" to "经目标已控进程/会话转发（SSH -R 等）",
        "self-only" to "只在自己 VPS/自建服务器上（不算突破）",
    )

    /** 未声明（老数据/没填）返回 null，界面按「待确认」显示。 */
    fun tunnelIsLegit(entryKind: String?): Boolean? {
        val key = entryKind?.trim().orEmpty()
        if (key.isEmpty()) return null
        return key != "self-only"
    }

    // ────────────────────────────── 地址与端口 ──────────────────────────────

    fun isIpv6(raw: String?): Boolean {
        val text = raw?.trim().orEmpty()
        if (!text.contains(':')) return false
        if (!Regex("^[0-9a-fA-F:]+(:\\d{1,3}(\\.\\d{1,3}){3})?$").matches(text)) return false
        return text.split(':').size <= 9
    }

    /**
     * 从 target 解析端口。解析不出返回 null——**不要编造 80/443**，否则会把不同服务并成一个。
     *
     * 注意：裸写 IPv6（`2001:db8::1`）会被末尾的 `:1` 误判成端口，这是上游既有行为，
     * 为保持计分口径一致而刻意保留。
     */
    fun parseTargetPort(target: String?): Int? {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return null
        val url = Regex("^[a-z][a-z0-9+.-]*://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(t)
        val authority = url?.groupValues?.get(1) ?: Regex("^([^\\s/?#]+)").find(t)?.groupValues?.get(1)
        if (authority.isNullOrEmpty()) return null
        val m = Regex(":(\\d{1,5})$").find(authority) ?: return null
        val n = m.groupValues[1].toIntOrNull() ?: return null
        return if (n in 1..65535) n else null
    }

    /** 库列的 port（老数据可能是 null/0/空串）。 */
    fun normalizePort(value: Int?): Int? = value?.takeIf { it in 1..65535 }

    /**
     * 从 target 取「服务标识」host[:port]。
     * 没写端口时按协议补默认端口，这样 `http://h/x` 与 `http://h:80/y` 归到同一服务；
     * 路径/参数/查询串一律丢掉。解析不出 host 返回 null。
     */
    fun targetAuthority(target: String?): String? {
        val t = target?.trim().orEmpty()
        if (t.isEmpty()) return null
        val url = Regex("^([a-z][a-z0-9+.-]*)://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(t)
        val scheme = url?.groupValues?.get(1)?.lowercase()
        val authority = url?.groupValues?.get(2) ?: Regex("^([^\\s/?#]+)").find(t)?.groupValues?.get(1)
        if (authority.isNullOrEmpty()) return null
        val host = authority.replace(Regex(":\\d{1,5}$"), "").lowercase()
        if (host.isEmpty()) return null
        var port = parseTargetPort(t)
        if (port == null) {
            port = when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> null
            }
        }
        return if (port == null) host else "$host:$port"
    }

    /**
     * 「服务」键 = 封顶粒度。同一个资产同一个端口只算一次分。
     * target 能解析就用 target（同一服务可能有人带 asset_id、有人只写 target，以目标为准才能归到一起）；
     * 否则退到资产；两样都没有返回 null（拿不到服务信息，不参与封顶）。
     */
    fun scoreServiceKey(hit: ScoreHit): String? {
        targetAuthority(hit.target)?.let { return "t$it" }
        val assetId = hit.assetId ?: return null
        val port = normalizePort(hit.port)
        return "a$assetId:${port ?: 0}"
    }

    /** 服务可读标签，给告警/报告用。 */
    fun serviceLabel(hit: ScoreHit, assetIp: String? = null): String {
        val ip = assetIp?.takeIf { it.isNotEmpty() } ?: ""
        val port = normalizePort(hit.port) ?: parseTargetPort(hit.target)
        if (ip.isNotEmpty()) return if (port == null) ip else "$ip:$port"
        val authority = targetAuthority(hit.target)
            ?: return hit.target?.takeIf { it.isNotEmpty() } ?: "(未标注服务)"
        if (port != null && !Regex(":\\d{1,5}$").containsMatchIn(authority)) return "$authority:$port"
        return authority
    }

    // ────────────────────────────── 倍率 ──────────────────────────────

    /**
     * 这个 target 的主机部分是 IPv6 字面量吗。
     * 不能用粗糙正则代替：IPv6 里混着十六进制字母，只看冒号和十六进制会漏判带字母的地址
     * （`2001:db8::1` 的 `db8` 会被漏掉，G6 的 ×3 完全不生效）。
     */
    fun targetHasIpv6Host(target: String?): Boolean {
        val text = target?.trim().orEmpty()
        if (text.isEmpty()) return false
        Regex("^[a-z][a-z0-9+.-]*://(\\[[^\\]]+\\])", RegexOption.IGNORE_CASE).find(text)?.let {
            return isIpv6(it.groupValues[1].trim('[', ']'))
        }
        val url = Regex("^[a-z][a-z0-9+.-]*://([^/?#\\s]+)", RegexOption.IGNORE_CASE).find(text)
        val authority = url?.groupValues?.get(1) ?: Regex("^([^\\s/?#]+)").find(text)?.groupValues?.get(1)
        if (authority.isNullOrEmpty()) return false
        val host = authority.trim('[', ']').replace(Regex(":\\d{1,5}$"), "")
        return isIpv6(host)
    }

    data class Multiplier(val multiplier: Double, val reasons: List<String>)

    /** G5 / G6 倍率。倍率作用在权限分上，且**不突破原上限**（由 cap 兜住）。 */
    fun scoreMultiplierOf(hit: ScoreHit): Multiplier =
        scoreMultiplierOf(hit.ipVersion, hit.dataScale, hit.target)

    /** 同上，但显式接受 ip_version / data_scale，供调用方直接声明。 */
    fun scoreMultiplierOf(ipVersion: Int?, dataScale: String?, target: String?): Multiplier {
        val reasons = mutableListOf<String>()
        var multiplier = 1.0
        val t = target.orEmpty()
        val ipv6 = ipVersion == 6 || targetHasIpv6Host(t) ||
            Regex("(^|//)[0-9a-f]{0,4}:[0-9a-f:]+", RegexOption.IGNORE_CASE).containsMatchIn(t)
        if (ipv6) {
            multiplier *= 3
            reasons += "G6 IPv6 成果 ×3"
        }
        when (dataScale?.trim()?.lowercase()) {
            "large" -> { multiplier *= 2; reasons += "G5 数据规模超 1 亿条 / 10TB ×2" }
            "knowledge-base", "kb" -> { multiplier *= 2; reasons += "G5 控制知识库相关系统 ×2" }
        }
        return Multiplier(multiplier, reasons)
    }

    /** 本条命中的实际分值 = 档位分值 × 倍率。 */
    fun hitPointsOf(hit: ScoreHit): Int {
        val base = hit.points ?: POINTS_BY_CODE[hit.code]?.points ?: 0
        val mult = hit.multiplier?.takeIf { it > 0 && it.isFinite() } ?: 1.0
        return (base * mult).roundToInt()
    }

    // ────────────────────────────── 系统键 ──────────────────────────────

    /**
     * 参与 `system` 口径去重的系统键。判定顺序：
     *   ① 调用方显式给的 system_key —— **只有能通过服务键校验才采信**，
     *      否则 `"|8080"` 这种退化键会把不同主机的服务并成「同一个系统」，白白封顶别人的成果；
     *   ② 资产名（系统名/域名首选名）+ 服务键 —— 主力路径；
     *   ③ 退到服务键（同资产同端口只算一次最高权限，即 G1 的兜底）；
     *   ④ 都拿不到就退回**本条命中自己的 id**，即「互不相同」各算各的。
     *      ⚠️ 不能用随机值：同一命中在面板/报告/攻击链里必须拿到同一个键，否则口径不稳定。
     */
    fun systemKeyOf(hit: ScoreHit, assetName: String? = null): String {
        val svc = scoreServiceKey(hit).orEmpty()
        val explicit = hit.systemKey?.trim().orEmpty()
        if (explicit.isNotEmpty() && (svc.isEmpty() || explicit.contains(svc))) {
            val head = explicit.substringBefore('|')
            if (svc.isNotEmpty() || head.trim().isNotEmpty()) return explicit
        }
        val name = assetName?.trim()?.takeIf { it.isNotEmpty() }
        if (name != null) return "$name|$svc"
        if (svc.isNotEmpty()) return svc
        return "#hit${hit.id}"
    }

    private fun legacyCapGroup(code: String?): ScoreDedupScope? {
        val c = code?.trim().orEmpty()
        if (c == "web-account-user" || c == "web-account-admin") return ScoreDedupScope.SERVICE
        return if (c in LEGACY_SERVICE_CAPPED) ScoreDedupScope.SERVICE else null
    }

    /** 从一组同口径命中里挑「计分的那一条」：分值最高，同分取更早记的（id 小的）。 */
    private fun pickBestHit(list: List<ScoredHit>): ScoredHit = list.reduce { a, b ->
        if (b.points != a.points) (if (b.points > a.points) b else a)
        else (if (b.hit.id < a.hit.id) b else a)
    }

    // ────────────────────────────── 封顶与去重 ──────────────────────────────

    /**
     * 封顶与去重的**唯一实现**。
     *
     * ① 计分口径去重：同一口径键内只保留分值最高一条（同分取更早记的）；
     * ② 规则上限：同一 rule 的计分命中累计不得超过 cap，按分值从高到低累计，超出的标 capped。
     *
     * `selfCreated`（自己注册/自建的账号）本来就不计分，既不参与竞争也不占位。
     */
    fun applyScoreCaps(
        hits: List<ScoreHit>,
        assetNames: Map<Long, String> = emptyMap(),
        pointsByCode: Map<String, ScorePoint> = POINTS_BY_CODE,
    ): ScoreBoard {
        val flagged = hits.map { hit ->
            val meta = pointsByCode[hit.code]
            val legacy = legacyCapGroup(hit.code)
            ScoredHit(
                hit = hit,
                points = hitPointsOf(hit),
                rule = meta?.rule,
                ruleCap = meta?.cap?.takeIf { it > 0 } ?: 0,
                dedupScope = meta?.dedupScope ?: legacy ?: ScoreDedupScope.NONE,
                capGroup = if (meta?.rule == null) "code:${hit.code}" else "rule:${meta.rule}",
                systemKey = systemKeyOf(hit, hit.assetId?.let { assetNames[it] }),
            )
        }.toMutableList()

        var cappedCount = 0

        /* ① 去重 */
        val buckets = LinkedHashMap<String, MutableList<Int>>()
        flagged.forEachIndexed { index, r ->
            if (r.hit.selfCreated) return@forEachIndexed
            val scope = r.dedupScope
            if (scope == ScoreDedupScope.NONE) return@forEachIndexed
            val key = when (scope) {
                ScoreDedupScope.TARGET -> "target"
                ScoreDedupScope.SYSTEM -> "sys:" + r.systemKey
                else -> {
                    // 认不出服务就各算各的：宁可少封顶也不能错封。
                    val svc = scoreServiceKey(r.hit) ?: return@forEachIndexed
                    if (svc.isEmpty()) return@forEachIndexed
                    "svc:$svc"
                }
            }
            if (key.isEmpty() || key == "svc:null" || key == "sys:null") return@forEachIndexed
            buckets.getOrPut("${r.capGroup}|$key") { mutableListOf() } += index
        }
        buckets.values.forEach { indices ->
            if (indices.size < 2) return@forEach
            val group = indices.map { flagged[it] }
            val best = pickBestHit(group)
            indices.forEach { i ->
                val r = flagged[i]
                if (r.hit.id == best.hit.id) return@forEach
                flagged[i] = r.copy(
                    capped = true,
                    cappedById = best.hit.id,
                    cappedByPoints = best.points,
                    cappedReasonKind = "dedup",
                )
                cappedCount++
            }
        }

        /* ② 规则上限 */
        val byRule = LinkedHashMap<String, MutableList<Int>>()
        flagged.forEachIndexed { index, r ->
            if (r.capped || r.hit.selfCreated || r.ruleCap <= 0) return@forEachIndexed
            byRule.getOrPut(r.capGroup) { mutableListOf() } += index
        }
        val caps = LinkedHashMap<String, ScoreCapUsage>()
        byRule.forEach { (group, indices) ->
            val cap = flagged[indices.first()].ruleCap
            val ordered = indices.sortedWith(
                compareByDescending<Int> { flagged[it].points }.thenBy { flagged[it].hit.id },
            )
            var used = 0
            var nCapped = 0
            ordered.forEach { i ->
                val r = flagged[i]
                if (used + r.points > cap) {
                    flagged[i] = r.copy(capped = true, cappedReasonKind = "cap", cappedByPoints = null)
                    nCapped++
                    cappedCount++
                } else {
                    used += r.points
                }
            }
            caps[group] = ScoreCapUsage(flagged[indices.first()].rule, cap, used, nCapped)
        }

        return ScoreBoard(flagged.toList(), caps, cappedCount)
    }

    /**
     * 端到端评估：面板 / 报告 / 攻击链都调这一个。
     * 与 applyScoreCaps 的区别是它会先用得分点元数据把每行的实际分值补齐。
     */
    fun evaluateScoreBoard(
        hits: List<ScoreHit>,
        assetNames: Map<Long, String> = emptyMap(),
        pointsByCode: Map<String, ScorePoint> = POINTS_BY_CODE,
    ): ScoreBoard = applyScoreCaps(hits, assetNames, pointsByCode)

    // ────────────────────────────── 数据量 ──────────────────────────────

    /**
     * 从证据文本解析数据量（行/条/记录数）。
     * **只认带量词或中文量级**的写法——裸数字不认，否则会把口令里的 `123456`、端口号当数据量。
     * 解析不出返回 null（提示补齐量级，不猜、不编数）。
     */
    fun parseRowCount(evidence: String?): Long? {
        val text = evidence.orEmpty()
        if (text.isBlank()) return null
        val units = mapOf("亿" to 1e8, "千万" to 1e7, "百万" to 1e6, "十万" to 1e5, "万" to 1e4, "千" to 1e3)
        val nums = mutableListOf<Long>()
        Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(亿|千万|百万|十万|万|千)?\\s*(条|行|记录|数据|records?|rows?|entries)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { m ->
                val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
                val mult = units[m.groupValues[2]] ?: 1.0
                nums += Math.round(base * mult)
            }
        if (nums.isNotEmpty()) return nums.maxOrNull()
        Regex("(\\d[\\d,]*(?:\\.\\d+)?)\\s*(亿|千万|百万|十万|万|千)")
            .findAll(text)
            .forEach { m ->
                val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return@forEach
                nums += Math.round(base * units.getValue(m.groupValues[2]))
            }
        return nums.maxOrNull()
    }

    fun formatRows(n: Long): String = "%,d".format(n)

    /** 千分位展示（Int 便利重载）。 */
    fun formatRows(n: Int): String = formatRows(n.toLong())
}