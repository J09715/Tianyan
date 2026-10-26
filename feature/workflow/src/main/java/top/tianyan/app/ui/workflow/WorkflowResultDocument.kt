package top.tianyan.app.ui.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport

/** Preserve every result value; formatting must not prevent a complete file transfer. */
internal object WorkflowResultDocument {
    private val pretty = Json { prettyPrint = true }

    fun encode(result: JsonObject): String {
        val compact = result.toString()
        require(compact.length <= WorkflowDefinitionImport.MAX_CHARACTERS) { "结果 JSON 超过文件导出上限" }
        val formatted = pretty.encodeToString(JsonObject.serializer(), result)
        return formatted.takeIf { it.length <= WorkflowDefinitionImport.MAX_CHARACTERS } ?: compact
    }

    fun pages(raw: String, pageSize: Int = 6000): List<String> {
        require(pageSize >= 2) { "分页大小至少为 2" }
        if (raw.isEmpty()) return listOf("")
        return buildList {
            var start = 0
            while (start < raw.length) {
                var end = (start.toLong() + pageSize).coerceAtMost(raw.length.toLong()).toInt()
                if (end < raw.length && raw[end - 1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
                add(raw.substring(start, end))
                start = end
            }
        }
    }
}
