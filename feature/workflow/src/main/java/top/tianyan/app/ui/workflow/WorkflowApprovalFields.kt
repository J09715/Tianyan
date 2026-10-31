package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.fillMaxWidth
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.tianyan.app.core.model.workflow.WorkflowExpansions
import top.tianyan.app.core.model.workflow.WorkflowApprovalTimeout

@Composable
internal fun WorkflowApprovalFields(config: MutableMap<String, String>) {
    RuntimeFilledTextField(value = config[WorkflowApprovalTimeout.CONFIG_KEY].orEmpty(), onValueChange = {
        if (it.isBlank()) config.remove(WorkflowApprovalTimeout.CONFIG_KEY) else config[WorkflowApprovalTimeout.CONFIG_KEY] = it.trim()
    }, label = { Text("审批等待上限（秒，可选）") },
        isError = WorkflowApprovalTimeout.validationError(config[WorkflowApprovalTimeout.CONFIG_KEY]) != null,
        supportingText = { Text("留空不限时；填写 1–3600 秒，超时按失败策略处理。通用节点超时不用于审批等待。") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    RuntimeFilledTextField(value = config["prompt"].orEmpty(), onValueChange = { config["prompt"] = it },
        label = { Text("审批确认说明（必填）") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
    RuntimeFilledTextField(value = config[WorkflowExpansions.SOURCE_KEY].orEmpty(), onValueChange = {
        if (it.isBlank()) config.remove(WorkflowExpansions.SOURCE_KEY) else config[WorkflowExpansions.SOURCE_KEY] = it.trim()
    }, label = { Text("动态扩图结果源节点 ID（可选）") },
        supportingText = { Text("填写后确认该智能体的结构化 nodes/edges 提案；需以 success 边直接连接。") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
}
