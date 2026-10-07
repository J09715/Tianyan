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
import top.tianyan.app.core.model.RedTeamRole
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
                // 存量评估/并发验证：只登记结论，真正的主动探测仍需走已审批的 base/process。
                "asset_assess", "asset_test",
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
                        "asset_assess" -> "asset"
                        "asset_test" -> "event"
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
                "fact_query", "asset_query", "vuln_query", "credential_list", "access_list", "webshell_list", "tunnel_list", "chain", "attack_chain", "attack_file_list", "score_list", "score_report", "poc_search", "poc_list", "report_targets", "report" -> {
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
                    if (action in setOf("report", "score_report")) {
                        buildReport(session.redTeamTarget.orEmpty(), items)
                    } else {
                        items.joinToString("\n") { "${it.kind} | ${it.title} | ${it.severity ?: "-"} | ${it.status} | ${it.target.orEmpty()}" }
                            .ifBlank { "当前会话暂无事实记录" }
                    }
                }
                "asset_graph" -> assetGraph(sessionId, args)
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

    private fun buildReport(target: String, items: List<RedTeamFactEntity>): String = buildString {
        appendLine("# Red-Team Engagement Report")
        appendLine("Target: $target")
        appendLine("Generated: ${java.time.Instant.now()}")
        appendLine("Facts: ${items.size}")
        items.groupBy { it.kind }.toSortedMap().forEach { (kind, facts) ->
            appendLine()
            appendLine("## $kind (${facts.size})")
            facts.forEach { appendLine("- [${it.severity ?: it.status}] ${it.title}${it.target?.let { value -> " · $value" } ?: ""}") }
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

    /** /24 归段：上游以 C 段为图谱顶层节点。 */
    private fun segmentOf(ip: String?): String? {
        val raw = ip?.trim()?.substringBefore('/') ?: return null
        val parts = raw.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return null
        return "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
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
            RedTeamRole.RECON to "被动测绘优先：域名、证书、备案、公开暴露面，结果写 asset_add 并标注 provenance=passive。",
            RedTeamRole.ASSET to "把资产、端口、服务、指纹、归属关系整理成可复用的资产库，区分 live/dead。",
            RedTeamRole.VULN_SCAN to "对已知资产做授权范围内的主动验证，产出漏洞记录与 HTTP 证据。",
            RedTeamRole.EXPLOIT to "在确认的漏洞上验证影响，记录凭据、访问会话、WebShell 等利用结果。",
            RedTeamRole.INTERNAL to "从已获得的立足点向内网延伸，维护内网资产与攻击链。",
        )
    }
}
