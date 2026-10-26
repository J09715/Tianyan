package top.tianyan.app.core.model.workflow

import kotlinx.serialization.json.Json

/** Portable, definition-only JSON that can always be accepted by the definition importer. */
object WorkflowDefinitionExport {
    private val compact = Json { encodeDefaults = true }
    private val pretty = Json { encodeDefaults = true; prettyPrint = true }

    fun encode(definition: WorkflowDefinition): String {
        val portable = WorkflowDefinitionImport.normalized(definition)
        val raw = compact.encodeToString(WorkflowDefinition.serializer(), portable)
        require(raw.length <= WorkflowDefinitionImport.MAX_CHARACTERS) { "导出 JSON 超过 524288 个字符" }
        val formatted = pretty.encodeToString(WorkflowDefinition.serializer(), portable)
        return formatted.takeIf { it.length <= WorkflowDefinitionImport.MAX_CHARACTERS } ?: raw
    }
}
