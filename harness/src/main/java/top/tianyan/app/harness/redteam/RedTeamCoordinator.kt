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
                "fact_add", "asset_add", "vuln_add", "credential_add", "access_add", "webshell_add", "tunnel_add", "chain_add", "attack_file_add", "score_hit", "poc_add", "http_evidence_add", "knowledge_add", "skill_add" -> {
                    val actionKind = when (action) {
                        "asset_add" -> "asset"
                        "vuln_add" -> "vulnerability"
                        "credential_add" -> "credential"
                        "access_add" -> "access_session"
                        "webshell_add" -> "webshell"
                        "tunnel_add" -> "tunnel"
                        "chain_add" -> "attack_step"
                        "attack_file_add" -> "attack_file"
                        "score_hit" -> "score_hit"
                        "poc_add" -> "knowledge"
                        "http_evidence_add" -> "event"
                        "knowledge_add" -> "knowledge"
                        "skill_add" -> "skill"
                        else -> args["kind"]?.jsonPrimitive?.contentOrNull
                    }
                    val kind = RedTeamFactKind.entries.firstOrNull { it.id == actionKind }
                        ?: error("unsupported fact kind")
                    val fact = record(
                        sessionId = sessionId,
                        kind = kind,
                        title = args["title"]?.jsonPrimitive?.contentOrNull.orEmpty().also { require(it.isNotBlank()) { "title is required" } },
                        target = args["target"]?.jsonPrimitive?.contentOrNull,
                        severity = args["severity"]?.jsonPrimitive?.contentOrNull,
                        status = args["status"]?.jsonPrimitive?.contentOrNull ?: "observed",
                        payload = args["payload"]?.jsonPrimitive?.contentOrNull ?: "{}",
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

    private suspend fun requireBoundSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId is required" }
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        require(!session.redTeamTarget.isNullOrBlank()) { "bind a target and scope before recording facts" }
    }

    private companion object {
        const val MAX_CONCURRENT_AGENTS = 3
        val ROLE_DUTIES = mapOf(
            RedTeamRole.RECON to "被动测绘优先：域名、证书、备案、公开暴露面，结果写 asset_add 并标注 provenance=passive。",
            RedTeamRole.ASSET to "把资产、端口、服务、指纹、归属关系整理成可复用的资产库，区分 live/dead。",
            RedTeamRole.VULN_SCAN to "对已知资产做授权范围内的主动验证，产出漏洞记录与 HTTP 证据。",
            RedTeamRole.EXPLOIT to "在确认的漏洞上验证影响，记录凭据、访问会话、WebShell 等利用结果。",
            RedTeamRole.INTERNAL to "从已获得的立足点向内网延伸，维护内网资产与攻击链。",
        )
    }
}
