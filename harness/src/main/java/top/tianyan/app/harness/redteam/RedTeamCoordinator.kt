package top.tianyan.app.harness.redteam

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.database.RedTeamFactRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.model.RedTeamFactKind
import top.tianyan.app.core.model.RedTeamPreflightResult
import top.tianyan.app.core.model.RedTeamIpUtils
import top.tianyan.app.core.model.RedTeamReportReplay
import top.tianyan.app.core.model.RedTeamRole
import top.tianyan.app.core.model.RedTeamScoreCatalog
import top.tianyan.app.core.model.RedTeamScoring
import top.tianyan.app.core.model.ScoreHit
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus

@Singleton
class RedTeamCoordinator @Inject constructor(
    private val facts: RedTeamFactRepository,
    private val sessions: HarnessSessionRepository,
    private val events: HarnessEventBus,
) {
    /** Per-session agent slots. The registry lives in memory; the fact store survives restarts. */
    private val slotLock = Mutex()
    private val reservations = java.util.concurrent.ConcurrentHashMap<String, MutableList<SlotReservation>>()

    private data class SlotReservation(val key: String, val label: String, val at: Long)

    /** 会话级角色提示词覆盖；未设置时回落到内置职责描述，进程重启后回到内置值。 */
    private val rolePrompts = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, String>>()

    suspend fun record(
        sessionId: String,
        kind: RedTeamFactKind,
        title: String,
        target: String? = null,
        severity: String? = null,
        status: String = "observed",
        payload: String = "{}",
        id: String = UUID.randomUUID().toString(),
    ): RedTeamFactEntity = withContext(Dispatchers.IO) {
        requireBoundSession(sessionId)
        val now = System.currentTimeMillis()
        val fact = RedTeamFactEntity(sessionId, id, kind.id, title, target, severity, status, payload, now, now)
        facts.upsert(fact)
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, now, id, kind.id, status))
        fact
    }

    suspend fun bind(sessionId: String, target: String, scope: String): RedTeamFactEntity = withContext(Dispatchers.IO) {
        require(target.isNotBlank()) { "target is required" }
        require(scope.isNotBlank()) { "scope must be explicitly confirmed" }
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        val now = System.currentTimeMillis()
        sessions.updateRedTeamBinding(sessionId, "red_team", target.trim(), scope.trim(), "recon", now)
        val fact = RedTeamFactEntity(sessionId, "target-binding", "engagement", "目标已绑定", target.trim(), status = "confirmed", payload = JsonObject(mapOf("scope" to kotlinx.serialization.json.JsonPrimitive(scope.trim()))).toString(), createdAt = now, updatedAt = now)
        facts.upsert(fact)
        events.emit(HarnessEvent.RedTeamPhaseChanged(sessionId, now, "recon", "目标已确认并绑定到当前会话"))
        fact
    }

    suspend fun sessionInfo(sessionId: String): String {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        return "session=${session.id}\ntarget=${session.redTeamTarget.orEmpty()}\nscope=${session.redTeamScope}\nphase=${session.redTeamPhase}"
    }

    fun requiredRoles(): List<RedTeamRole> = RedTeamRole.entries
    suspend fun recent(sessionId: String, limit: Int = 100): List<RedTeamFactEntity> = facts.recent(sessionId, limit)

    fun preflight(requiredEnvironment: Map<String, String?>, availableTools: Set<String>): RedTeamPreflightResult {
        val missing = requiredEnvironment.filterValues { it.isNullOrBlank() }.keys.map { "缺少环境变量: $it" }
        val warnings = if (availableTools.isEmpty()) listOf("没有发现已配置的红队工具") else emptyList()
        return RedTeamPreflightResult(availableTools.sorted(), missing, warnings)
    }

    suspend fun execute(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        return runCatching {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            when (action) {
                "session_info" -> sessionInfo(sessionId)
                "agent_slot" -> agentSlot(sessionId, args)
                "roles" -> roleBrief(sessionId)
                "preflight" -> {
                    val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
                    require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
                    val result = preflight(
                        requiredEnvironment = emptyMap(),
                        availableTools = setOf("base", "process", "invoke_subagent", "mcp"),
                    )
                    "ready=${result.ready}\navailable=${result.available.joinToString(",")}\nmissing=${result.missing.joinToString(",")}\nwarnings=${result.warnings.joinToString(",")}"
                }
                "fact_add", "asset_add", "vuln_add", "credential_add", "access_add", "webshell_add", "tunnel_add", "chain_add", "attack_file_add", "score_hit", "poc_add", "http_evidence_add", "knowledge_add", "skill_add",
                "vuln_update", "tunnel_update", "webshell_update", "poc_update", "asset_update", "credential_update", "access_update",
                "asset_link",
                -> {
                    val actionKind = when (action) {
                        "asset_add", "asset_update" -> "asset"
                        "vuln_add", "vuln_update" -> "vulnerability"
                        "credential_add", "credential_update" -> "credential"
                        "access_add", "access_update" -> "access_session"
                        "webshell_add", "webshell_update" -> "webshell"
                        "tunnel_add", "tunnel_update" -> "tunnel"
                        "chain_add" -> "attack_step"
                        "attack_file_add" -> "attack_file"
                        "score_hit" -> "score_hit"
                        "poc_add", "poc_update" -> "knowledge"
                        "http_evidence_add" -> "event"
                        "knowledge_add" -> "knowledge"
                        "skill_add" -> "skill"
                        "asset_link" -> "edge"
                        else -> args["kind"]?.jsonPrimitive?.contentOrNull
                    }
                    val kind = RedTeamFactKind.entries.firstOrNull { it.id == actionKind }
                        ?: error("unsupported fact kind")
                    // 更新类动作强制要求 id：没有 id 就成了「再插一条」，会污染图谱与去重。
                    val isUpdate = action.endsWith("_update")
                    val explicitId = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    require(!isUpdate || explicitId != null) { "$action requires id" }
                    // 结构化字段原样落库，保留上游 schema（ip/port/service/fingerprint/provenance/relation…）。
                    val fact = record(
                        sessionId = sessionId,
                        kind = kind,
                        title = args["title"]?.jsonPrimitive?.contentOrNull.orEmpty().also { require(it.isNotBlank()) { "title is required" } },
                        target = args["target"]?.jsonPrimitive?.contentOrNull
                            ?: args["ip"]?.jsonPrimitive?.contentOrNull
                            ?: args["host"]?.jsonPrimitive?.contentOrNull
                            ?: args["domain"]?.jsonPrimitive?.contentOrNull,
                        severity = args["severity"]?.jsonPrimitive?.contentOrNull,
                        status = args["status"]?.jsonPrimitive?.contentOrNull ?: "observed",
                        payload = JsonObject(args.filterKeys { it !in CONTROL_KEYS }).toString(),
                        id = explicitId ?: UUID.randomUUID().toString(),
                    )
                    "已保存 ${fact.kind} 事实 ${fact.id}"
                }
                "fact_query", "asset_query", "vuln_query", "credential_list", "access_list", "webshell_list", "tunnel_list", "chain", "attack_chain", "attack_file_list", "score_list", "poc_search", "poc_list", "report_targets", "report" -> {
                    val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
                    require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
                    val requestedKind = when (action) {
                        "asset_query" -> "asset"
                        "vuln_query" -> "vulnerability"
                        "credential_list" -> "credential"
                        "access_list" -> "access_session"
                        "webshell_list" -> "webshell"
                        "tunnel_list" -> "tunnel"
                        "chain", "attack_chain" -> "attack_step"
                        "attack_file_list" -> "attack_file"
                        "score_list", "score_report" -> "score_hit"
                        "poc_search", "poc_list" -> "knowledge"
                        else -> args["kind"]?.jsonPrimitive?.contentOrNull
                    }
                    val items = recent(sessionId, 500).filter { fact ->
                        requestedKind.isNullOrBlank() || fact.kind == requestedKind
                    }
                    if (action == "report") {
                        buildReport(sessionId, session.redTeamTarget.orEmpty(), items)
                    } else {
                        items.joinToString("\n") { "${it.kind} | ${it.title} | ${it.severity ?: "-"} | ${it.status} | ${it.target.orEmpty()}" }
                            .ifBlank { "当前会话暂无事实记录" }
                    }
                }
                "asset_graph" -> assetGraph(sessionId, args)
                "score_report" -> scoreReport(sessionId)
                "score_points" -> scorePointList()
                "role_dispatch" -> roleDispatch(sessionId, args)
                // 存量评估/并发验证：只登记结论，真正的主动探测仍需走已审批的 base/process。
                "asset_assess" -> assessAsset(sessionId, args)
                "asset_test" -> testAsset(sessionId, args)
                "group_slot" -> groupSlot(sessionId)
                "domain_index" -> domainIndex(sessionId)
                "asset_stats" -> assetStats(sessionId)
                "asset_timeline" -> assetTimeline(sessionId, args)
                "web_list" -> webList(sessionId)
                "attack_path" -> attackPath(sessionId, args)
                "asset_get", "poc_get", "vuln_get" -> singleFact(sessionId, args)
                "poc_use" -> usePoc(sessionId, args)
                "role_prompt" -> rolePrompt(sessionId, args)
                "role_prompt_reset" -> rolePromptReset(sessionId, args)
                "sessions" -> "当前会话 ${sessionId}（红队模式的目标与事实均为会话级，不跨会话共享）"
                "session_check", "engagement_open", "session_bind" -> sessionCheck(sessionId)
                else -> error("unsupported redteam action: $action")
            }
        }.fold(onSuccess = { true to it }, onFailure = { false to (it.message ?: "redteam tool failed") })
    }

    private suspend fun agentSlot(sessionId: String, args: JsonObject): String = slotLock.withLock {
        val max = MAX_CONCURRENT_AGENTS
        val held = reservations.getOrPut(sessionId) { mutableListOf() }
        val action = args["sub_action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val used = held.size
        val free = (max - used).coerceAtLeast(0)
        when (action) {
            "status" -> buildString {
                appendLine("max=$max")
                appendLine("used=$used")
                appendLine("free=$free")
                appendLine("reservations=${held.joinToString(";") { it.key }}")
                append(if (free == 0) "名额已满，等现有智能体回报后再派" else "还有 $free 个名额")
            }
            "acquire" -> {
                if (free <= 0) {
                    "ok=false\nmax=$max\nerror=并发已满（最多 $max 个），先等当前在跑的智能体回报或 release 掉已结束的。"
                } else {
                    val label = args["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "slot"
                    val slot = SlotReservation("$label#${System.currentTimeMillis()}", label, System.currentTimeMillis())
                    held += slot
                    "ok=true\nslot=${slot.key}\nused=${held.size}\nfree=${(max - held.size).coerceAtLeast(0)}\n派完活记得 release。已在会话事实库登记。"
                }
            }
            "release" -> {
                val key = args["key"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                val removed = if (key == null) held.minByOrNull { it.at } else held.firstOrNull { it.key == key }
                if (removed != null) held.remove(removed)
                "ok=true\nreleased=${removed?.key ?: "none"}\nused=${held.size}\nfree=${(max - held.size).coerceAtLeast(0)}"
            }
            else -> error("agent_slot sub_action must be status/acquire/release")
        }
    }

    private suspend fun roleBrief(sessionId: String): String {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("scope=${session.redTeamScope}")
            RedTeamRole.entries.forEach { role ->
                appendLine("- ${role.id}｜${role.displayName}：${ROLE_DUTIES.getValue(role)}")
            }
            append("派活用 invoke_subagent；并发上限 $MAX_CONCURRENT_AGENTS，派前先 agent_slot status。")
        }
    }

    /** 报告里的评分段直接嵌 scoreReport 的输出，保证与面板口径一致。 */
    private suspend fun buildReport(sessionId: String, target: String, items: List<RedTeamFactEntity>): String = buildString {
        appendLine("# Red-Team Engagement Report")
        appendLine("Target: $target")
        appendLine("Generated: ${java.time.Instant.now()}")
        appendLine("Facts: ${items.size}")
        items.groupBy { it.kind }.toSortedMap().forEach { (kind, facts) ->
            appendLine()
            appendLine("## $kind (${facts.size})")
            facts.forEach { appendLine("- [${it.severity ?: it.status}] ${it.title}${it.target?.let { value -> " · $value" } ?: ""}") }
        }
        // 评分段直接复用 scoreReport：报告与面板必须给出同一个总分。
        if (items.any { it.kind == "score_hit" }) {
            appendLine()
            appendLine("## 评分")
            appendLine(scoreReport(sessionId))
        }
    }

    /**
     * 资产图谱：C 段 → 资产 → 开放端口，外加显式写入的关系边。
     * 节点数超过上限时按上游语义拒绝并回吐 C 段摘要，让调用方缩小范围而不是拿到截断的图。
     */
    private suspend fun assetGraph(sessionId: String, args: JsonObject): String {
        val session = requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val cidrFilter = args["cidr"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val maxNodes = (args["maxNodes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_MAX_NODES)
            .coerceIn(1, HARD_MAX_NODES)

        val assets = all.filter { it.kind == "asset" }
            .filter { cidrFilter == null || segmentOf(it.target) == cidrFilter }
        val edges = all.filter { it.kind == "edge" }
        val segments = assets.mapNotNull { segmentOf(it.target) }.distinct().sorted()

        val nodes = buildList {
            segments.forEach { add("segment|$it") }
            assets.forEach { add("asset|${it.target ?: it.id}") }
            edges.forEach {
                edgeEndpoints(it)?.let { (src, dst) -> add("edge|$src->$dst") }
            }
        }.distinct()

        if (nodes.size > maxNodes) {
            return buildString {
                appendLine("ok=false")
                appendLine("error=图谱节点过多（${nodes.size} > $maxNodes），请指定 cidr 缩小范围")
                appendLine("segments=" + segments.joinToString(",") { "$it(${assets.count { a -> segmentOf(a.target) == it }})" })
                append("target=${session.redTeamTarget.orEmpty()}")
            }
        }
        return buildString {
            appendLine("ok=true")
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("nodes=${nodes.size} edges=${edges.size}")
            appendLine("## segments")
            segments.forEach { seg -> appendLine("- $seg (${assets.count { segmentOf(it.target) == seg }})") }
            appendLine("## assets")
            assets.forEach { appendLine("- ${it.target ?: it.id} | ${it.status} | ${it.title}") }
            appendLine("## edges")
            edges.forEach { edge ->
                val (src, dst) = edgeEndpoints(edge) ?: (edge.target.orEmpty() to "?")
                appendLine("- ${edge.title} | $src -> $dst | ${edge.status}")
            }
        }
    }

    /** 域名解析索引：域名 → 已解析 IP（来自 asset_link 的 resolves 边或域名资产自身的 target）。 */
    private suspend fun domainIndex(sessionId: String): String {
        val session = requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val domains = all.filter { it.kind == "asset" && it.payload.contains("\"domain\"") }
        val resolves = all.filter { it.kind == "edge" && it.title.contains("resolves", ignoreCase = true) }
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("domains=${domains.size} resolves=${resolves.size}")
            domains.forEach { appendLine("- ${it.target ?: it.title} | ${it.status}") }
            resolves.forEach { appendLine("- ${it.title} | ${it.target.orEmpty()}") }
            if (domains.isEmpty() && resolves.isEmpty()) append("暂无可索引的域名记录")
        }
    }

    private suspend fun assetStats(sessionId: String): String {
        val session = requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val byKind = all.groupingBy { it.kind }.eachCount().toSortedMap()
        val byStatus = all.filter { it.kind == "asset" }.groupingBy { it.status }.eachCount().toSortedMap()
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("phase=${session.redTeamPhase}")
            appendLine("## 按类型")
            byKind.forEach { (kind, count) -> appendLine("- $kind: $count") }
            appendLine("## 资产按状态")
            byStatus.forEach { (status, count) -> appendLine("- $status: $count") }
            appendLine("segments=${all.mapNotNull { if (it.kind == "asset") segmentOf(it.target) else null }.distinct().size}")
        }
    }

    private suspend fun assetTimeline(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val key = args["target"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val items = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { key == null || it.target == key }
            .sortedBy { it.updatedAt }
        return items.joinToString("\n") {
            "${java.time.Instant.ofEpochMilli(it.updatedAt)} | ${it.kind} | ${it.title} | ${it.status}"
        }.ifBlank { "该目标暂无时间线记录" }
    }

    /** Web 暴露面清单：端口/URL 类资产，供移动端快速确认外网可达面。 */
    private suspend fun webList(sessionId: String): String {
        val session = requireBoundRedTeam(sessionId)
        val web = recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "asset" && isWebAsset(it) }
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("web=${web.size}")
            web.forEach { appendLine("- ${it.target ?: it.id} | ${it.title} | ${it.status}") }
            if (web.isEmpty()) append("暂无 Web 暴露面记录")
        }
    }

    /** 攻击路径：从攻击步/边推导出的有序链路，按写入顺序回放。 */
    private suspend fun attackPath(sessionId: String, args: JsonObject): String {
        val session = requireBoundRedTeam(sessionId)
        val steps = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == "attack_step" || it.kind == "edge" }
            .sortedBy { it.updatedAt }
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("steps=${steps.size}")
            steps.forEachIndexed { index, step ->
                appendLine("${index + 1}. [${step.status}] ${step.title}${step.target?.let { t -> " · $t" } ?: ""}")
            }
            if (steps.isEmpty()) append("暂无攻击路径记录")
        }
    }

    /**
     * 评分报告：把会话内的 score_hit 事实转成命中行，交给评分引擎算封顶与去重。
     *
     * 面板、报告、攻击链三处必须共用这一个实现——三处各写一遍必然漂移成不同口径，
     * 同一份战果会算出三个总分（上游为此专门把规则抽成唯一实现）。
     */
    /**
     * 角色派发：把「角色提示词 + 已绑定目标与 scope + 当前资产/评分态势 + 并发闸门」
     * 合成一份可以直接交给 `invoke_subagent` 的任务描述。
     *
     * 上游的做法是让调用方自己把 role_prompt 取出来塞进任务描述，漏一步就派了个没有角色约束的
     * 裸子代理。这里把它收成一个动作：调用方拿到的就是能直接派出去的文本，并同时占好槽位。
     */
    private suspend fun roleDispatch(sessionId: String, args: JsonObject): String {
        val session = requireBoundRedTeam(sessionId)
        val roleId = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: error("role is required")
        val role = RedTeamRole.fromId(roleId)
            ?: error("unknown role: $roleId (${RedTeamRole.entries.joinToString("/") { it.id }})")
        require(role != RedTeamRole.PLANNER) { "plan 是主会话本身，不能派给自己；可派角色：${RedTeamRole.dispatchable.joinToString("/") { it.id }}" }

        val task = args["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(task.isNotEmpty()) { "task is required：说清这一轮要这个角色交付什么" }

        val override = rolePrompts[sessionId]?.get(role.id)
        val duty = override ?: ROLE_DUTIES.getValue(role)

        // 派发即占位：避免「先查名额、再派、中间被别人抢走」的竞态。
        val slot = slotLock.withLock {
            val held = reservations.getOrPut(sessionId) { mutableListOf() }
            if (held.size >= MAX_CONCURRENT_AGENTS) {
                return@withLock null
            }
            val reservation = SlotReservation("${role.id}#${System.currentTimeMillis()}", role.id, System.currentTimeMillis())
            held += reservation
            reservation
        } ?: return "ok=false\nerror=并发已满（最多 $MAX_CONCURRENT_AGENTS 个），先等当前在跑的角色回报或 release 掉已结束的。"

        val context = engagementBrief(sessionId)
        return buildString {
            appendLine("ok=true")
            appendLine("slot=${slot.key}")
            appendLine("role=${role.id}｜${role.displayName}")
            appendLine("## 交给 invoke_subagent 的任务描述")
            appendLine("---")
            appendLine("你是本次授权红队演练中的「${role.displayName}」角色。")
            appendLine()
            appendLine("【角色约束】")
            appendLine(duty)
            appendLine()
            appendLine("【本次任务】")
            appendLine(task)
            appendLine()
            appendLine("【授权范围】")
            appendLine("目标：${session.redTeamTarget.orEmpty()}")
            appendLine("范围：${session.redTeamScope}")
            appendLine("只在上述范围与当前会话审批模式下动作；越界目标一律不碰。")
            appendLine()
            appendLine("【当前态势】")
            appendLine(context)
            appendLine()
            appendLine("【交付要求】")
            appendLine("产出通过 redteam 工具写回当前会话（asset_add/vuln_add/credential_add/…），")
            appendLine("评分相关命中写 score_hit 并填 code/points/asset_id/port；结束时回一句结论与剩余攻击面。")
            appendLine("---")
            appendLine("派完活后：invoke_subagent 返回即视为本轮结束，记得 agent_slot release key=${slot.key}。")
        }
    }

    /** 已有多少个角色在跑，以及还能派几个。 */
    private suspend fun groupSlot(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val held = slotLock.withLock { reservations.getOrPut(sessionId) { mutableListOf() }.toList() }
        return buildString {
            appendLine("max=$MAX_CONCURRENT_AGENTS")
            appendLine("used=${held.size}")
            appendLine("free=${(MAX_CONCURRENT_AGENTS - held.size).coerceAtLeast(0)}")
            appendLine("## 在跑的角色")
            held.forEach { appendLine("- ${it.label} | ${it.key}") }
            append("可派角色：${RedTeamRole.dispatchable.joinToString("/") { it.id }}")
        }
    }

    /** 派活前给子代理看的态势摘要：目标、C 段、资产/漏洞计数、当前得分。 */
    private suspend fun engagementBrief(sessionId: String): String {
        val facts = recent(sessionId, MAX_FACTS_SCANNED)
        val board = RedTeamScoring.applyScoreCaps(hitRows(sessionId))
        val segments = facts.filter { it.kind == "asset" }
            .mapNotNull { segmentOf(it.target) }.distinct()
        return buildString {
            appendLine("资产 ${facts.count { it.kind == "asset" }} 条，覆盖 C 段 ${segments.size} 个${if (segments.isNotEmpty()) "（${segments.take(5).joinToString(", ")}${if (segments.size > 5) " …" else ""}）" else ""}")
            appendLine("漏洞 ${facts.count { it.kind == "vulnerability" }} 条，凭据 ${facts.count { it.kind == "credential" }} 条，访问会话 ${facts.count { it.kind == "access_session" }} 个")
            appendLine("当前评分合计 ${board.totalPoints} 分（命中 ${board.items.size} 条，被封顶 ${board.cappedCount} 条）")
        }
    }

    /**
     * 资产测试登记（上游 asset_test）：维护「未测试 / 测试中 / 已测试 / 被封禁 / 已放弃 / 无攻击面」状态机。
     *
     * 两个字段的语义必须分清，否则资产测绘页会显示成错误的进度：
     *   · `test` 是**追加式**的测试记录（每次调用往后接，保留完整试错过程）；
     *   · `surface` 是**覆盖式**的剩余攻击面（只关心当前还剩什么）。
     * `blocked=true` 时封禁计数 +1，用于判断该资产是否已被 WAF 盯上。
     */
    private suspend fun testAsset(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val assetKey = assetKeyOf(args, "asset_test")
        val status = args["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        require(status == null || status in ASSET_TEST_STATUSES) {
            "status 必须是 ${ASSET_TEST_STATUSES.joinToString("/")}"
        }

        val time = System.currentTimeMillis()
        val existing = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { it.id == assetKey }
        val prior = payloadOf(existing)
        // `notes` 是上游老提示词里的兼容别名，效果与 `test` 相同。
        val entry = listOfNotNull(
            args["test"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
            args["notes"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() },
        ).joinToString("；")

        val priorLog = prior["test_log"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val priorBlocked = prior["blocked_count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val surface = args["surface"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val blockedNow = args["blocked"]?.jsonPrimitive?.contentOrNull == "true"
        val blockedCount = priorBlocked + if (blockedNow) 1 else 0

        val merged = prior
            .putJson("test_log", if (entry.isEmpty()) priorLog else listOf(priorLog, "[$time] $entry").filter { it.isNotEmpty() }.joinToString("\n"))
            .putJson("blocked_count", blockedCount.toString())
            .let { if (surface != null) it.putJson("surface", surface) else it }
            .let { if (status != null) it.putJson("test_status", status) else it }

        val fact = RedTeamFactEntity(
            sessionId = sessionId,
            id = assetKey,
            kind = "asset",
            title = existing?.title ?: args["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: assetKey,
            target = existing?.target ?: args["ip"]?.jsonPrimitive?.contentOrNull,
            severity = existing?.severity,
            status = status ?: existing?.status ?: "observed",
            payload = merged.toString(),
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
        )
        facts.upsert(fact)
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, time, fact.id, fact.kind, fact.status))
        return "ok=true\nid=${fact.id}\nstatus=${fact.status}\nblocked_count=$blockedCount"
    }

    /**
     * 易打性评估（上游 asset_assess）：预期成果、优先级、判断理由。
     * 信息收集收口时对每个资产调用一次，供指挥者按性价比排序派活。
     */
    private suspend fun assessAsset(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val assetKey = assetKeyOf(args, "asset_assess")
        val priority = args["priority"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        require(priority == null || priority in ASSESS_PRIORITIES) {
            "priority 必须是 ${ASSESS_PRIORITIES.joinToString("/")}"
        }

        val time = System.currentTimeMillis()
        val existing = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { it.id == assetKey }
        val merged = payloadOf(existing)
            .let { obj -> priority?.let { obj.putJson("priority", it) } ?: obj }
            .let { obj -> args["potential"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { obj.putJson("potential", it) } ?: obj }
            .let { obj -> args["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { obj.putJson("assess_reason", it) } ?: obj }

        val fact = RedTeamFactEntity(
            sessionId = sessionId,
            id = assetKey,
            kind = "asset",
            title = existing?.title ?: args["title"]?.jsonPrimitive?.contentOrNull ?: assetKey,
            target = existing?.target ?: args["ip"]?.jsonPrimitive?.contentOrNull,
            severity = existing?.severity,
            status = existing?.status ?: "observed",
            payload = merged.toString(),
            createdAt = existing?.createdAt ?: time,
            updatedAt = time,
        )
        facts.upsert(fact)
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, time, fact.id, fact.kind, fact.status))
        return "ok=true\nid=${fact.id}\npriority=${priority ?: "-"}"
    }

    /** 资产类动作可以用 id / asset_id / ip 任一定位；三样都没有就报错，不要静默新建一条无名资产。 */
    private fun assetKeyOf(args: JsonObject, action: String): String =
        listOf("id", "asset_id", "ip").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        } ?: error("$action 需要 id / asset_id / ip 指定资产")

    private fun payloadOf(fact: RedTeamFactEntity?): JsonObject =
        runCatching { Json.parseToJsonElement(fact?.payload ?: "{}") as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())

    private fun JsonObject.putJson(key: String, value: String): JsonObject =
        JsonObject(this + (key to kotlinx.serialization.json.JsonPrimitive(value)))

    /**
     * 每条计分的复现入口：报告里不能只有「没有原始请求记录」。
     *
     * 数据其实都在事实库里（攻击步骤的 tool、漏洞的目标 URL、HTTP 证据的原始报文），
     * 这里把它整理成能重放的报文与能直接跑的命令。真实抓到的证据优先原样给出——
     * 合成的必须标注 `synthesized`，推断与实证在可信度上不是一回事。
     */
    private suspend fun replayBrief(sessionId: String): String {
        val facts = recent(sessionId, MAX_FACTS_SCANNED)
        val evidence = facts.filter { it.kind == "event" }
        val steps = facts.filter { it.kind == "attack_step" }
        val vulns = facts.filter { it.kind == "vulnerability" }

        return buildString {
            appendLine("## 复现入口")
            if (evidence.isEmpty() && steps.isEmpty()) {
                appendLine("暂无复现材料。补录方式：用 http_evidence_add 保存原始请求与响应，")
                appendLine("或用 chain_add 记录攻击步骤的 tool 与目标 URL。")
                return@buildString
            }
            evidence.forEach { fact ->
                val payload = payloadOf(fact)
                val raw = payload["request"]?.jsonPrimitive?.contentOrNull
                appendLine("- 证据：${fact.title}")
                if (!raw.isNullOrBlank()) {
                    // 真实抓包：原样给出，不做任何加工。
                    appendLine("  原始请求（真实抓包）：")
                    raw.lines().forEach { appendLine("    $it") }
                } else {
                    val url = fact.target ?: payload["url"]?.jsonPrimitive?.contentOrNull
                    val synthesized = RedTeamReportReplay.buildHttpRequest(
                        url = url,
                        method = payload["method"]?.jsonPrimitive?.contentOrNull,
                        data = payload["body"]?.jsonPrimitive?.contentOrNull,
                    )
                    if (synthesized != null) {
                        appendLine("  合成请求（synthesized=true，非抓包，仅按已记录信息推断）：")
                        synthesized.text.lines().forEach { appendLine("    $it") }
                    } else {
                        appendLine("  缺原始请求与可解析 URL，无法合成；请用 http_evidence_add 补录。")
                    }
                }
            }
            (steps + vulns).forEach { fact ->
                val tool = payloadOf(fact)["tool"]?.jsonPrimitive?.contentOrNull
                val url = fact.target ?: payloadOf(fact)["url"]?.jsonPrimitive?.contentOrNull
                val cmd = RedTeamReportReplay.buildCurlCommand(tool = tool, url = url)
                if (cmd != null) appendLine("- 复现命令（${fact.title}）：$cmd")
            }
        }
    }

    private suspend fun scoreReport(sessionId: String): String {
        val session = requireBoundRedTeam(sessionId)
        val hits = hitRows(sessionId)
        val board = RedTeamScoring.applyScoreCaps(hits)
        val byCode = board.items.groupBy { it.hit.code }

        return buildString {
            appendLine("ok=true")
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("hits=${board.items.size} capped=${board.cappedCount}")
            appendLine("total=${board.totalPoints}")
            appendLine()
            appendLine("## 计分明细")
            byCode.forEach { (code, items) ->
                val point = RedTeamScoring.POINTS_BY_CODE[code]
                val label = point?.name ?: code
                val scored = items.filterNot { it.capped }
                appendLine("- $label（$code）计分 ${scored.size}/${items.size} · ${scored.sumOf { it.points }}/${point?.cap ?: 0}")
                items.forEach { row ->
                    val service = RedTeamScoring.serviceLabel(row.hit)
                    if (row.capped) {
                        val why = when (row.cappedReasonKind) {
                            "dedup" -> "被同口径更高分命中压住（#${row.cappedById}）"
                            else -> "超出该项上限"
                        }
                        appendLine("  · 不计分 ${row.points}分 · $service · $why")
                    } else {
                        appendLine("  · 计分 ${row.points}分 · $service")
                    }
                }
            }
            if (board.caps.isNotEmpty()) {
                appendLine()
                appendLine("## 上限用量")
                board.caps.forEach { (group, usage) ->
                    appendLine("- $group：已用 ${usage.used}/${usage.cap}${if (usage.cappedCount > 0) "（封顶 ${usage.cappedCount} 条）" else ""}")
                }
            }
            appendLine()
            appendLine("## 通用规则")
            RedTeamScoreCatalog.GENERAL_RULES.forEach { appendLine("- ${it.code} ${it.name}：${it.detail}") }
            appendLine()
            append(replayBrief(sessionId))
        }
    }

    /** 得分点清单：模型据此判档位，避免自己编分值。 */
    private fun scorePointList(): String = buildString {
        RedTeamScoreCatalog.GROUPS.forEach { group ->
            val points = RedTeamScoring.DEFAULT_POINTS.filter { it.category == group.code }
            if (points.isEmpty()) return@forEach
            appendLine("## ${group.name}（${group.code}）")
            points.forEach { p ->
                appendLine("- ${p.code}｜${p.name}｜档位 ${p.tier}｜上限 ${if (p.cap > 0) p.cap else "不限"}｜口径 ${p.dedupScope.id}")
            }
        }
    }

    /**
     * 把 score_hit 事实还原成命中行。事实是幂等覆盖写入的，
     * 所以同一 id 重复提交只会更新分值，不会重复计分。
     */
    private suspend fun hitRows(sessionId: String): List<ScoreHit> =
        recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == "score_hit" }
            .map { fact ->
                val payload = runCatching { Json.parseToJsonElement(fact.payload) as? JsonObject }.getOrNull()
                val code = payload?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: fact.title
                ScoreHit(
                    id = stableId(fact.id),
                    code = code,
                    points = payload?.get("points")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    assetId = payload?.get("asset_id")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    port = payload?.get("port")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    target = fact.target ?: payload?.get("target")?.jsonPrimitive?.contentOrNull,
                    recordedAt = fact.createdAt,
                    selfCreated = payload?.get("self_created")?.jsonPrimitive?.contentOrNull == "1",
                    systemKey = payload?.get("system_key")?.jsonPrimitive?.contentOrNull,
                    ipVersion = payload?.get("ip_version")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                    dataScale = payload?.get("data_scale")?.jsonPrimitive?.contentOrNull,
                    multiplier = RedTeamScoring
                        .scoreMultiplierOf(
                            payload?.get("ip_version")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                            payload?.get("data_scale")?.jsonPrimitive?.contentOrNull,
                            fact.target,
                        ).multiplier,
                )
            }

    /** 事实 id 是字符串；评分引擎按下标比较先后，这里映射成稳定且单调的数值键。 */
    private fun stableId(id: String): Long =
        id.toLongOrNull() ?: (id.hashCode().toLong() and 0x7fffffffL)

    private suspend fun singleFact(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("id is required")
        val fact = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { it.id == id }
            ?: error("fact not found: $id")
        return buildString {
            appendLine("id=${fact.id}")
            appendLine("kind=${fact.kind}")
            appendLine("title=${fact.title}")
            appendLine("target=${fact.target.orEmpty()}")
            appendLine("severity=${fact.severity.orEmpty()}")
            appendLine("status=${fact.status}")
            appendLine("updatedAt=${java.time.Instant.ofEpochMilli(fact.updatedAt)}")
            append("payload=${fact.payload}")
        }
    }

    /** 标记 PoC 被使用过一次：使用计数写回 payload，供后续排序与复盘。 */
    private suspend fun usePoc(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("id is required")
        val existing = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { it.id == id }
            ?: error("poc not found: $id")
        val current = Regex("\"useCount\"\\s*:\\s*(\\d+)").find(existing.payload)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val now = System.currentTimeMillis()
        val updated = existing.copy(
            payload = mergeJsonField(existing.payload, "useCount", (current + 1).toString()),
            status = "used",
            updatedAt = now,
        )
        facts.upsert(updated)
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, now, updated.id, updated.kind, updated.status))
        return "ok=true\nid=${updated.id}\nuseCount=${current + 1}"
    }

    /** 角色提示词：显式写过就用会话内的覆盖值，否则回落到内置职责描述。 */
    private suspend fun rolePrompt(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val roleId = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: error("role is required")
        val role = RedTeamRole.entries.firstOrNull { it.id == roleId }
            ?: error("unknown role: $roleId (${RedTeamRole.entries.joinToString("/") { it.id }})")
        val override = rolePrompts[sessionId]?.get(role.id)
        return buildString {
            appendLine("role=${role.id}｜${role.displayName}")
            appendLine("source=${if (override != null) "session-override" else "builtin"}")
            append(override ?: ROLE_DUTIES.getValue(role))
        }
    }

    private suspend fun rolePromptReset(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val roleId = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val bucket = rolePrompts[sessionId] ?: return "ok=true\nreset=none"
        val removed = if (roleId.isNullOrBlank()) bucket.keys.toList().also { bucket.clear() } else listOfNotNull(roleId.takeIf { bucket.remove(it) != null })
        return "ok=true\nreset=${removed.joinToString(",").ifBlank { "none" }}"
    }

    private suspend fun sessionCheck(sessionId: String): String {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        val bound = session.redTeamMode == "red_team" && !session.redTeamTarget.isNullOrBlank()
        return buildString {
            appendLine("ok=$bound")
            appendLine("session=${session.id}")
            appendLine("mode=${session.redTeamMode}")
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("scope=${session.redTeamScope}")
            append("phase=${session.redTeamPhase}")
        }
    }

    private suspend fun requireBoundRedTeam(sessionId: String): top.tianyan.app.core.database.HarnessSessionEntity {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        require(!session.redTeamTarget.isNullOrBlank()) { "bind a target and scope before querying the engagement" }
        return session
    }

    /**
     * 归段：委托 [RedTeamIpUtils.cidrOf]，IPv4 走 /24、IPv6 走 /64。
     * 之前这里手写死 `split('.')` 拼 `.0/24`，IPv6 资产会得到 `2001:db8::1.0/24` 这种脏 CIDR，
     * 面板左侧会多出假网段、资产归属也错。认不出来（非 IP）返回 null，不编造网段。
     */
    private fun segmentOf(ip: String?): String? {
        val raw = ip?.trim()?.substringBefore('/')?.takeIf { it.isNotEmpty() } ?: return null
        val cidr = RedTeamIpUtils.cidrOf(raw)
        return cidr.takeIf { it != raw }
    }

    private fun isWebAsset(fact: RedTeamFactEntity): Boolean {
        val blob = "${fact.payload} ${fact.title}".lowercase()
        return blob.contains("http") || blob.contains("port") || blob.contains("443") || blob.contains("80") ||
            Regex("\"port\"\\s*:\\s*\"?(80|443|8080|8443|8000|8888)").containsMatchIn(blob)
    }

    private fun edgeEndpoints(fact: RedTeamFactEntity): Pair<String, String>? {
        val src = Regex("\"src_id\"\\s*:\\s*\"([^\"]+)\"").find(fact.payload)?.groupValues?.get(1) ?: return null
        val dst = Regex("\"dst_id\"\\s*:\\s*\"([^\"]+)\"").find(fact.payload)?.groupValues?.get(1) ?: return null
        return src to dst
    }

    private fun mergeJsonField(payload: String, key: String, value: String): String {
        val obj = runCatching { Json.parseToJsonElement(payload).let { it as? JsonObject } }.getOrNull()
        val merged = (obj ?: JsonObject(emptyMap())) + (key to kotlinx.serialization.json.JsonPrimitive(value))
        return merged.toString()
    }

    private suspend fun requireBoundSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId is required" }
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        require(!session.redTeamTarget.isNullOrBlank()) { "bind a target and scope before recording facts" }
    }

    private companion object {
        const val MAX_CONCURRENT_AGENTS = 3

        /** 图谱/统计类查询一次扫描的事实上限，避免超大资产库把单次工具调用拖死。 */
        const val MAX_FACTS_SCANNED = 2000
        const val DEFAULT_MAX_NODES = 300
        const val HARD_MAX_NODES = 1000

        /** Control-plane keys; everything else is preserved as fact detail. */
        val CONTROL_KEYS = setOf("action", "sub_action", "title", "target", "severity", "status", "id", "key", "label")
        val ROLE_DUTIES = mapOf(
            RedTeamRole.PLANNER to "主会话负责统筹：读资产图谱与评分，按性价比排序后把活派给五个执行角色，自己不做具体探测。",
            RedTeamRole.RECON to "被动测绘优先：域名、证书、备案、公开暴露面，结果写 asset_add 并标注 provenance=passive。",
            RedTeamRole.ASSESS to "把资产、端口、服务、指纹、归属关系整理成可复用的资产库，区分 live/dead，并给出易打性评估 priority/potential/reason。",
            RedTeamRole.VULN_SCAN to "对已知资产做授权范围内的主动验证，产出漏洞记录与 HTTP 证据。",
            RedTeamRole.EXPLOIT to "在确认的漏洞上验证影响，记录凭据、访问会话、WebShell 等利用结果。",
            RedTeamRole.INTERNAL to "从已获得的立足点向内网延伸，维护内网资产与攻击链。",
        )

        /** 资产测试状态，与上游 asset_test 的 status 枚举一致。 */
        val ASSET_TEST_STATUSES = listOf("untested", "testing", "tested", "blocked", "abandoned", "no_surface")

        /** 易打性优先级。 */
        val ASSESS_PRIORITIES = listOf("high", "medium", "low")
    }
}
