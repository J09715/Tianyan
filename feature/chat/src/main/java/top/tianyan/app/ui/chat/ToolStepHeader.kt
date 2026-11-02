package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.harness.*
import top.tianyan.app.ui.components.*

/** Deliberately exclude command bodies, file content, URLs and arbitrary JSON from the collapsed summary. */
internal fun toolStepTarget(call: ToolCall): String? {
    val key = when (call.tool) {
        HarnessTool.READ, HarnessTool.WRITE, HarnessTool.EDIT -> "path"
        HarnessTool.LOAD_SKILL, HarnessTool.LOAD_RULE -> "name"
        HarnessTool.PROCESS -> "id"
        else -> return null
    }
    return (call.args[key] as? JsonPrimitive)?.contentOrNull?.take(512)
        ?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/')
        ?.replace(Regex("[\\r\\n\\t]+"), " ")?.take(100)?.takeIf { it.isNotBlank() }
}
