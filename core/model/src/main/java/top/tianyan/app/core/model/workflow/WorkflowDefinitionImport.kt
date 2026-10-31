package top.tianyan.app.core.model.workflow

import java.util.UUID
import kotlinx.serialization.json.Json

/** Definition-only import. Runtime snapshots and automatic trigger bindings are never imported. */
object WorkflowDefinitionImport {
    const val MAX_CHARACTERS = 524_288
    private val json = Json

    fun parse(raw: String): WorkflowDefinition {
        require(raw.length <= MAX_CHARACTERS) { "工作流 JSON 最多允许 524288 个字符" }
        require(raw.isNotBlank()) { "请粘贴完整的工作流 JSON" }
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "工作流 JSON 嵌套超过 32 层" } }
                '}', ']' -> depth--
            }
        }
        val definition = try { json.decodeFromString<WorkflowDefinition>(raw) } catch (error: Exception) {
            throw IllegalArgumentException("无法解析工作流 JSON，请检查字段名称、节点类型及字符串配置值", error)
        }
        return normalized(definition)
    }

    fun fresh(definition: WorkflowDefinition): WorkflowDefinition = normalized(definition).copy(id = "workflow_${UUID.randomUUID()}")

    internal fun normalized(definition: WorkflowDefinition): WorkflowDefinition {
        require(definition.nodes.size <= 256 && definition.edges.size <= 1024) { "工作流最多允许 256 个节点、1024 条连接" }
        val manual = definition.copy(isBuiltin = false, trigger = WorkflowTrigger.Manual(), createdAt = 0L, updatedAt = 0L)
        val issues = WorkflowValidator.validate(manual)
        require(issues.isEmpty()) { issues.take(5).joinToString { it.message } }
        return manual
    }
}
