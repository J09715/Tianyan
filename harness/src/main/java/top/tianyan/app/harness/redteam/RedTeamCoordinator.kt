package top.tianyan.app.harness.redteam

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
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
import top.tianyan.app.core.model.RedTeamPreflightReport
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.core.model.RedTeamMode
import top.tianyan.app.core.model.RedTeamRole
import top.tianyan.app.core.model.RedTeamSettings
import top.tianyan.app.core.model.RedTeamStage
import top.tianyan.app.core.model.RedTeamValidate
import top.tianyan.app.core.model.RedTeamSkillHealth
import top.tianyan.app.core.model.RedTeamSkillAvailability
import top.tianyan.app.core.model.RedTeamScoreCatalog
import top.tianyan.app.core.model.RedTeamScorePointEdit
import top.tianyan.app.core.model.RedTeamScoring
import top.tianyan.app.core.model.ScorePoint
import top.tianyan.app.core.model.ScoredHit
import top.tianyan.app.core.model.ScoreHit
import top.tianyan.app.core.model.RedTeamNucleiIndex
import top.tianyan.app.harness.events.HarnessEvent
import top.tianyan.app.harness.events.HarnessEventBus

@Singleton
class RedTeamCoordinator @Inject constructor(
    private val facts: RedTeamFactRepository,
    private val sessions: HarnessSessionRepository,
    private val events: HarnessEventBus,
    /** 红队技能来源；缺省为空，单测无需构造完整技能库。 */
    private val skills: RedTeamSkillSource = RedTeamSkillSource.Empty,
    /** 技能库读写接缝（控制台「技能库」页签）；缺省不可用，单测无需构造 Room。 */
    private val skillStore: RedTeamSkillStore = RedTeamSkillStore.Unsupported,
) {
    /** 落盘根目录的就地覆盖（测试用，避免写到真实 home）。 */
    @Volatile
    var filesRootOverride: String? = null

    /** 模板库读盘接缝（测试用）；为空时走真实文件系统。 */
    @Volatile
    var templateFsOverride: RedTeamNucleiIndex.TemplateFs? = null

    /** 连通性实测接缝（测试用）；为空时真发网络请求。 */
    @Volatile
    var sessionProbeOverride: RedTeamSessionProbe? = null

    /** 模板索引缓存：目录 + 构建时间 / 索引本体。13k 个 yaml 不能每次调用重建。 */
    private val templateCache = java.util.concurrent.atomic.AtomicReference<Pair<String, Long>?>(null)
    private val templateIndexCache = java.util.concurrent.atomic.AtomicReference<RedTeamNucleiIndex.Index?>(null)

    /** 设置文件位置；与事实库同根，随库一起备份迁移。 */
    private val settingsRoot: String
        get() = filesRootOverride ?: defaultSettingsRoot

    private val defaultSettingsRoot: String =
        System.getenv("DSH_HOME")?.takeIf { it.isNotBlank() }?.let { "$it/redteam" }
            ?: System.getProperty("user.home").orEmpty() + "/.dsh/redteam"

    /** Per-session agent slots. The registry lives in memory; the fact store survives restarts. */
    private val slotLock = Mutex()
    private val reservations = java.util.concurrent.ConcurrentHashMap<String, MutableList<SlotReservation>>()

    private data class SlotReservation(val key: String, val label: String, val at: Long)

    /** 会话级角色提示词覆盖；未设置时回落到内置职责描述，进程重启后回到内置值。 */
    /**
     * 角色提示词覆盖层的读缓存。
     *
     * 真值在 `prompt` 事实里（`id = prompt:<role>`，正文放 payload.content）；这里只是
     * 避免每次派活/查提示词都扫一遍事实库。缓存随保存写穿，重启后从事实库重建。
     */
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

    /**
     * 开工前体检：技能能列出来 ≠ 能跑。缺 `FOFA_KEY`、工具没落到 toolkit、VPS 还是占位符，
     * 都要等真正动手才发现，那时候人已经在靶场里了。所以缺什么在这里直接要，并给出怎么修。
     */
    /**
     * 结构化体检结论，供界面标记「能不能跑」。
     * 与文字版共用同一份判定，避免界面与智能体看到两种结论。
     */
    suspend fun skillHealth(sessionId: String): RedTeamPreflightReport {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }

        val fs = RedTeamSkillAvailability.fileSystemFs()
        val home = System.getProperty("user.home").orEmpty()
        val dshHome = System.getenv("DSH_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.dsh"
        val env = System.getenv()

        val verdicts = skills.redTeamSkills().map { source ->
            RedTeamSkillAvailability.checkSkill(
                skill = RedTeamSkillAvailability.Skill(
                    name = source.name,
                    content = source.content,
                    path = source.path,
                ),
                env = env,
                fs = fs,
                dshHome = dshHome,
                homeDir = home,
            )
        }
        val summary = RedTeamSkillAvailability.summarizeSkills(verdicts)
        return RedTeamPreflightReport(
            ready = verdicts.all { it.status == "available" },
            target = session.redTeamTarget.orEmpty(),
            scope = session.redTeamScope,
            total = summary["total"] ?: 0,
            available = summary["available"] ?: 0,
            broken = summary["broken"] ?: 0,
            unknown = summary["unknown"] ?: 0,
            needsUser = verdicts.flatMap { it.needsUser }.distinct(),
            skills = verdicts.map { verdict ->
                RedTeamSkillHealth(
                    name = verdict.name,
                    status = verdict.status,
                    problems = verdict.problems,
                    fix = verdict.issues.firstOrNull { it.fix.isNotEmpty() }?.fix.orEmpty(),
                )
            },
        )
    }

    /**
     * 开工前体检：技能能列出来 ≠ 能跑。缺 `FOFA_KEY`、工具没落到 toolkit、VPS 还是占位符，
     * 都要等真正动手才发现，那时候人已经在靶场里了。所以缺什么在这里直接要，并给出怎么修。
     */
    private suspend fun preflightReport(sessionId: String): String {
        val report = skillHealth(sessionId)
        val toolProbe = preflight(
            requiredEnvironment = emptyMap(),
            availableTools = setOf("base", "process", "invoke_subagent", "mcp"),
        )
        val blocked = report.skills.filterNot { it.usable }

        return buildString {
            appendLine("ready=${report.ready}")
            appendLine("target=${report.target}")
            appendLine("scope=${report.scope}")
            appendLine("tools=${toolProbe.available.joinToString(",")}")
            appendLine("skills=${report.total} available=${report.available} broken=${report.broken} unknown=${report.unknown}")
            if (report.needsUser.isNotEmpty()) {
                appendLine()
                appendLine("## 需要你提供")
                report.needsUser.forEach { appendLine("- $it") }
            }
            if (blocked.isNotEmpty()) {
                appendLine()
                appendLine("## 不可用技能与修法")
                blocked.forEach { skill ->
                    skill.problems.forEach { appendLine("- [${skill.name}] $it") }
                    if (skill.fix.isNotEmpty()) appendLine("  修法：${skill.fix}")
                }
            }
        }
    }


    suspend fun execute(args: JsonObject, sessionId: String): Pair<Boolean, String> {
        return runCatching {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
            when (action) {
                "session_info" -> sessionInfo(sessionId)
                "agent_slot" -> agentSlot(sessionId, args)
                "roles" -> roleBrief(sessionId)
                "preflight" -> preflightReport(sessionId)
                "fact_add", "asset_add", "vuln_add", "credential_add", "access_add", "webshell_add", "tunnel_add", "chain_add", "attack_file_add", "score_hit", "poc_add", "http_evidence_add", "knowledge_add", "skill_add",
                "vuln_update", "tunnel_update", "webshell_update", "poc_update", "asset_update", "credential_update", "access_update",
                "asset_link",
                -> {
                    val actionKind = when (action) {
                        "asset_add", "asset_update" -> "asset"
                        "vuln_add", "vuln_update" -> "vulnerability"
                        "credential_add", "credential_update" -> "credential"
                        "access_add", "access_update" -> "access_session"
                        "webshell_add", "webshell_update" -> {
                            // 类型/状态必须归一化：存进拼错的值不报错，只会让面板的
                            // 「用户连不上」红标与会话页的口令复制两处判定静默失效。
                            args["shell_type"]?.jsonPrimitive?.contentOrNull?.let {
                                RedTeamValidate.normalizeShellType(it)
                            }
                            args["status"]?.jsonPrimitive?.contentOrNull?.let {
                                RedTeamValidate.normalizeShellStatus(it)
                            }
                            "webshell"
                        }
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
                    var normalizedStageCode: String? = null
                    require(!isUpdate || explicitId != null) { "$action requires id" }
                    // 结构化字段原样落库，保留上游 schema（ip/port/service/fingerprint/provenance/relation…）。
                    // 攻击文件要真落盘：只记元数据事实的话，交付时手上没有文件，
                    // 读回也无从读起（路径防护也就没有守卫对象）。
                    val attackFilePath = if (kind == RedTeamFactKind.ATTACK_FILE) {
                        writeAttackFile(sessionId, args).first
                    } else {
                        null
                    }
                    // 攻击链步骤的阶段：非法 code 退回老 stage 兜底并告警，而不是静默丢桶。
                    val stageWarning = if (kind == RedTeamFactKind.ATTACK_STEP) {
                        val resolved = RedTeamStage.resolveChainStage(
                            stageCode = args["stage_code"]?.jsonPrimitive?.contentOrNull,
                            legacyStage = args["stage"]?.jsonPrimitive?.contentOrNull,
                        )
                        normalizedStageCode = resolved.code
                        resolved.warning
                    } else {
                        null
                    }
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
                        // 归一后的阶段码要落库：报告侧按它分桶，塞原始非法值会让这一步落在任何阶段之外。
                        payload = JsonObject(
                            args.filterKeys { it !in CONTROL_KEYS }.toMutableMap().apply {
                                if (normalizedStageCode != null) put("stage_code", JsonPrimitive(normalizedStageCode))
                                // 落盘路径以服务端结果为准，不采信调用方自报的 path。
                                if (attackFilePath != null) put("path", JsonPrimitive(attackFilePath))
                            },
                        ).toString(),
                        id = explicitId ?: UUID.randomUUID().toString(),
                    )
                    buildString {
                        append("已保存 ${fact.kind} 事实 ${fact.id}")
                        normalizedStageCode?.takeIf { kind == RedTeamFactKind.ATTACK_STEP }
                            ?.let { append(" · 阶段 $it") }
                        attackFilePath?.let { append(" · 已落盘 $it") }
                        stageWarning?.let { appendLine().append("warning=").append(it) }
                    }
                }
                "read_attack_file" -> readAttackFile(sessionId, args)
                "poc_delete" -> {
                    val session = requireBoundRedTeam(sessionId)
                    val key = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: args["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                        ?: error("poc_delete 需要 id 或 code")
                    val target = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { fact ->
                        fact.kind == "knowledge" && (fact.id == key || payloadOf(fact)["code"]?.jsonPrimitive?.contentOrNull == key)
                    } ?: error("未找到 PoC：$key")
                    facts.deleteById(sessionId, target.id)
                    "ok=true\ndeleted=${target.id}"
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
                        val listed = items.joinToString("\n") {
                            "${it.kind} | ${it.title} | ${it.severity ?: "-"} | ${it.status} | ${it.target.orEmpty()}"
                        }.ifBlank { "当前会话暂无事实记录" }
                        // 上游 pocSearch 一次把「本机沉淀的 POC」与「本机 nuclei 模板」都返回，省一轮往返。
                        // 只列 POC 会让人以为本机没有现成模板，白白多跑一次互联网检索。
                        if (action == "poc_search") "$listed\n${templateHits(args)}" else listed
                    }
                }
                "asset_graph" -> assetGraph(sessionId, args)
                "score_report" -> scoreReport(sessionId)
                "score_points" -> scorePointList(sessionId)
                "score_point_save" -> saveScorePoint(sessionId, args)
                "stages" -> stageList(sessionId)
                "save_stage" -> saveStage(sessionId, args)
                "delete_score_point" -> deleteScorePoint(sessionId, args)
                "role_dispatch" -> roleDispatch(sessionId, args)
                // 存量评估/并发验证：只登记结论，真正的主动探测仍需走已审批的 base/process。
                "asset_assess" -> assessAsset(sessionId, args)
                "asset_test" -> testAsset(sessionId, args)
                "active_tests" -> activeTests(sessionId, args)
                "test_stats" -> testStats(sessionId)
                "console_digest" -> consoleDigest(sessionId)
                "import_bundle" -> importBundle(sessionId, args)
                "template_search" -> templateSearch(sessionId, args)
                "template_stats" -> templateStats(sessionId)
                "probe_sessions" -> probeSessions(sessionId, args)
                "snapshot" -> snapshot(sessionId)
                "bootstrap" -> bootstrap(sessionId)
                "group_slot" -> groupSlot(sessionId)
                "domain_index" -> domainIndex(sessionId)
                "asset_stats" -> assetStats(sessionId)
                "asset_timeline" -> assetTimeline(sessionId, args)
                "web_list" -> webList(sessionId)
                "attack_path" -> attackPath(sessionId, args)
                "asset_get", "poc_get", "vuln_get" -> singleFact(sessionId, args)
                "poc_use" -> usePoc(sessionId, args)
                "vuln_stats" -> vulnStats(sessionId)
                "http_evidence_list" -> httpEvidenceList(sessionId, args)
                "score_chain" -> scoreChain(sessionId, args)
                "poc_stats" -> pocStats(sessionId)
                "role_prompt" -> rolePrompt(sessionId, args)
                "role_prompt_save" -> saveRolePrompt(sessionId, args)
                "role_prompt_reset" -> rolePromptReset(sessionId, args)
                "prompts" -> prompts(sessionId)
                "skill_list" -> skillList()
                "skill_get" -> skillGet(args)
                "skill_save" -> skillSave(args)
                "skill_delete" -> skillDelete(args)
                "sessions" -> "当前会话 ${sessionId}（红队模式的目标与事实均为会话级，不跨会话共享）"
                "session_check", "engagement_open", "session_bind" -> sessionCheck(sessionId)
                else -> error("unsupported redteam action: $action")
            }
        }.fold(onSuccess = { true to it }, onFailure = { false to (it.message ?: "redteam tool failed") })
    }

    private suspend fun agentSlot(sessionId: String, args: JsonObject): String = slotLock.withLock {
        val max = maxConcurrentAgents
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

    // ── 控制台投影 API ────────────────────────────────────────────────────────
    // 面板不解析工具输出：那是写给模型看的散文。让面板去正则抠 `assets=12`，
    // 工具输出改一个字面板就瞎了，而且没有任何编译期保护。这里直接给结构化行。

    /**
     * 面板一次要读的事实类型。
     *
     * 端口/指纹/域名不是独立事实，它们挂在资产 payload 的数组里，所以只取三类；
     * 多取类型只会让面板在无关行上白跑投影。
     */
    private val consoleKinds = listOf(
        RedTeamFactKind.ASSET.id,
        RedTeamFactKind.EDGE.id,
        RedTeamFactKind.EVENT.id,
        RedTeamFactKind.SEGMENT.id,
    )

    /** 面板单次读事实的上限：上游 `listAssets` 硬上限 2000，这里留出端口/漏洞的余量。 */
    private val consoleFactLimit = 8000

    private fun RedTeamFactEntity.toConsoleFact() = RedTeamConsoleModel.Fact(
        id = id,
        kind = kind,
        title = title,
        target = target,
        status = status,
        payload = payload,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    suspend fun consoleFacts(sessionId: String): List<RedTeamConsoleModel.Fact> = withContext(Dispatchers.IO) {
        facts.byKinds(sessionId, consoleKinds, consoleFactLimit).map { it.toConsoleFact() }
    }

    /**
     * 面板首屏：靶标清单 + 当前靶标 + 资产 + 网段 + 底栏统计。
     *
     * 一次取全，避免「切页签时统计已经变了、列表还是旧的」——
     * 底栏数字和列表对不上，比慢一点更让人不信任。
     */
    suspend fun consoleSnapshot(sessionId: String): RedTeamConsoleModel.Snapshot = withContext(Dispatchers.IO) {
        val rows = consoleFacts(sessionId)
        val assets = RedTeamConsoleModel.assets(rows)
        val engagements = sessions.listAll()
            .filter { it.redTeamMode == RedTeamMode.RED_TEAM.id }
            .sortedByDescending { it.updatedAt }
            .map {
                RedTeamConsoleModel.Engagement(
                    id = it.id,
                    name = it.title.ifBlank { it.redTeamTarget ?: it.id },
                    target = it.redTeamTarget,
                    scope = it.redTeamScope,
                )
            }
        RedTeamConsoleModel.Snapshot(
            engagements = engagements,
            current = sessionId,
            assets = assets,
            segments = RedTeamConsoleModel.segments(rows, assets),
            stats = RedTeamConsoleModel.stats(rows, assets),
        )
    }

    /** 资产列表：返回（过滤后总数，当前页）。 */
    suspend fun consoleAssets(
        sessionId: String,
        filter: RedTeamConsoleModel.Filter = RedTeamConsoleModel.Filter(),
    ): Pair<Int, List<RedTeamConsoleModel.Asset>> = withContext(Dispatchers.IO) {
        RedTeamConsoleModel.query(consoleFacts(sessionId), filter)
    }

    /** 单台资产详情（含采集溯源）；找不到返回 null，由面板显示空态而不是编一行假数据。 */
    suspend fun consoleAssetDetail(sessionId: String, assetId: String): RedTeamConsoleModel.Detail? =
        withContext(Dispatchers.IO) {
            val rows = consoleFacts(sessionId)
            val asset = RedTeamConsoleModel.assets(rows).firstOrNull { it.id == assetId || it.ip == assetId }
                ?: return@withContext null
            RedTeamConsoleModel.Detail(asset, RedTeamConsoleModel.observationsOf(rows, asset))
        }

    suspend fun consoleGraph(
        sessionId: String,
        cidr: String? = null,
        maxNodes: Int = 600,
    ): RedTeamConsoleModel.Graph = withContext(Dispatchers.IO) {
        RedTeamConsoleModel.graph(consoleFacts(sessionId), cidr, maxNodes.coerceIn(10, 2000))
    }

    /** 角色提示词清单（面板「智能体提示词」页签）。 */
    suspend fun consoleRoles(sessionId: String): List<RedTeamConsoleModel.Role> = withContext(Dispatchers.IO) {
        val overrides = facts.byKinds(sessionId, listOf(RedTeamFactKind.PROMPT.id), consoleFactLimit)
            .associate { fact ->
                val role = payloadOf(fact)["role"]?.jsonPrimitive?.contentOrNull ?: fact.id.removePrefix("prompt:")
                role to (payloadOf(fact)["content"]?.jsonPrimitive?.contentOrNull.orEmpty() to fact.updatedAt)
            }
        RedTeamRole.entries.map { role ->
            val hit = overrides[role.id]
            RedTeamConsoleModel.Role(
                id = role.id,
                title = role.displayName,
                source = if (hit != null && hit.first.isNotBlank()) {
                    RedTeamConsoleModel.Role.SOURCE_OVERRIDE
                } else {
                    RedTeamConsoleModel.Role.SOURCE_BUILTIN
                },
                content = hit?.first?.takeIf { it.isNotBlank() } ?: ROLE_DUTIES.getValue(role),
                updatedAt = hit?.second,
            )
        }
    }

    /** 面板保存角色提示词；空正文恢复内置。返回是否落成覆盖。 */
    suspend fun consoleSaveRolePrompt(sessionId: String, roleId: String, content: String): Boolean {
        val args = buildJsonObject {
            put("role", JsonPrimitive(roleId))
            put("content", JsonPrimitive(content))
        }
        return saveRolePrompt(sessionId, args).contains("source=session-override")
    }

    /**
     * 智能体并发上限的当前值与来源（上游 `agentsStatus` 的 max/source/limit 部分）。
     *
     * 面板要如实说明「这个数是哪来的」：设置项 / 环境变量 / 默认值。
     * 不标来源的话，用户改了设置却没生效（被环境变量压住）时会以为界面坏了。
     */
    data class AgentsStatus(
        val max: Int,
        val source: String,
        val limit: Int,
        val defaultMax: Int,
        val used: Int,
        val free: Int,
        val running: List<String>,
    )

    suspend fun consoleAgentsStatus(sessionId: String): AgentsStatus {
        val effective = RedTeamSettings.maxAgentsOf(
            settingsValue = maxAgentsOverride ?: readSettingsMaxAgents(),
            envValue = System.getenv(RedTeamSettings.ENV_MAX_AGENTS),
        )
        val held = slotLock.withLock { reservations[sessionId]?.toList().orEmpty() }
        return AgentsStatus(
            max = effective.value,
            source = effective.source.id,
            limit = RedTeamSettings.MAX_AGENTS_LIMIT,
            defaultMax = RedTeamSettings.DEFAULT_MAX_AGENTS,
            used = held.size,
            free = (effective.value - held.size).coerceAtLeast(0),
            running = held.map { it.label },
        )
    }

    /**
     * 改并发上限（上游 `setAgentsMax`）：落盘 settings.json，立即生效。
     *
     * 「立即生效」是真的：派活时每次现读 [maxConcurrentAgents]，不需要重启。
     * 这一点要写进面板提示——用户最怕的就是「改完要重启才生效」。
     */
    suspend fun consoleSetAgentsMax(value: Any?): Int = withContext(Dispatchers.IO) { saveMaxAgents(value) }

    /** 面板技能库读写：直接转发到技能库接缝，未装配时由接缝给出明确错误。 */
    suspend fun consoleSkills(): List<RedTeamSkillStore.Skill> = withContext(Dispatchers.IO) { skillStore.list() }

    suspend fun consoleSaveSkill(skill: RedTeamSkillStore.Skill): RedTeamSkillStore.Skill =
        withContext(Dispatchers.IO) { skillStore.save(skill) }

    suspend fun consoleDeleteSkill(id: String): Boolean = withContext(Dispatchers.IO) { skillStore.delete(id) }

    private suspend fun roleBrief(sessionId: String): String {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        return buildString {
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("scope=${session.redTeamScope}")
            RedTeamRole.entries.forEach { role ->
                appendLine("- ${role.id}｜${role.displayName}：${ROLE_DUTIES.getValue(role)}")
            }
            append("派活用 invoke_subagent；并发上限 $maxConcurrentAgents，派前先 agent_slot status。")
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
            if (held.size >= maxConcurrentAgents) {
                return@withLock null
            }
            val reservation = SlotReservation("${role.id}#${System.currentTimeMillis()}", role.id, System.currentTimeMillis())
            held += reservation
            reservation
        } ?: return "ok=false\nerror=并发已满（最多 $maxConcurrentAgents 个），先等当前在跑的角色回报或 release 掉已结束的。"

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
            appendLine("max=$maxConcurrentAgents")
            appendLine("used=${held.size}")
            appendLine("free=${(maxConcurrentAgents - held.size).coerceAtLeast(0)}")
            appendLine("## 在跑的角色")
            held.forEach { appendLine("- ${it.label} | ${it.key}") }
            append("可派角色：${RedTeamRole.dispatchable.joinToString("/") { it.id }}")
        }
    }

    /** 派活前给子代理看的态势摘要：目标、C 段、资产/漏洞计数、当前得分。 */
    private suspend fun engagementBrief(sessionId: String): String {
        val facts = recent(sessionId, MAX_FACTS_SCANNED)
        val board = RedTeamScoring.applyScoreCaps(hitRows(sessionId), pointsByCode = scoringPoints(sessionId))
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
    /** 资产测试状态的合法取值；未记录时按 `untested` 计。 */
    private val assetTestStatuses = listOf("untested", "testing", "tested", "blocked", "abandoned", "no_surface")

    /** 一台资产的测试状态：没记过就是 `untested`（与上游 `COALESCE(test_status,'untested')` 一致）。 */
    private fun testStatusOf(fact: RedTeamFactEntity): String =
        payloadOf(fact)["test_status"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "untested"

    /**
     * 待测队列的排序：先按易打性分档，再按开放端口数从多到少，最后按 IP 升序。
     *
     * 分档用的是 `asset_assess` 写下的 `priority`；没评估过的排最后，
     * 否则队列会把「还没看过的机器」顶到前面，与「先打容易打的」这个意图相反。
     */
    private fun queueRank(fact: RedTeamFactEntity): Triple<Int, Int, Long> {
        val payload = payloadOf(fact)
        val tier = when (payload["priority"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "high" -> 0
            "medium" -> 1
            "low" -> 2
            else -> 3
        }
        val ports = payload["open_ports"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return Triple(tier, -ports, RedTeamIpUtils.ipToInt(fact.target ?: "") ?: Long.MAX_VALUE)
    }

    /**
     * 批量导入一次扫描的成果：分段、资产（含域名/端口/服务/指纹）、关系边。
     *
     * 身份键取 `id`/`asset_id`/`ip` 并落成 `asset:<键>`：上游按 (ip,…) 幂等 upsert，
     * 同一份扫描导两次不该变成两份资产。重复导入保留原 `createdAt`（等价于上游的 first_seen）。
     *
     * 上游还有 scan_run 表记录扫描批次；本移植没有这张表，改为把 `tool` 落到资产上，
     * 批次元信息（argv 等）不落库——这一条是明确的分歧，不是遗漏。
     *
     * 批量写入只发一条汇总事件：逐行 emit 会把事件总线冲爆，而面板只需要知道「有变化」。
     */
    private suspend fun importBundle(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val raw = args["bundle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: JsonObject(args.filterKeys { it !in CONTROL_KEYS }).toString()
        val bundle = runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { error("import_bundle 的 bundle 必须是 JSON 对象：${it.message}") }

        val existing = recent(sessionId, MAX_FACTS_SCANNED).associateBy { it.id }
        val now = System.currentTimeMillis()
        val scanTool = bundle["scan"]?.let { runCatching { it.jsonObject["tool"]?.jsonPrimitive?.contentOrNull }.getOrNull() }

        var segments = 0
        var assets = 0
        var ports = 0
        var services = 0
        var fingerprints = 0
        var names = 0
        var edges = 0

        // 段元数据必须落库，不能只计数。
        //
        // 上游 `#upsertSegment` 把 org/asn/country/city 写进独立的 segment 表，面板左侧栏的
        // 「归属组织」就是从这里读的。原来这里只 `segments++`，行被直接丢掉——
        // 导入的网段归属信息全部静默消失，而且因为计数是对的，看不出任何异常。
        bundle["segments"]?.let { runCatching { it.jsonArray }.getOrNull() }?.forEach { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
            val cidr = row["cidr"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@forEach
            segments++
            val priorSegment = existing["segment:$cidr"]
            val priorSegmentPayload = priorSegment?.let { payloadOf(it) } ?: JsonObject(emptyMap())
            // 合并而不是覆盖：上游是 COALESCE(excluded.org, segment.org)，
            // 后一次导入没带 org 时不能把先前的抹掉。
            fun keep(field: String) = row[field]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: priorSegmentPayload[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            record(
                sessionId = sessionId,
                kind = RedTeamFactKind.SEGMENT,
                title = cidr,
                target = cidr,
                payload = JsonObject(
                    buildMap {
                        listOf("org", "asn", "country", "city", "ip_start", "ip_end", "source").forEach { field ->
                            keep(field)?.let { put(field, JsonPrimitive(it)) }
                        }
                    },
                ).toString(),
                id = "segment:$cidr",
            )
        }

        val edgeRows = mutableListOf<RedTeamFactEntity>()

        for (element in bundle["assets"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()) {
            val a = runCatching { element.jsonObject }.getOrNull() ?: continue
            val key = listOf("id", "asset_id", "ip").firstNotNullOfOrNull { k ->
                a[k]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            } ?: error("import_bundle: 资产缺少 id/asset_id/ip")
            val factId = "asset:$key"
            val prior = existing[factId]
            val priorPayload = prior?.let { payloadOf(it) } ?: JsonObject(emptyMap())

            fun arr(name: String) = a[name]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()
            val nameRows = arr("names")
            val portRows = arr("ports")
            val serviceRows = arr("services")
            val fingerprintRows = arr("fingerprints")
            names += nameRows.size
            ports += portRows.size
            services += serviceRows.size
            fingerprints += fingerprintRows.size

            val merged = JsonObject(
                priorPayload.toMutableMap().apply {
                    put(
                        "provenance",
                        JsonPrimitive(
                            a["provenance"]?.jsonPrimitive?.contentOrNull
                                ?: priorPayload["provenance"]?.jsonPrimitive?.contentOrNull
                                ?: "active",
                        ),
                    )
                    (a["tool"]?.jsonPrimitive?.contentOrNull ?: scanTool)?.let { put("tool", JsonPrimitive(it)) }
                    a["primary_name"]?.let { put("primary_name", it) }
                    if (nameRows.isNotEmpty()) put("names", JsonArray(nameRows))
                    if (portRows.isNotEmpty()) put("ports", JsonArray(portRows))
                    if (serviceRows.isNotEmpty()) put("services", JsonArray(serviceRows))
                    if (fingerprintRows.isNotEmpty()) put("fingerprints", JsonArray(fingerprintRows))
                    // 待测队列按开放端口数排序，所以要把这个数固化下来，而不是每次遍历 payload。
                    if (portRows.isNotEmpty()) {
                        val open = portRows.count { row ->
                            val state = runCatching { row.jsonObject["state"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                            state == null || state == "open"
                        }
                        put("open_ports", JsonPrimitive(open.toString()))
                    }
                },
            )

            facts.upsert(
                RedTeamFactEntity(
                    sessionId = sessionId,
                    id = factId,
                    kind = "asset",
                    title = a["title"]?.jsonPrimitive?.contentOrNull ?: prior?.title ?: key,
                    target = a["ip"]?.jsonPrimitive?.contentOrNull ?: prior?.target,
                    severity = prior?.severity,
                    status = prior?.status ?: "observed",
                    payload = merged.toString(),
                    createdAt = prior?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            assets++

            // 域名 → 资产 的 resolves 边：上游对每个 name 都补这条，
            // 少了它 domain_index 与攻击路径就看不到「哪个域名指向哪台机器」。
            nameRows.forEach { row ->
                val name = runCatching { row.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: return@forEach
                edgeRows += edgeFact(sessionId, name, factId, "resolves", now)
            }
        }

        for (element in bundle["edges"]?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty()) {
            val e = runCatching { element.jsonObject }.getOrNull() ?: continue
            val src = e["src_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: e["src"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: continue
            val dst = e["dst_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: e["dst"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: continue
            edgeRows += edgeFact(sessionId, src, dst, e["relation"]?.jsonPrimitive?.contentOrNull ?: "related", now)
        }

        edgeRows.distinctBy { it.id }.forEach {
            facts.upsert(it)
            edges++
        }

        // 汇总一条事件即可：面板只需要知道「这个靶标有变化」，逐行 emit 会冲爆总线。
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, now, "import_bundle", "import", "ok"))
        return buildString {
            appendLine("ok=true")
            appendLine("segments=$segments")
            appendLine("assets=$assets")
            appendLine("ports=$ports")
            appendLine("services=$services")
            appendLine("fingerprints=$fingerprints")
            appendLine("names=$names")
            appendLine("edges=$edges")
        }
    }

    /**
     * 本机 nuclei 模板检索：知识库里没有、但本机其实已有现成 POC 时先查它，能省掉一轮互联网检索。
     *
     * 索引只抽 path/name/severity/tags 四个检索字段，并按「目录一致 + 7 天内」缓存：
     * 13k 个 yaml 全量重建每次要读几千次盘，直接卡死工具调用。
     * 目录按上游顺序找（靶标 toolkit → 用户目录 → 系统目录），一个都没有就明确说没装，不报错。
     */
    private fun nucleiIndex(fs: RedTeamNucleiIndex.TemplateFs): RedTeamNucleiIndex.Index {
        val home = System.getProperty("user.home").orEmpty()
        val dir = RedTeamNucleiIndex.pickDir(settingsRoot, home, fs)
            ?: return RedTeamNucleiIndex.Index(null, emptyList())
        val now = System.currentTimeMillis()
        val stamp = templateCache.get()
        val cached = templateIndexCache.get()
        if (cached != null && stamp != null && cached.dir == dir &&
            RedTeamNucleiIndex.cacheFresh(stamp.first, stamp.second, dir, now)
        ) {
            return cached
        }
        val built = RedTeamNucleiIndex.Index(dir, RedTeamNucleiIndex.buildIndex(dir, fs))
        templateIndexCache.set(built)
        templateCache.set(dir to now)
        return built
    }

    private suspend fun templateSearch(sessionId: String, args: JsonObject): String {
        requireRedTeamMode(sessionId)
        val index = nucleiIndex(templateFsOverride ?: RedTeamNucleiIndex.fileSystemFs())
        val limit = args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: RedTeamNucleiIndex.DEFAULT_LIMIT
        val result = RedTeamNucleiIndex.search(index, args["query"]?.jsonPrimitive?.contentOrNull, limit)
        return buildString {
            appendLine("ok=true")
            appendLine("dir=${result.dir ?: "-"}")
            appendLine("total=${result.total}")
            appendLine("returned=${result.items.size}")
            if (result.dir == null) {
                append("未找到本机 nuclei 模板库；把模板放到 <redteam>/toolkit/nuclei-templates 或 ~/nuclei-templates 后重试。")
            } else if (result.items.isEmpty()) {
                append("没有匹配的模板；换个 CVE 编号、组件名或标签再试。")
            } else {
                result.items.forEach {
                    appendLine("- ${it.path} | ${it.name.ifBlank { "-" }} | ${it.severity.ifBlank { "-" }} | ${it.tags.ifBlank { "-" }}")
                }
            }
        }
    }

    private suspend fun templateStats(sessionId: String): String {
        requireRedTeamMode(sessionId)
        val index = nucleiIndex(templateFsOverride ?: RedTeamNucleiIndex.fileSystemFs())
        val stats = RedTeamNucleiIndex.stats(index)
        return buildString {
            appendLine("ok=true")
            appendLine("dir=${stats.dir ?: "-"}")
            appendLine("total=${stats.total}")
            appendLine("cve=${stats.cve}")
            append(if (stats.dir == null) "本机未安装 nuclei 模板库，检索不可用。" else "索引已就绪。")
        }
    }

    /**
     * 会话连通性实测（上游 `probeSessions`）：对已登记的 WebShell 发 HTTP、对隧道做 TCP 连接，
     * 把结果写回同一条事实的 `status` / `last_check` / `latency_ms` / `check_note`。
     *
     * 为什么值得真连：面板上「用户连不上」和智能体「以为还有通道」是同一份数据的两面，
     * 不实测就只能靠猜。上游在 host 侧做，本移植同理。
     *
     * 时间戳用 epoch 毫秒（上游是 ISO 字符串）：本移植的事实表就是毫秒，
     * 与 `console_digest` 的处理保持一致——两边都是「越大越新」，比较语义不变。
     */
    private suspend fun probeSessions(sessionId: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        requireBoundRedTeam(sessionId)
        // 超时收敛到 1..20 秒：低于 1 秒在移动网络下几乎必然假离线，高于 20 秒会把工具调用拖死。
        val timeout = (args["timeout_ms"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_PROBE_TIMEOUT_MS)
            .coerceIn(MIN_PROBE_TIMEOUT_MS, MAX_PROBE_TIMEOUT_MS)
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: MAX_PROBE_ROWS)
            .coerceIn(1, MAX_PROBE_ROWS)
        val probe = sessionProbeOverride ?: RedTeamSessionProbe.Network
        val rows = recent(sessionId, MAX_FACTS_SCANNED)

        val shells = mutableListOf<Triple<RedTeamFactEntity, String, Int>>()
        rows.filter { it.kind == "webshell" }.take(limit).forEach { fact ->
            val payload = payloadOf(fact)
            val url = payload["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: fact.target?.takeIf { it.isNotBlank() }
            if (url == null) {
                shells += Triple(fact, "offline", 0)
                applyProbeResult(sessionId, fact, "offline", "未登记 url，无法探测", 0)
                return@forEach
            }
            val started = System.currentTimeMillis()
            val (status, note) = probe.http(url, timeout)
            val latency = (System.currentTimeMillis() - started).toInt()
            applyProbeResult(sessionId, fact, status, note, latency)
            shells += Triple(fact, status, latency)
        }

        val tunnels = mutableListOf<Triple<RedTeamFactEntity, String, Int>>()
        rows.filter { it.kind == "tunnel" }.take(limit).forEach { fact ->
            val listen = payloadOf(fact)["listen"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .removePrefix("socks5://").removePrefix("https://").removePrefix("http://")
                .trim()
            val started = System.currentTimeMillis()
            val parsed = parseListen(listen)
            val (status, note) = when {
                listen.isEmpty() -> "down" to "缺少 listen 地址，无法探测"
                parsed == null -> "down" to "无法解析端口：$listen"
                else -> probe.tcp(parsed.first, parsed.second, timeout)
            }
            val latency = (System.currentTimeMillis() - started).toInt()
            applyProbeResult(sessionId, fact, status, note, latency)
            tunnels += Triple(fact, status, latency)
        }

        buildString {
            appendLine("ok=true")
            appendLine("checked_at=${System.currentTimeMillis()}")
            appendLine("timeout_ms=$timeout")
            appendLine("webshells=${shells.size} online=${shells.count { it.second == "online" }}")
            shells.forEach { (fact, status, latency) ->
                appendLine("- ${fact.id} | ${fact.title} | $status | ${latency}ms")
            }
            appendLine("tunnels=${tunnels.size} active=${tunnels.count { it.second == "active" }}")
            tunnels.forEach { (fact, status, latency) ->
                appendLine("- ${fact.id} | ${fact.title} | $status | ${latency}ms")
            }
            if (shells.isEmpty() && tunnels.isEmpty()) {
                append("没有已登记的 WebShell 或隧道；先用 webshell_add / tunnel_add 登记再实测。")
            }
        }
    }

    /**
     * `listen` 解析：`host:port`；没有 host 时按上游回落到 127.0.0.1（本地转发最常见），
     * 只有裸端口也认。端口非法返回 null，由调用方给出明确原因而不是硬编一个默认端口。
     */
    private fun parseListen(listen: String): Pair<String, Int>? {
        val idx = listen.lastIndexOf(':')
        val host = if (idx > 0) listen.substring(0, idx).trim().trim('[', ']') else "127.0.0.1"
        val rawPort = if (idx > 0) listen.substring(idx + 1) else listen
        val port = rawPort.trim().toIntOrNull() ?: return null
        if (port <= 0 || port > 65535 || host.isEmpty()) return null
        return host to port
    }

    /** 把一次探测结果并回事实的 payload；状态机与备注要一起写，否则界面只看到一半。 */
    private suspend fun applyProbeResult(
        sessionId: String,
        fact: RedTeamFactEntity,
        status: String,
        note: String,
        latencyMs: Int,
    ) {
        val now = System.currentTimeMillis()
        val merged = payloadOf(fact) +
            mapOf(
                "status" to JsonPrimitive(status),
                "last_check" to JsonPrimitive(now.toString()),
                "latency_ms" to JsonPrimitive(latencyMs.toString()),
                "check_note" to JsonPrimitive(note),
            )
        facts.upsert(fact.copy(payload = JsonObject(merged).toString(), updatedAt = now))
        events.emit(HarnessEvent.RedTeamFactChanged(sessionId, now, fact.id, fact.kind, status))
    }

    /**
     * 上游 `pocSearch` 会把本机 nuclei 模板一并返回。
     * 查询词按 `q`/`query`/`cve`/`component` 顺序取第一个非空值——
     * 模型经常把 CVE 编号填在 `cve` 字段而不是 `q`，只认 `q` 会漏掉最常见的那种调用。
     */
    private suspend fun templateHits(args: JsonObject): String {
        val query = listOf("q", "query", "cve", "component").firstNotNullOfOrNull { key ->
            args[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }
        val index = nucleiIndex(templateFsOverride ?: RedTeamNucleiIndex.fileSystemFs())
        val result = RedTeamNucleiIndex.search(index, query, POC_TEMPLATE_HITS)
        return buildString {
            appendLine("## 本机 nuclei 模板（dir=${result.dir ?: "-"} total=${result.total} 命中=${result.items.size}）")
            when {
                result.dir == null -> append("本机未安装 nuclei 模板库。")
                query == null -> append("未给查询词；用 template_search 指定 CVE/组件/标签再查。")
                result.items.isEmpty() -> append("没有匹配的模板。")
                else -> result.items.forEach {
                    appendLine("- ${it.path} | ${it.name.ifBlank { "-" }} | ${it.severity.ifBlank { "-" }}")
                }
            }
        }
    }

    /**
     * 漏洞统计（上游 `vulnStats`）：按严重级/状态分桶，外加两个「结论行」用的计数。
     *
     * `targetGroups` 是上游漏洞页的默认分组数（按站点/服务归并）。
     * 不报它的话，界面上的分组数与工具返回的 total 对不上，看起来像丢了记录。
     */
    private suspend fun vulnStats(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val vulns = all.filter { it.kind == "vulnerability" }
        val evidenceVulnIds = all.filter { it.kind == "event" }
            .mapNotNull { payloadOf(it)["vuln_id"]?.jsonPrimitive?.contentOrNull?.takeIf { id -> id.isNotBlank() } }
            .toSet()
        val bySeverity = vulns.groupingBy { it.severity ?: "unknown" }.eachCount().toSortedMap()
        val byStatus = vulns.groupingBy { it.status }.eachCount().toSortedMap()
        val withGained = vulns.count { payloadOf(it)["gained"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true }
        val withEvidence = vulns.count { fact ->
            payloadOf(fact)["evidence"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true || fact.id in evidenceVulnIds
        }
        val targetGroups = vulns.map { RedTeamStage.targetKey(it.target, null) }.distinct().size
        return buildString {
            appendLine("ok=true")
            appendLine("total=${vulns.size}")
            appendLine("## 按严重级")
            bySeverity.forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## 按状态")
            byStatus.forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("withGained=$withGained")
            appendLine("withEvidence=$withEvidence")
            appendLine("targetGroups=$targetGroups")
        }
    }

    /**
     * HTTP 证据清单（上游 `listHttpEvidence`）：可按 `vuln_id` / `asset_id` 过滤。
     *
     * 默认只给摘要行（请求首行 + 响应状态），`full=true` 才附完整原始报文——
     * 原始报文动辄几十行，默认全给会把工具输出撑爆，而大多数时候只是想确认「有没有证据」。
     */
    private suspend fun httpEvidenceList(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val vulnId = args["vuln_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val assetId = args["asset_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_EVIDENCE_LIMIT)
            .coerceIn(1, HARD_MAX_EVIDENCE_LIMIT)
        val full = args["full"]?.jsonPrimitive?.contentOrNull == "true"

        val rows = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == "event" }
            .filter { fact ->
                val payload = payloadOf(fact)
                (vulnId == null || payload["vuln_id"]?.jsonPrimitive?.contentOrNull == vulnId) &&
                    (assetId == null || payload["asset_id"]?.jsonPrimitive?.contentOrNull == assetId)
            }
            .sortedByDescending { it.createdAt }
            .take(limit)

        return buildString {
            appendLine("ok=true")
            appendLine("count=${rows.size}")
            rows.forEach { fact ->
                val payload = payloadOf(fact)
                val request = payload["request"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val status = payload["status"]?.jsonPrimitive?.contentOrNull
                    ?: fact.status.takeIf { it != "observed" }
                appendLine("- ${fact.id} | ${fact.title} | ${status ?: "-"} | ${fact.target ?: "-"}")
                if (request.isNotBlank()) {
                    if (full) {
                        appendLine("  原始请求：")
                        request.lines().forEach { appendLine("    $it") }
                    } else {
                        appendLine("  ${request.lineSequence().firstOrNull().orEmpty()}")
                    }
                }
            }
            if (rows.isEmpty()) append("没有匹配的 HTTP 证据；用 http_evidence_add 保存原始请求与响应。")
        }
    }

    /**
     * 计分链（上游 `scoreChain`）：每条得分点记录「靠什么动作拿到的」。
     *
     * 归因分三级，越靠前越可信：
     *   ① 显式 `step_id`；② 同一得分点 + 同一资产的步骤；③ 同一资产上时间不晚于它的最近一步（标注 inferred）。
     * 把推断与实证标开，是因为报告里「怎么拿到的」这句话要经得起复盘。
     */
    private suspend fun scoreChain(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_CHAIN_LIMIT)
            .coerceIn(1, HARD_MAX_CHAIN_LIMIT)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val hits = all.filter { it.kind == "score_hit" }.sortedBy { it.createdAt }.take(limit)
        val steps = all.filter { it.kind == "attack_step" }.sortedBy { it.createdAt }
        val assets = all.filter { it.kind == "asset" }.associateBy { it.id }
        val vulns = all.filter { it.kind == "vulnerability" }.associateBy { it.id }

        return buildString {
            appendLine("ok=true")
            appendLine("count=${hits.size}")
            hits.forEach { hit ->
                val payload = payloadOf(hit)
                val pointId = payload["point_id"]?.jsonPrimitive?.contentOrNull
                // 资产键：优先显式 asset_id，缺失时用记录里的目标。
                // 本移植里资产事实常以 IP 为 id、得分点也常只写 target，
                // 只认 asset_id 会让绝大多数记录掉进「未关联步骤」。
                val assetKey = payload["asset_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: hit.target
                val explicit = payload["step_id"]?.jsonPrimitive?.contentOrNull
                var inferred = false
                var step = explicit?.let { id -> steps.firstOrNull { it.id == id } }
                if (step == null && pointId != null) {
                    step = steps.firstOrNull {
                        payloadOf(it)["point_id"]?.jsonPrimitive?.contentOrNull == pointId && it.target == assetKey
                    }
                }
                if (step == null && assetKey != null) {
                    step = steps.lastOrNull { it.target == assetKey && it.createdAt <= hit.createdAt }
                    inferred = step != null
                }
                val assetIp = assetKey?.let { key ->
                    (assets[key] ?: assets.values.firstOrNull { it.target == key })?.target
                }
                val vulnTitle = payload["vuln_id"]?.jsonPrimitive?.contentOrNull?.let { vulns[it]?.title }
                val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: hit.title
                append("- $code | ${hit.target ?: assetIp ?: "-"} | points=${payload["points"]?.jsonPrimitive?.contentOrNull ?: "-"}")
                if (vulnTitle != null) append(" | 漏洞=$vulnTitle")
                when {
                    step == null -> append(" | 归因=未关联步骤")
                    inferred -> append(" | 归因=推断（同资产最近一步：${step.title}）")
                    else -> append(" | 归因=${step.title}")
                }
                appendLine()
            }
            if (hits.isEmpty()) append("还没有计分记录；用 score_hit 记分后这里会显示归因。")
        }
    }

    /**
     * 知识库统计（上游 `pocStats`）：总量、已验证数，以及按类型/来源/组件/归类/来源靶标/发现资产的分布。
     *
     * 面板按 `byCategory` 分组，所以这里按**数量降序**输出归类，让「哪类武器攒得最多」一眼可见。
     */
    private suspend fun pocStats(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val rows = recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "knowledge" }
        fun field(fact: RedTeamFactEntity, key: String): String? =
            payloadOf(fact)[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val verified = rows.count { field(it, "verified") in setOf("1", "true") }
        fun tally(key: String, fallback: String): Map<String, Int> =
            rows.groupingBy { field(it, key) ?: fallback }.eachCount()
                .entries.sortedByDescending { it.value }.associate { it.key to it.value }

        return buildString {
            appendLine("ok=true")
            appendLine("total=${rows.size}")
            appendLine("verified=$verified")
            appendLine("## 按类型")
            tally("kind", "poc").forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## 按来源")
            tally("source", "self").forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## 按归类")
            tally("category", "other").forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## Top 组件")
            tally("component", "(未标注)").entries.take(12).forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## 来源靶标")
            tally("engagement_name", "(未标注来源靶标)").entries.take(30).forEach { (key, n) -> appendLine("$key=$n") }
            appendLine("## 发现资产")
            tally("asset_target", "(未标注)").entries.take(15).forEach { (key, n) -> appendLine("$key=$n") }
        }
    }

    /**
     * 靶标总览（上游 `snapshot`）：元信息 + 事实统计 + 测试态 + 网段分布，一次给齐。
     *
     * 为什么合成一条：面板首屏与报告开头都要这几块，分三次调用会让「打开靶标」慢半拍。
     */
    private suspend fun snapshot(sessionId: String): String {
        val session = requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val assets = all.filter { it.kind == "asset" }
        val segments = assets.mapNotNull { segmentOf(it.target) }.groupingBy { it }.eachCount().toSortedMap()
        return buildString {
            appendLine("ok=true")
            appendLine("id=$sessionId")
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("scope=${session.redTeamScope}")
            appendLine("phase=${session.redTeamPhase}")
            appendLine("facts=${all.size}")
            appendLine("assets=${assets.size}")
            appendLine("## 测试态")
            assetTestStatuses.forEach { status -> appendLine("$status=${assets.count { testStatusOf(it) == status }}") }
            appendLine("## 网段（${segments.size}）")
            segments.forEach { (cidr, count) -> appendLine("- $cidr assets=$count") }
        }
    }

    /**
     * 面板首屏（上游 `bootstrap`）：数据根目录 + 当前靶标 + 可选靶标清单。
     *
     * 本移植的「靶标」就是红队会话，所以 `current` 取当前会话；
     * 上游那是一个可切换的指针文件，本移植用会话选择代替——这一条是明确的分歧，不是遗漏。
     */
    private suspend fun bootstrap(sessionId: String): String {
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        val all = sessions.listAll().filter { it.redTeamMode == "red_team" }
        return buildString {
            appendLine("ok=true")
            appendLine("root=$settingsRoot")
            appendLine("current=$sessionId")
            appendLine("engagements=${all.size}")
            all.forEach { appendLine("- ${it.id} | ${it.redTeamTarget ?: "-"} | ${it.redTeamPhase}") }
        }
    }

    /** 关系边事实：id 由两端与关系确定，重复导入不会堆出重复边。 */
    private fun edgeFact(sessionId: String, src: String, dst: String, relation: String, now: Long): RedTeamFactEntity =
        RedTeamFactEntity(
            sessionId = sessionId,
            id = "edge:$src->$dst:$relation",
            kind = "edge",
            // domain_index 用 title 里是否含 "resolves" 来筛解析关系，所以关系要写进标题。
            title = "$src $relation $dst",
            target = dst,
            severity = null,
            status = "observed",
            payload = JsonObject(
                mapOf(
                    "src_id" to JsonPrimitive(src),
                    "dst_id" to JsonPrimitive(dst),
                    "relation" to JsonPrimitive(relation),
                ),
            ).toString(),
            createdAt = now,
            updatedAt = now,
        )

    /**
     * 控制台「未读」摘要：每个页签给一个「条数 + 最近一条时间」的轻量指针。
     *
     * 面板拿它跟本地记住的上次查看状态比：有新条数、或最新时间晚于上次查看，就在页签上点一个红点；
     * 用户点开该页签后把当前值记为已读，红点消失。**只读、只数数**，不做任何重活。
     *
     * 时间戳用 epoch 毫秒（本移植的事实表就是毫秒），上游是 ISO 字符串——
     * 两边都是「越大越新」，比较语义一致，故不再做字符串转换。
     */
    private suspend fun consoleDigest(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val all = recent(sessionId, MAX_FACTS_SCANNED)
        val of = { kind: String -> all.filter { it.kind == kind } }
        val latest = { rows: List<RedTeamFactEntity> -> rows.maxOfOrNull { it.updatedAt } }

        data class Pointer(val count: Int, val at: Long?)
        val assets = of("asset")
        val steps = of("attack_step")
        val hits = of("score_hit")
        val sessions = of("webshell") + of("tunnel")
        val pointers = linkedMapOf(
            "assets" to Pointer(assets.size, latest(assets)),
            "testing" to Pointer(
                assets.count { testStatusOf(it) != "untested" },
                latest(assets.filter { testStatusOf(it) != "untested" }),
            ),
            // 「智能体」页签看的是"谁在执行"：用攻击步骤的最新动作当指针
            "agents" to Pointer(steps.size, latest(steps)),
            "sessions" to Pointer(sessions.size, latest(sessions)),
            "findings" to Pointer(of("vulnerability").size, latest(of("vulnerability"))),
            "chain" to Pointer(steps.size, latest(steps)),
            "scores" to Pointer(hits.size, latest(hits)),
            "report" to Pointer(hits.size, latest(hits)),
            "attackfiles" to Pointer(of("attack_file").size, latest(of("attack_file"))),
            // 知识库上游是跨靶标共享的另一个库；本移植的 POC 随会话走，故按本会话计。
            "knowledge" to Pointer(of("poc").size, latest(of("poc"))),
            // 提示词与技能库是随包分发的静态内容：没有"新条目"一说，永不点红点。
            "prompts" to Pointer(0, null),
            "skills" to Pointer(0, null),
        )
        return buildString {
            appendLine("ok=true")
            appendLine("session=$sessionId")
            pointers.forEach { (name, p) ->
                appendLine("$name count=${p.count} at=${p.at ?: "-"}")
            }
        }
    }

    /** 测试态总览：按状态给资产计数。界面上的进度条与「还有几台没测」都读它。 */
    private suspend fun testStats(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val assets = recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "asset" }
        val counts = assetTestStatuses.associateWith { status -> assets.count { testStatusOf(it) == status } }
        return buildString {
            appendLine("ok=true")
            appendLine("total=${assets.size}")
            counts.forEach { (status, n) -> appendLine("$status=$n") }
        }
    }

    /**
     * 三块测试视图：正在测的、最近动过的、待测队列。
     *
     * 「最近动过」排除正在测的，否则同一台机器会在两个区块各出现一次。
     * 本移植没有独立的端口表，队列里的端口数取 `asset_assess` 记下的 `open_ports`，
     * 没记过按 0 计——这会让它排在已探测的机器之后，方向是对的。
     */
    private suspend fun activeTests(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val limit = (args["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: DEFAULT_ACTIVE_TESTS)
            .coerceIn(1, HARD_MAX_ACTIVE_TESTS)
        val assets = recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "asset" }

        val testing = assets.filter { testStatusOf(it) == "testing" }
            .sortedWith(compareByDescending<RedTeamFactEntity> { it.updatedAt }.thenByDescending { it.id })
            .take(limit)
        val recentTested = assets.filter { testStatusOf(it) != "untested" && testStatusOf(it) != "testing" }
            .sortedByDescending { it.updatedAt }
            .take(limit)
        val queue = assets.filter { testStatusOf(it) == "untested" }
            .sortedWith(compareBy({ queueRank(it).first }, { queueRank(it).second }, { queueRank(it).third }))
            .take(limit)
        val untested = assets.count { testStatusOf(it) == "untested" }

        fun line(fact: RedTeamFactEntity): String {
            val payload = payloadOf(fact)
            val parts = mutableListOf(
                "id=${fact.id}",
                "ip=${fact.target ?: "-"}",
                "状态=${testStatusOf(fact)}",
            )
            payload["priority"]?.jsonPrimitive?.contentOrNull?.let { parts += "易打性=$it" }
            payload["surface"]?.jsonPrimitive?.contentOrNull?.let { parts += "面=$it" }
            payload["open_ports"]?.jsonPrimitive?.contentOrNull?.let { parts += "开放端口=$it" }
            payload["blocked_count"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "0" }?.let { parts += "受阻=$it" }
            val notes = payload["test_log"]?.jsonPrimitive?.contentOrNull?.lines()?.lastOrNull { it.isNotBlank() }
            notes?.let { parts += "最近=$it" }
            return "- " + parts.joinToString("｜")
        }

        return buildString {
            appendLine("ok=true")
            appendLine("untested=$untested")
            appendLine("## 正在测试（${testing.size}）")
            testing.forEach { appendLine(line(it)) }
            appendLine("## 最近测过（${recentTested.size}）")
            recentTested.forEach { appendLine(line(it)) }
            appendLine("## 待测队列（按易打性/端口数排序，取前 ${queue.size}）")
            queue.forEach { appendLine(line(it)) }
        }
    }

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

    /**
     * 靶标目录：与上游 `<root>/engagements/<id>` 同构，随事实库一起备份迁移。
     * 攻击文件必须真写在磁盘上——上游这一块的意义就是「供复用与交付」，
     * 只记一条元数据事实等于交付时手上什么都没有。
     */
    private fun engagementRoot(sessionId: String): java.io.File =
        java.io.File(settingsRoot, "engagements/" + sessionId.replace(Regex("[^\\w.-]"), "_"))

    /** 攻击文件根目录：`<靶标>/attack-files/<目标>/<文件名>`。 */
    private fun attackFilesDir(sessionId: String): java.io.File =
        java.io.File(engagementRoot(sessionId), "attack-files")

    /**
     * 写入一个攻击文件，返回落盘路径。
     *
     * 写路径也过一遍根目录校验：`target` 为 `..`/`.` 时 `slugTarget` 会原样返回，
     * join 之后就跑出 `attack-files/` 了。
     */
    private fun writeAttackFile(sessionId: String, args: JsonObject): Pair<String, String> {
        val target = args["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(target.isNotEmpty()) { "attack file target required（IP / URL / C 段）" }
        // 文件名里的路径分隔符要抹掉：上游同样把 `/\` 换成 `-`，避免写到目标目录之外。
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            .replace('/', '-').replace('\\', '-')
        require(name.isNotEmpty()) { "attack file name required" }
        val evidence = args["evidence"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        require(evidence.isNotEmpty()) {
            "attack file evidence required：只收录实际生效的脚本/POC/EXP，请写明验证效果"
        }

        val root = attackFilesDir(sessionId)
        val dir = java.io.File(root, RedTeamIpUtils.slugTarget(target))
        dir.mkdirs()
        val path = RedTeamValidate.assertPathWithin(java.io.File(dir, name).path, listOf(root.path))

        val content = args["content"]?.jsonPrimitive?.contentOrNull
        val sourcePath = args["path"]?.jsonPrimitive?.contentOrNull
        when {
            !content.isNullOrEmpty() -> java.io.File(path).writeText(content)
            !sourcePath.isNullOrEmpty() -> {
                val source = java.io.File(sourcePath).takeIf { it.isAbsolute }
                    ?: java.io.File(engagementRoot(sessionId), sourcePath)
                require(source.isFile) { "source path not found: $sourcePath" }
                source.copyTo(java.io.File(path), overwrite = true)
            }
            else -> error("attack file content or path required")
        }
        return path to target
    }

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
        val board = RedTeamScoring.applyScoreCaps(hits, pointsByCode = scoringPoints(sessionId))
        val byCode = board.items.groupBy { it.hit.code }

        // 演练方按这条推进线读报告，所以分桶必须在这里算，不能让每个消费方各推一次。
        val stages = stageBuckets(sessionId, board.items)
        return buildString {
            appendLine("ok=true")
            appendLine("target=${session.redTeamTarget.orEmpty()}")
            appendLine("hits=${board.items.size} capped=${board.cappedCount}")
            appendLine("total=${board.totalPoints}")
            appendLine()
            appendLine("## 阶段进度")
            RedTeamStage.DEFAULT_STAGES.forEach { stage ->
                val bucket = stages[stage.code]
                val points = bucket?.sumOf { it.points } ?: 0
                val count = bucket?.size ?: 0
                val unscoredNote = if (stage.scored == 0) " · 前置阶段不计分" else ""
                appendLine("- ${stage.name}（${stage.code}）$count 条 · $points 分$unscoredNote")
            }
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
    private suspend fun scorePointList(sessionId: String): String {
        val overrides = scoreOverrides(sessionId)
        val merged = RedTeamScorePointEdit.mergeInto(overrides = overrides)
        return buildString {
            RedTeamScoreCatalog.GROUPS.forEach { group ->
                val points = merged.values.filter { it.category == group.code }.sortedBy { it.src }
                if (points.isEmpty()) return@forEach
                appendLine("## ${group.name}（${group.code}）")
                points.forEach { p ->
                    val off = if (!p.enabled) "｜**已停用**" else ""
                    appendLine("- ${p.code}｜${p.name}｜档位 ${p.tier}｜上限 ${if (p.cap > 0) p.cap else "不限"}｜口径 ${p.dedupScope.id}$off")
                }
            }
            val custom = merged.values.filter { it.src == 0 }
            if (custom.isNotEmpty()) {
                appendLine("## 自建得分点")
                custom.forEach { p ->
                    val off = if (!p.enabled) "｜**已停用**" else ""
                    appendLine("- ${p.code}｜${p.name}｜${p.points} 分｜${p.category.orEmpty()}$off")
                }
            }
            val disabled = overrides.builtinEnabled.filterValues { !it }.keys
            if (disabled.isNotEmpty()) {
                appendLine()
                appendLine("已停用：${disabled.joinToString("、")}")
            }
        }
    }

    /**
     * 当前生效的得分点表 = 内置目录 + 用户覆盖。
     *
     * 三处计分（态势摘要、评分报告、总报告）必须共用这一份：
     * 只要有一处直接用静态目录，自建点与停用就只在部分视图生效，同一份战果会算出不同总分。
     */
    private suspend fun scoringPoints(sessionId: String): Map<String, ScorePoint> =
        RedTeamScorePointEdit.mergeInto(overrides = scoreOverrides(sessionId))

    /** 从事实库读出阶段覆盖层。 */
    private suspend fun stageOverrides(sessionId: String): Pair<Map<String, RedTeamStage.Stage>, Map<String, Int>> {
        val overrides = mutableMapOf<String, RedTeamStage.Stage>()
        val order = mutableMapOf<String, Int>()
        recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "stage" }.forEach { fact ->
            val payload = payloadOf(fact)
            val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: fact.id
            val sections = runCatching {
                Json.decodeFromString<List<Map<String, String>>>(payload["sections"]?.jsonPrimitive?.contentOrNull ?: "[]")
            }.getOrNull().orEmpty().mapNotNull { row ->
                val label = row["label"] ?: return@mapNotNull null
                RedTeamStage.Section(label, row["items"]?.split("\u0001")?.filter { it.isNotBlank() }.orEmpty())
            }
            val patch = RedTeamStage.StagePatch(
                code = code,
                name = payload["name"]?.jsonPrimitive?.contentOrNull,
                subtitle = payload["subtitle"]?.jsonPrimitive?.contentOrNull,
                color = payload["color"]?.jsonPrimitive?.contentOrNull,
                goal = payload["goal"]?.jsonPrimitive?.contentOrNull,
                sections = sections.takeIf { it.isNotEmpty() },
                tools = payload["tools"]?.jsonPrimitive?.contentOrNull,
                transition = payload["transition"]?.jsonPrimitive?.contentOrNull,
            )
            overrides[code] = RedTeamStage.applyPatch(overrides[code], patch)
            payload["sort_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { order[code] = it }
        }
        return overrides to order
    }

    private suspend fun stageList(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val (overrides, order) = stageOverrides(sessionId)
        val stages = RedTeamStage.resolveStages(overrides, order)
        return buildString {
            appendLine("ok=true")
            appendLine("count=${stages.size}")
            stages.forEachIndexed { index, stage ->
                val edited = if (overrides.containsKey(stage.code)) " ·已编辑" else ""
                appendLine()
                appendLine("## ${index + 1}. ${stage.name}（${stage.code}）$edited")
                appendLine("subtitle=${stage.subtitle}")
                appendLine("color=${stage.color}")
                appendLine("goal=${stage.goal}")
                stage.sections.forEach { section ->
                    appendLine("- ${section.label}：${section.items.joinToString("；")}")
                }
                appendLine("tools=${stage.tools}")
                if (stage.transition.isNotEmpty()) appendLine("transition=${stage.transition}")
            }
        }
    }

    /**
     * 编辑阶段。**部分补丁**语义：没传的字段沿用当前值，再回落到默认——
     * 否则界面上只改一句「目标」就会把名称、手段分组、工具全清空。
     */
    private suspend fun saveStage(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val code = args["code"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("stage.code required")
        val (existing, order) = stageOverrides(sessionId)
        val incoming = args["sections"]?.jsonPrimitive?.contentOrNull?.let { raw ->
            runCatching { Json.decodeFromString<List<Map<String, String>>>(raw) }.getOrNull()
        }
        val patch = RedTeamStage.StagePatch(
            code = code,
            name = args["name"]?.jsonPrimitive?.contentOrNull,
            subtitle = args["subtitle"]?.jsonPrimitive?.contentOrNull,
            color = args["color"]?.jsonPrimitive?.contentOrNull,
            goal = args["goal"]?.jsonPrimitive?.contentOrNull,
            sections = incoming?.mapNotNull { row ->
                val label = row["label"] ?: return@mapNotNull null
                RedTeamStage.Section(label, row["items"]?.split("|")?.filter { it.isNotBlank() }.orEmpty())
            },
            tools = args["tools"]?.jsonPrimitive?.contentOrNull,
            transition = args["transition"]?.jsonPrimitive?.contentOrNull,
            sortOrder = args["sort_order"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        )
        val next = RedTeamStage.applyPatch(existing[code], patch)
        record(
            sessionId = sessionId,
            kind = RedTeamFactKind.STAGE,
            title = next.name,
            payload = JsonObject(
                buildMap {
                    put("code", JsonPrimitive(code))
                    put("name", JsonPrimitive(next.name))
                    put("subtitle", JsonPrimitive(next.subtitle))
                    put("color", JsonPrimitive(next.color))
                    put("goal", JsonPrimitive(next.goal))
                    put("tools", JsonPrimitive(next.tools))
                    put("transition", JsonPrimitive(next.transition))
                    // 条目用 \u0001 连接，避免与正文里的分号/竖线冲突。
                    put(
                        "sections",
                        JsonPrimitive(
                            Json.encodeToString(
                                next.sections.map { mapOf("label" to it.label, "items" to it.items.joinToString("\u0001")) },
                            ),
                        ),
                    )
                    (patch.sortOrder ?: order[code])?.let { put("sort_order", JsonPrimitive(it.toString())) }
                },
            ).toString(),
            id = "stage:$code",
        )
        return "ok=true\ncode=$code\nname=${next.name}\nsections=${next.sections.size}"
    }

    /** 从事实库读出得分点覆盖层：内置启停 + 自建点 + 被停用的自建点。 */
    private suspend fun scoreOverrides(sessionId: String): RedTeamScorePointEdit.Overrides {
        val rows = recent(sessionId, MAX_FACTS_SCANNED).filter { it.kind == "score_point" }
        val builtinEnabled = mutableMapOf<String, Boolean>()
        val custom = mutableListOf<RedTeamScorePointEdit.CustomPoint>()
        val disabledCustom = mutableSetOf<String>()
        rows.forEach { fact ->
            val payload = payloadOf(fact)
            val code = payload["code"]?.jsonPrimitive?.contentOrNull ?: fact.id
            val enabled = payload["enabled"]?.jsonPrimitive?.contentOrNull != "false"
            val builtin = RedTeamScoring.POINTS_BY_CODE.containsKey(code)
            if (builtin) {
                builtinEnabled[code] = enabled
            } else {
                custom += RedTeamScorePointEdit.CustomPoint(
                    code = code,
                    name = payload["name"]?.jsonPrimitive?.contentOrNull ?: fact.title,
                    category = payload["category"]?.jsonPrimitive?.contentOrNull,
                    points = payload["points"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
                    description = payload["description"]?.jsonPrimitive?.contentOrNull,
                    enabled = enabled,
                )
                if (!enabled) disabledCustom += code
            }
        }
        return RedTeamScorePointEdit.Overrides(builtinEnabled, custom, disabledCustom)
    }

    /**
     * 保存得分点。
     *
     * 内置点只落「启用/停用」——分值由规则锁定（同一条规则的上限按组内累计，
     * 单独改分值会让一条命中吃掉整组上限）。要自定义分值请新增一个点。
     */
    private suspend fun saveScorePoint(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val existing = scoreOverrides(sessionId)
        val takenCodes = RedTeamScoring.POINTS_BY_CODE.keys + existing.custom.mapNotNull { it.code }
        val requestedCode = args["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val (result, apply) = RedTeamScorePointEdit.save(
            existingCode = requestedCode,
            name = args["name"]?.jsonPrimitive?.contentOrNull,
            enabled = args["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
            category = args["category"]?.jsonPrimitive?.contentOrNull,
            points = args["points"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            description = args["description"]?.jsonPrimitive?.contentOrNull,
            takenCodes = takenCodes,
        )
        val next = existing.apply()
        val entry = next.custom.firstOrNull { it.code == result.code }
        val enabled = next.builtinEnabled[result.code]
            ?: entry?.enabled
            ?: true
        record(
            sessionId = sessionId,
            kind = RedTeamFactKind.SCORE_POINT,
            title = entry?.name ?: (RedTeamScoring.POINTS_BY_CODE[result.code]?.name ?: result.code),
            payload = JsonObject(
                buildMap {
                    put("code", JsonPrimitive(result.code))
                    put("enabled", JsonPrimitive(enabled.toString()))
                    entry?.let {
                        put("name", JsonPrimitive(it.name))
                        put("points", JsonPrimitive(it.points.toString()))
                        it.category?.let { c -> put("category", JsonPrimitive(c)) }
                        it.description?.let { d -> put("description", JsonPrimitive(d)) }
                    }
                },
            ).toString(),
            id = "score_point:${result.code}",
        )
        return buildString {
            appendLine("ok=true")
            appendLine("code=${result.code}")
            appendLine("builtin=${result.builtin}")
            appendLine("updated=${result.updated}")
            appendLine("enabled=$enabled")
            if (result.lockedFields.isNotEmpty()) appendLine("locked_fields=${result.lockedFields.joinToString(",")}")
            appendLine("note=${result.note}")
        }
    }

    /** 删除自建得分点。内置点不允许删除：删掉会让规则表缺一条、报告少一类成果。 */
    private suspend fun deleteScorePoint(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val code = args["code"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: args["id"]?.jsonPrimitive?.contentOrNull
        ?: error("delete_score_point 需要 code")
        RedTeamScorePointEdit.assertDeletable(code)
        val factId = "score_point:$code"
        val existed = recent(sessionId, MAX_FACTS_SCANNED).any { it.kind == "score_point" && it.id == factId }
        require(existed) { "score point not found: $code" }
        facts.deleteById(sessionId, factId)
        return "ok=true\ndeleted=$code"
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
                    stageCode = payload?.get("stage_code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    multiplier = RedTeamScoring
                        .scoreMultiplierOf(
                            payload?.get("ip_version")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                            payload?.get("data_scale")?.jsonPrimitive?.contentOrNull,
                            fact.target,
                        ).multiplier,
                )
            }


    /**
     * 读回攻击文件内容。
     *
     * 路径来自**库里的 path 列**，而那一列是智能体写过的——不可信输入。
     * 一旦填成 `/etc/passwd` 或 `~/.ssh/id_rsa`，界面上点一下就把文件读出来了，
     * 所以这里必须过根目录校验，越界按「拒绝」回话而**不是静默返回内容**。
     */
    private suspend fun readAttackFile(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val key = args["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: error("read_attack_file 需要 id")
        val fact = recent(sessionId, MAX_FACTS_SCANNED).firstOrNull { it.kind == "attack_file" && it.id == key }
            ?: return "ok=false\nerror=未找到攻击文件：$key"
        val stored = payloadOf(fact)["path"]?.jsonPrimitive?.contentOrNull
            ?: return "ok=false\nerror=这条记录没有落盘路径"

        val root = attackFilesDir(sessionId)
        val safe = RedTeamValidate.pathWithinOrNull(stored, listOf(root.path))
            ?: return "ok=false\nerror=（拒绝读取：路径不在本靶标目录内）$stored"

        val file = java.io.File(safe)
        if (!file.isFile) return "ok=false\nerror=（读取失败：文件不存在）$safe"
        val content = runCatching { file.readText() }.getOrElse { return "ok=false\nerror=（读取失败）${it.message}" }
        return buildString {
            appendLine("ok=true")
            appendLine("name=${payloadOf(fact)["name"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            appendLine("path=$safe")
            appendLine("---")
            append(content)
        }
    }

    /**
     * 把命中按作战阶段分桶。阶段的资产归属需要回查资产事实，所以这一步要读库。
     *
     * 归属口径与 [RedTeamStage.scoreStageOf] 完全一致：显式 stage_code > 类型特判 >
     * 资产内外网 > target 地址。资产事实不存在时退到 target 推断，而不是直接算成互联网侧。
     */
    private suspend fun stageBuckets(sessionId: String, items: List<ScoredHit>): Map<String, List<ScoredHit>> {
        val assetTargets = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == "asset" }
            .associate { fact -> fact.id to (fact.target ?: fact.title) }
        return items.groupBy { scored ->
            val scope = scored.hit.assetId
                ?.let { assetTargets[it.toString()] }
                ?.let { RedTeamIpUtils.scopeOfIp(it.substringBefore('/')) }
                ?.takeIf { it == "internal" || it == "external" }
            RedTeamStage.scoreStageOf(
                stageCode = scored.hit.stageCode,
                hitCode = scored.hit.code,
                assetScope = scope,
                target = scored.hit.target,
            )
        }
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
        val override = rolePromptOverrides(sessionId)[role.id]
        return buildString {
            appendLine("role=${role.id}｜${role.displayName}")
            appendLine("source=${if (override != null) "session-override" else "builtin"}")
            append(override ?: ROLE_DUTIES.getValue(role))
        }
    }

    /**
     * 角色提示词覆盖层：从 `prompt` 事实读，按会话缓存。
     *
     * 与 stage/score_point 一致地把用户编辑落库，而不是只放内存——上游存在 SQLite，
     * 只放内存的话重启后用户改过的提示词会静默回退成内置文案，而且没有任何提示。
     */
    private suspend fun rolePromptOverrides(sessionId: String): Map<String, String> {
        val cached = rolePrompts[sessionId]
        if (cached != null && cached.isNotEmpty()) return cached
        val loaded = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == RedTeamFactKind.PROMPT.id }
            .associate { fact ->
                val role = payloadOf(fact)["role"]?.jsonPrimitive?.contentOrNull
                    ?: fact.id.removePrefix("prompt:")
                role to fact.payload.let { payloadOf(fact)["content"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            }
            .filterValues { it.isNotBlank() }
        if (loaded.isNotEmpty()) rolePrompts.getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap() }.putAll(loaded)
        return loaded
    }

    /**
     * 保存角色提示词覆盖（上游 `savePrompt`）。
     *
     * 内容为空等于「恢复内置」：留一条空覆盖会让面板显示「已覆盖」但正文是空的，
     * 比直接删掉更难看懂，所以这里走 reset 语义。
     */
    private suspend fun saveRolePrompt(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val roleId = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: error("role is required")
        val role = RedTeamRole.entries.firstOrNull { it.id == roleId }
            ?: error("unknown role: $roleId (${RedTeamRole.entries.joinToString("/") { it.id }})")
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (content.isBlank()) {
            rolePrompts[sessionId]?.remove(role.id)
            facts.deleteById(sessionId, "prompt:${role.id}")
            events.emit(HarnessEvent.RedTeamFactChanged(sessionId, System.currentTimeMillis(), "prompt:${role.id}", "prompt", "reset"))
            return "ok=true\nrole=${role.id}\nsource=builtin"
        }
        record(
            sessionId = sessionId,
            kind = RedTeamFactKind.PROMPT,
            title = role.displayName,
            payload = JsonObject(
                mapOf(
                    "role" to JsonPrimitive(role.id),
                    "content" to JsonPrimitive(content),
                ),
            ).toString(),
            id = "prompt:${role.id}",
        )
        rolePrompts.getOrPut(sessionId) { java.util.concurrent.ConcurrentHashMap() }[role.id] = content
        return "ok=true\nrole=${role.id}\nchars=${content.length}\nsource=session-override"
    }

    /** 角色提示词清单（上游 `prompts` op）：一次性给出全部角色的标题、来源与正文。 */
    private suspend fun prompts(sessionId: String): String {
        requireBoundRedTeam(sessionId)
        val overrides = rolePromptOverrides(sessionId)
        return buildString {
            appendLine("ok=true")
            appendLine("count=${RedTeamRole.entries.size}")
            RedTeamRole.entries.forEach { role ->
                val override = overrides[role.id]
                appendLine("## ${role.id}｜${role.displayName}")
                appendLine("source=${if (override != null) "session-override" else "builtin"}")
                appendLine("chars=${(override ?: ROLE_DUTIES.getValue(role)).length}")
                appendLine(override ?: ROLE_DUTIES.getValue(role))
            }
        }
    }

    private suspend fun rolePromptReset(sessionId: String, args: JsonObject): String {
        requireBoundRedTeam(sessionId)
        val roleId = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        // 先清事实再清缓存：只清缓存的话重启后覆盖层会「复活」，用户会以为重置没生效。
        val persisted = recent(sessionId, MAX_FACTS_SCANNED)
            .filter { it.kind == RedTeamFactKind.PROMPT.id }
            .map { it.id.removePrefix("prompt:") }
            .filter { roleId.isNullOrBlank() || it == roleId }
        persisted.forEach { facts.deleteById(sessionId, "prompt:$it") }
        val bucket = rolePrompts[sessionId]
        val cached = if (bucket == null) {
            emptyList()
        } else if (roleId.isNullOrBlank()) {
            bucket.keys.toList().also { bucket.clear() }
        } else {
            listOfNotNull(roleId.takeIf { bucket.remove(it) != null })
        }
        val removed = (persisted + cached).distinct()
        if (removed.isNotEmpty()) {
            events.emit(HarnessEvent.RedTeamFactChanged(sessionId, System.currentTimeMillis(), "prompt", "prompt", "reset"))
        }
        return "ok=true\nreset=${removed.joinToString(",").ifBlank { "none" }}"
    }

    /** 技能库清单（上游 `skillCatalog`）。 */
    private suspend fun skillList(): String {
        val rows = skillStore.list()
        return buildString {
            appendLine("ok=true")
            appendLine("count=${rows.size}")
            rows.forEach { skill ->
                appendLine("- ${skill.id} | ${skill.name} | ${if (skill.enabled) "enabled" else "disabled"} | ${if (skill.builtin) "builtin" else "custom"}")
                skill.description.takeIf { it.isNotBlank() }?.let { appendLine("  desc: $it") }
                skill.whenToUse?.let { appendLine("  when: $it") }
                skill.path?.let { appendLine("  path: $it") }
            }
            if (rows.isEmpty()) append("技能库为空；用 skill_save 新建，或把 SKILL.md 目录放进技能扫描根目录后同步。")
        }
    }

    /** 读单个技能正文（上游 `skillRead`）。 */
    private suspend fun skillGet(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("skill_get 需要 id 或 name")
        val skill = skillStore.list().firstOrNull { it.id == id || it.name == id }
            ?: return "ok=false\nerror=技能不存在：$id"
        return buildString {
            appendLine("ok=true")
            appendLine("id=${skill.id}")
            appendLine("name=${skill.name}")
            appendLine("enabled=${skill.enabled}")
            appendLine("builtin=${skill.builtin}")
            skill.role?.let { appendLine("role=$it") }
            // whenToUse / description 是面板编辑器的字段，读不回来就等于面板一打开就丢内容。
            skill.whenToUse?.let { appendLine("when_to_use=$it") }
            skill.description.takeIf { it.isNotBlank() }?.let { appendLine("description=$it") }
            skill.path?.let { appendLine("path=$it") }
            appendLine("## 正文")
            append(skill.body)
        }
    }

    /** 新建/更新技能（上游 `saveSkill`）。 */
    private suspend fun skillSave(args: JsonObject): String {
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("skill_save 需要 name")
        val body = args["body"]?.jsonPrimitive?.contentOrNull
            ?: args["content"]?.jsonPrimitive?.contentOrNull
            ?: error("skill_save 需要 body（技能正文）")
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val existing = id.takeIf { it.isNotEmpty() }?.let { skillStore.read(it) }
        val saved = skillStore.save(
            RedTeamSkillStore.Skill(
                id = existing?.id ?: id,
                name = name,
                role = args["role"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.role,
                enabled = args["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: existing?.enabled ?: true,
                description = args["description"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: existing?.description.orEmpty(),
                whenToUse = args["when_to_use"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    ?: existing?.whenToUse,
                body = body,
                path = existing?.path,
                builtin = existing?.builtin ?: false,
            ),
        )
        return "ok=true\nid=${saved.id}\nname=${saved.name}\nenabled=${saved.enabled}"
    }

    /** 删除自建技能（上游 `deleteSkill`）。内置技能拒绝删除并说明原因，而不是静默不动。 */
    private suspend fun skillDelete(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: args["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("skill_delete 需要 id 或 name")
        val skill = skillStore.list().firstOrNull { it.id == id || it.name == id }
            ?: return "ok=false\nerror=技能不存在：$id"
        if (skill.builtin) return "ok=false\nerror=内置技能不可删除：${skill.name}"
        val removed = skillStore.delete(skill.id)
        return if (removed) "ok=true\ndeleted=${skill.id}" else "ok=false\nerror=删除失败：${skill.id}"
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

    /**
     * 只要求红队模式，不要求已绑靶标：本机模板库检索与靶标无关，
     * 逼着先 bind 才能查模板只会让「还没定目标先摸家底」这一步做不了。
     */
    private suspend fun requireRedTeamMode(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId is required" }
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
    }

    private suspend fun requireBoundSession(sessionId: String) {
        require(sessionId.isNotBlank()) { "sessionId is required" }
        val session = requireNotNull(sessions.findById(sessionId)) { "session not found" }
        require(session.redTeamMode == "red_team") { "red-team mode is not enabled for this session" }
        require(!session.redTeamTarget.isNullOrBlank()) { "bind a target and scope before recording facts" }
    }

    /**
     * 当前生效的并发上限：设置项 → 环境变量 `REDTEAM_MAX_AGENTS` → 默认 3。
     * 上游特意把它做成可调（上限 10），因为「同时派几个执行智能体」是使用中最常改的一项。
     */
    private val maxConcurrentAgents: Int
        get() = RedTeamSettings.maxAgentsOf(
            settingsValue = maxAgentsOverride ?: readSettingsMaxAgents(),
            envValue = System.getenv(RedTeamSettings.ENV_MAX_AGENTS),
        ).value

    /** 界面/测试可以就地覆盖，不必落盘。 */
    @Volatile
    var maxAgentsOverride: Double? = null

    private fun readSettingsMaxAgents(): Double? =
        runCatching {
            val file = java.io.File(settingsRoot, RedTeamSettings.SETTINGS_FILE_NAME)
            if (!file.isFile) null else RedTeamSettings.maxAgentsFromJson(file.readText())
        }.getOrNull()

    /** 保存并发上限并返回实际生效值（收敛到 1..10）。 */
    fun saveMaxAgents(value: Any?): Int {
        val applied = RedTeamSettings.clampMaxAgents(value)
        runCatching {
            val file = java.io.File(settingsRoot, RedTeamSettings.SETTINGS_FILE_NAME)
            file.parentFile?.mkdirs()
            file.writeText(RedTeamSettings.withMaxAgents(file.takeIf { it.isFile }?.readText(), applied))
        }
        maxAgentsOverride = applied.toDouble()
        return applied
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
        val DEFAULT_ACTIVE_TESTS = 8
        val HARD_MAX_ACTIVE_TESTS = 50

        /** 连通性实测：上游默认 6 秒、收敛到 1..20 秒；单次最多探 500 条。 */
        const val DEFAULT_PROBE_TIMEOUT_MS = 6000
        const val MIN_PROBE_TIMEOUT_MS = 1000
        const val MAX_PROBE_TIMEOUT_MS = 20000
        const val MAX_PROBE_ROWS = 500

        /** poc_search 附带返回的模板条数：上游 `Math.min(req.templateLimit || 20, 100)`。 */
        const val POC_TEMPLATE_HITS = 20

        /** HTTP 证据清单：上游默认 100、硬上限 500。 */
        const val DEFAULT_EVIDENCE_LIMIT = 100
        const val HARD_MAX_EVIDENCE_LIMIT = 500

        /** 计分链：上游默认 500、硬上限 2000。 */
        const val DEFAULT_CHAIN_LIMIT = 500
        const val HARD_MAX_CHAIN_LIMIT = 2000

        /** 易打性优先级。 */
        val ASSESS_PRIORITIES = listOf("high", "medium", "low")
    }
}
