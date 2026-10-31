package top.tianyan.app.ui.workflow

import top.tianyan.app.core.model.workflow.WorkflowNodeType

internal fun WorkflowNodeType.defaultTitle(): String = when (this) {
    WorkflowNodeType.PLUGIN_ACTION -> "插件动作"
    WorkflowNodeType.TRIGGER -> "触发器"
    WorkflowNodeType.BASH_COMMAND -> "执行命令"
    WorkflowNodeType.PROCESS_SERVICE -> "启动服务"
    WorkflowNodeType.AGENT_INFERENCE -> "智能体推理"
    WorkflowNodeType.SUBAGENT_DELEGATE -> "委派子智能体"
    WorkflowNodeType.TIANYAN_BUILD -> "天衍构建"
    WorkflowNodeType.CONDITION_BRANCH -> "条件分支"
    WorkflowNodeType.HUMAN_APPROVAL -> "人工审批"
    WorkflowNodeType.HOST_ACTION -> "宿主动作"
    WorkflowNodeType.DELAY -> "延时等待"
    WorkflowNodeType.SET_VARIABLE -> "设置变量"
    WorkflowNodeType.TERMINAL_OUTPUT -> "输出结果"
}

internal fun WorkflowNodeType.defaultConfig(): Map<String, String> = when (this) {
    WorkflowNodeType.PLUGIN_ACTION -> mapOf("plugin" to "tianyan.harness", "extension" to "text-result", "arguments" to "{\"text\":\"Hello\"}")
    WorkflowNodeType.AGENT_INFERENCE -> mapOf(
        "prompt" to "请分析以下上游结果并给出明确结论：\n\${previous.output}",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> mapOf(
        "taskName" to "工作流子任务",
        "prompt" to "请完成以下任务并返回结果：\n\${previous.output}",
        "role" to "",
        "writePaths" to "",
    )
    WorkflowNodeType.HOST_ACTION -> mapOf("action" to "status")
    WorkflowNodeType.CONDITION_BRANCH -> mapOf("expression" to "exitCode == 0")
    WorkflowNodeType.DELAY -> mapOf("seconds" to "1")
    WorkflowNodeType.SET_VARIABLE -> mapOf("variables" to "EXAMPLE_KEY=example_value")
    else -> emptyMap()
}
