package top.tianyan.app.ui.workflow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import top.tianyan.app.core.model.workflow.WorkflowDefinition

/** Preserve local drag positions until their source coordinates change; add/remove synchronously. */
internal fun synchronizeCanvasPositions(
    source: Map<String, Offset>, current: Map<String, Offset>, previousSource: Map<String, Offset>,
): Map<String, Offset> = source.mapValues { (id, position) ->
    if (previousSource[id] == position) current[id] ?: position else position
}

@Composable
internal fun rememberWorkflowCanvasPositions(definition: WorkflowDefinition, density: Float): MutableMap<String, Offset> {
    val current = remember(definition.id, density) { mutableStateMapOf<String, Offset>() }
    val previousSource = remember(definition.id, density) { mutableMapOf<String, Offset>() }
    val source = resolveNodePositions(definition.nodes, emptyMap(), density)
    val next = synchronizeCanvasPositions(source, current, previousSource)
    current.keys.retainAll(source.keys)
    next.forEach { (id, position) -> if (current[id] != position) current[id] = position }
    previousSource.clear()
    previousSource.putAll(source)
    return current
}
