package top.tianyan.app.ui.workflow

import top.tianyan.app.core.model.workflow.WorkflowDefinition
import top.tianyan.app.core.model.workflow.WorkflowLayout
import top.tianyan.app.core.model.workflow.WorkflowValidator

/** Display-only projection: the approved definition and recovery journal retain original coordinates. */
internal fun workflowRunGraphPresentation(definition: WorkflowDefinition, expanded: Boolean): WorkflowDefinition =
    if (expanded && WorkflowValidator.validate(definition).isEmpty()) WorkflowLayout.arrange(definition) else definition
