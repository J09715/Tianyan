package top.tianyan.app.core.database

import kotlinx.serialization.json.*
import top.tianyan.app.core.model.BackupRecords

/** Explicit portable-data boundary. Execution queues, approvals and credential tables are excluded. */
object BackupRecordPolicy {
    val tables = listOf(
        "harness_models", "workspaces", "harness_sessions", "harness_entries", "harness_lanes",
        "harness_usage", "agent_memories", "agent_plans", "agent_scratchpads", "agent_skills",
        "agent_subagents", "quick_phrases", "build_scripts", "project_build_script_bindings",
    )
    fun key(table: String, row: JsonObject): String = when (table) {
        "workspaces" -> row.getValue("name").toString()
        "harness_lanes" -> listOf(row.getValue("sessionId"), row.getValue("name")).toString()
        "agent_plans" -> row.getValue("sessionId").toString()
        "agent_scratchpads" -> listOf(row.getValue("sessionId"), row.getValue("key")).toString()
        "project_build_script_bindings" -> row.getValue("projectName").toString()
        else -> row.getValue("id").toString()
    }

    /** Format-v1 archives predating DB 55 have no execution target. Never bind imported history to this device. */
    fun upgradeLegacy(records: BackupRecords): BackupRecords = records.copy(tables = records.tables.mapValues { (table, rows) ->
        if (table != "harness_sessions") rows else rows.map { row ->
            if ("executionTargetJson" in row) row
            else JsonObject(row + ("executionTargetJson" to JsonPrimitive("restore-required")))
        }
    })

    fun portable(records: BackupRecords): BackupRecords = BackupRecords(records.tables.mapValues { (table, rows) ->
        rows.filter { row -> table !in setOf("agent_skills", "agent_subagents") || row["isBuiltin"]?.jsonPrimitive?.intOrNull == 0 }
            .map { row -> JsonObject(row.toMutableMap().apply {
                when (table) {
                    "harness_models" -> { put("secretRef", JsonPrimitive("")); put("apiKeyCount", JsonPrimitive(0)); put("customHeaders", JsonPrimitive("")) }
                    "harness_lanes" -> { put("currentOperationId", JsonNull); put("faulted", JsonPrimitive(0)) }
                    "agent_skills" -> { put("isBuiltin", JsonPrimitive(0)); put("isImmutable", JsonPrimitive(0)); put("isEnabled", JsonPrimitive(0)) }
                    "agent_subagents" -> put("isEnabled", JsonPrimitive(0))
                    "workspaces" -> put("ownsDirectory", JsonPrimitive(0))
                    "harness_sessions" -> { put("approvalMode", JsonPrimitive("full_access")); put("executionTargetJson", JsonPrimitive("restore-required")) }
                    "build_scripts" -> put("isBuiltin", JsonPrimitive(0))
                    "agent_plans" -> if (get("status")?.jsonPrimitive?.content == "active") put("status", JsonPrimitive("cancelled"))
                }
            }) }
    })

    /** Existing sessions are retained as a unit; importing their branches would change their history. */
    fun missing(incoming: BackupRecords, current: BackupRecords): BackupRecords {
        val sessions = current.tables["harness_sessions"].orEmpty().mapTo(mutableSetOf()) { it.getValue("id") }
        return BackupRecords(incoming.tables.mapValues { (table, rows) ->
            val keys = current.tables[table].orEmpty().mapTo(mutableSetOf()) { key(table, it) }
            rows.filter { key(table, it) !in keys && !(table in sessionChildren && it["sessionId"] in sessions) &&
                !(table == "agent_memories" && it["scope"]?.jsonPrimitive?.content == "session" && it["ownerId"] in sessions) }
        })
    }

    val sessionChildren = setOf("harness_entries", "harness_lanes", "harness_usage", "agent_plans", "agent_scratchpads")
}
