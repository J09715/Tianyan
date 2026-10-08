package top.tianyan.app.harness.redteam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 红队工具 schema 的单一事实来源。
 *
 * 之所以把 action 清单放在代码里而不是 ProviderClient 里手写 JSON 字符串：
 * 之前枚举宣告了 asset_assess / asset_test，协调器却没有对应分支，
 * 模型调用只会拿到 unsupported。测试 [RedTeamCoordinatorTest] 直接用这里的清单
 * 反查实现，任何「宣告了但没实现」的漂移都会在单元测试阶段失败。
 */
object RedTeamToolSchema {

    /** 会话控制类：不产出事实记录，只读状态或调整闸门。 */
    val CONTROL_ACTIONS = listOf(
        "session_info", "preflight", "agent_slot", "roles",
        "fact_add", "role_prompt", "role_prompt_save", "role_prompt_reset",
        "sessions", "session_check", "engagement_open", "session_bind",
        "score_points", "score_point_save", "stages", "save_stage", "role_dispatch", "group_slot",
        "asset_assess", "asset_test", "active_tests", "test_stats", "console_digest", "import_bundle",
        "template_search", "template_stats", "probe_sessions", "snapshot", "bootstrap",
        "skill_save", "skill_delete",
    )

    /**
     * 删除类：与写入分开列，避免与 UPDATE_ACTIONS 的「必须带 id」规则混淆——
     * 删除按 id 或 code 定位，缺一不可，但不需要 title。
     */
    val DELETE_ACTIONS = listOf("poc_delete", "delete_score_point")

    /** 事实写入类：全部要求 title；更新类额外要求 id。 */
    val WRITE_ACTIONS = listOf(
        "asset_add", "asset_update", "vuln_add", "vuln_update",
        "credential_add", "credential_update", "access_add", "access_update",
        "webshell_add", "webshell_update", "tunnel_add", "tunnel_update",
        "chain_add", "attack_file_add", "score_hit", "poc_add", "poc_update",
        "http_evidence_add", "knowledge_add", "skill_add",
        "asset_link",
    )

    /** 查询/报告类：只读当前会话事实库。 */
    val READ_ACTIONS = listOf(
        "asset_query", "asset_get", "asset_graph", "asset_stats", "asset_timeline",
        "vuln_query", "vuln_get", "credential_list", "access_list", "webshell_list",
        "tunnel_list", "chain", "attack_chain", "attack_file_list",
        "score_list", "score_report", "poc_search", "poc_list", "poc_get", "poc_use",
        "report_targets", "report", "domain_index", "web_list", "attack_path", "fact_query",
        "vuln_stats", "http_evidence_list", "score_chain", "poc_stats",
        "prompts", "skill_list", "skill_get",
        // 攻击文件读回：路径来自库里被智能体写过的 path 列，读取必须过靶标根目录校验。
        "read_attack_file",
    )

    /** 更新类动作必须携带 id，否则会退化成「再插一条」并污染图谱。 */
    val UPDATE_ACTIONS = WRITE_ACTIONS.filter { it.endsWith("_update") }

    /** 按 id 取单条记录的动作。 */
    val ID_REQUIRED_ACTIONS = listOf("asset_get", "poc_get", "vuln_get", "poc_use")

    val FACTS_REQUIRING_TITLE = WRITE_ACTIONS

    fun actionNames(): List<String> = CONTROL_ACTIONS + WRITE_ACTIONS + READ_ACTIONS + DELETE_ACTIONS

    fun factKindIds(): List<String> = top.tianyan.app.core.model.RedTeamFactKind.entries.map { it.id }

    /** 生成发给 provider 的完整 parameters schema。 */
    fun parameters(): JsonObject {
        val schema = buildString {
            append("""{"type":"object","properties":{""")
            append(""""action":{"type":"string","enum":[""")
            append(actionNames().joinToString(",") { "\"$it\"" })
            append("""]},""")
            append(""""sub_action":{"type":"string","enum":["status","acquire","release"],"description":"仅 agent_slot 使用"},""")
            append(""""key":{"type":"string","description":"agent_slot release 释放的槽位键"},""")
            append(""""label":{"type":"string","description":"agent_slot acquire 的任务标签"},""")
            append(""""kind":{"type":"string","enum":[""")
            append(factKindIds().joinToString(",") { "\"$it\"" })
            append("""]},""")
            // 角色白名单从枚举派生：上游注释过「写死 4 个值曾漏掉 assess」。
            append(""""role":{"type":"string","enum":[""")
            append(top.tianyan.app.core.model.RedTeamRole.entries.joinToString(",") { "\"${it.id}\"" })
            append("""]},""")
            append(""""task":{"type":"string","description":"role_dispatch 要该角色这一轮交付什么"},""")
            append(""""cidr":{"type":"string","description":"只取某个 C 段的子图，例如 10.0.0.0/24"},""")
            append(""""maxNodes":{"type":"integer","description":"图谱节点上限，默认 300，硬上限 1000"},""")
            append(""""src_kind":{"type":"string"},"src_id":{"type":"string"},""")
            append(""""dst_kind":{"type":"string"},"dst_id":{"type":"string"},""")
            append(""""relation":{"type":"string","description":"resolves/exposes/contains/trusts/shares_cert"},""")
            append(""""confidence":{"type":"number"},""")
            append(""""title":{"type":"string"},"target":{"type":"string"},""")
            append(""""severity":{"type":"string"},"status":{"type":"string"},""")
            append(""""query":{"type":"string","description":"template_search 的模板名/标签/CVE 关键字"},""")
            append(""""limit":{"type":"integer","description":"结果上限：模板检索默认 40、硬上限 200；active_tests 默认 8、硬上限 50"},""")
            append(""""timeout_ms":{"type":"integer","description":"probe_sessions 的连接超时，收敛到 1000..20000 毫秒，默认 6000"},""")
            append(""""name":{"type":"string","description":"skill_save/skill_delete 的技能名"},""")
            append(""""body":{"type":"string","description":"skill_save 的技能正文（Markdown）"},""")
            append(""""content":{"type":"string","description":"role_prompt_save 的角色提示词正文；传空字符串恢复内置文案"},""")
            append(""""when_to_use":{"type":"string","description":"skill_save 的「何时使用」"},""")
            append(""""enabled":{"type":"string","enum":["true","false"],"description":"skill_save 的启用开关"},""")
            append(""""vuln_id":{"type":"string","description":"http_evidence_list 按漏洞过滤"},""")
            append(""""asset_id":{"type":"string","description":"http_evidence_list 按资产过滤"},""")
            append(""""full":{"type":"string","enum":["true","false"],"description":"http_evidence_list 是否附完整原始报文，默认只给请求首行"},""")
            append(""""id":{"type":"string","description":"可选稳定 ID，重复写入同一实体时覆盖"}},""")
            append(""""required":["action"],"additionalProperties":true,"allOf":[""")
            val clauses = buildList {
                add("""{"if":{"properties":{"action":{"const":"fact_add"}},"required":["action"]},"then":{"required":["kind","title"]}}""")
                add("""{"if":{"properties":{"action":{"enum":[""" + FACTS_REQUIRING_TITLE.joinToString(",") { "\"$it\"" } + """]}},"required":["action"]},"then":{"required":["title"]}}""")
                add("""{"if":{"properties":{"action":{"const":"agent_slot"}},"required":["action"]},"then":{"required":["sub_action"]}}""")
                add("""{"if":{"properties":{"action":{"const":"role_prompt"}},"required":["action"]},"then":{"required":["role"]}}""")
                add("""{"if":{"properties":{"action":{"const":"role_prompt_save"}},"required":["action"]},"then":{"required":["role","content"]}}""")
                add("""{"if":{"properties":{"action":{"const":"role_dispatch"}},"required":["action"]},"then":{"required":["role","task"]}}""")
                add("""{"if":{"properties":{"action":{"const":"skill_save"}},"required":["action"]},"then":{"required":["name","body"]}}""")
                add("""{"if":{"properties":{"action":{"const":"skill_delete"}},"required":["action"]},"then":{"required":["name"]}}""")
                add("""{"if":{"properties":{"action":{"const":"skill_get"}},"required":["action"]},"then":{"required":["name"]}}""")
                add("""{"if":{"properties":{"action":{"const":"asset_link"}},"required":["action"]},"then":{"required":["src_id","dst_id","relation"]}}""")
                add("""{"if":{"properties":{"action":{"enum":[""" + ID_REQUIRED_ACTIONS.joinToString(",") { "\"$it\"" } + """]}},"required":["action"]},"then":{"required":["id"]}}""")
                add("""{"if":{"properties":{"action":{"enum":[""" + UPDATE_ACTIONS.joinToString(",") { "\"$it\"" } + """]}},"required":["action"]},"then":{"required":["id"]}}""")
            }
            append(clauses.joinToString(","))
            append("]}")
        }
        return Json.parseToJsonElement(schema).jsonObject
    }
}