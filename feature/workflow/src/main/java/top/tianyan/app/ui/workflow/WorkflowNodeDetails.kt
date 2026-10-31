package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeOutlinedButton

@Composable
internal fun WorkflowNodeDetails(run: WorkflowNodeRunState?) {
    var expanded by remember { mutableStateOf(false) }
    val output = run?.output
    if (run?.status in setOf(NodeRunStatus.RUNNING, NodeRunStatus.STREAMING)) {
        RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
    output?.let {
        Text("耗时 ${it.durationMs / 1000.0} 秒 · 退出码 ${it.exitCode}", style = MaterialTheme.typography.labelSmall)
        it.error?.let { error -> SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error) } }
        if (it.artifacts.isNotEmpty()) {
            Text("产物路径", style = MaterialTheme.typography.labelLarge)
            SelectionContainer { Text(it.artifacts.joinToString("\n"), fontFamily = FontFamily.Monospace) }
        }
        it.structuredResult?.let { result -> WorkflowStructuredResultButton(result) }
    }
    val log = output?.textOutput?.takeIf { it.isNotBlank() } ?: run?.progressMessage.orEmpty()
    if (log.isNotBlank()) {
        RuntimeOutlinedButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起日志" else "展开日志 / Diff") }
        if (expanded) SelectionContainer {
            Text(log, Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
