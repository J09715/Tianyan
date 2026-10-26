package top.tianyan.app.harness.workflow

import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext
import top.tianyan.app.core.model.workflow.previousOutput
import top.tianyan.app.core.model.workflow.resultReference

internal fun interpolate(template: String, context: WorkflowRuntimeContext): String =
    WORKFLOW_VARIABLE.replace(template) { match ->
        val key = match.groupValues[1]
        context.resultReference(key) ?: when {
            key == "WORKSPACE_PATH" -> context.workspacePath
            key == "previous.output" -> context.previousOutput()
            key.endsWith(".output") -> context.nodeOutputs[key.removeSuffix(".output")]?.textOutput.orEmpty()
            else -> context.globalVariables[key].orEmpty()
        }
    }

private val WORKFLOW_VARIABLE = Regex("\\$\\{([A-Za-z0-9_.\\-]+)\\}")
