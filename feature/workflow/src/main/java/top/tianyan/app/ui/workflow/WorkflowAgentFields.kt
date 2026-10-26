package top.tianyan.app.ui.workflow

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.tianyan.app.core.model.workflow.WorkflowResultSchema

@Composable
internal fun WorkflowAgentFields(config: MutableMap<String, String>, delegate: Boolean) {
    OutlinedTextField(value = config["prompt"].orEmpty(), onValueChange = { config["prompt"] = it },
        label = { Text(if (delegate) "子任务需求（必填）" else "任务需求 / 提示词（必填）") },
        supportingText = { Text("支持使用 \${previous.output} 引用上游节点输出") },
        minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = config["role"].orEmpty(), onValueChange = { config["role"] = it },
        label = { Text("智能体角色（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (!delegate) OutlinedTextField(value = config["modelId"].orEmpty(), onValueChange = { config["modelId"] = it },
        label = { Text("固定模型配置 ID（可选）") }, supportingText = { Text("留空则使用运行时选择的 WORKFLOW_MODEL_ID") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = config[WorkflowResultSchema.CONFIG_KEY].orEmpty(), onValueChange = {
        if (it.isBlank()) config.remove(WorkflowResultSchema.CONFIG_KEY) else config[WorkflowResultSchema.CONFIG_KEY] = it
    }, label = { Text("结构化结果 JSON Schema（可选）") },
        supportingText = { Text("动态扩图需声明 nodes 和 edges；详情及示例见工作流文档。") },
        minLines = 2, maxLines = 6, modifier = Modifier.fillMaxWidth())
}
