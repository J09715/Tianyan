package top.tianyan.app.ui.workflow

import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.ui.components.RuntimeIconName
import androidx.compose.ui.graphics.Color

data class NodeVisualTheme(
    val accentColor: Color,
    val icon: RuntimeIconName,
    val tag: String,
)

fun WorkflowNodeType.visualTheme(): NodeVisualTheme = when (this) {
    WorkflowNodeType.PLUGIN_ACTION -> NodeVisualTheme(Color(0xFF818CF8), RuntimeIconName.Extension, "插件动作")
    WorkflowNodeType.TRIGGER -> NodeVisualTheme(
        accentColor = Color(0xFF10B981), // 翡翠绿
        icon = RuntimeIconName.Play,
        tag = "触发器",
    )
    WorkflowNodeType.BASH_COMMAND -> NodeVisualTheme(
        accentColor = Color(0xFF06B6D4), // 终端青
        icon = RuntimeIconName.Terminal,
        tag = "Shell 命令",
    )
    WorkflowNodeType.PROCESS_SERVICE -> NodeVisualTheme(
        accentColor = Color(0xFF818CF8), // 守护紫
        icon = RuntimeIconName.Settings,
        tag = "常驻服务",
    )
    WorkflowNodeType.AGENT_INFERENCE -> NodeVisualTheme(
        accentColor = Color(0xFFA855F7), // 智能紫
        icon = RuntimeIconName.Sparkles,
        tag = "AI 推理",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> NodeVisualTheme(
        accentColor = Color(0xFF3B82F6), // 协同蓝
        icon = RuntimeIconName.Hub,
        tag = "子智能体",
    )
    WorkflowNodeType.TIANYAN_BUILD -> NodeVisualTheme(
        accentColor = Color(0xFFF59E0B), // 构建琥珀
        icon = RuntimeIconName.Package,
        tag = "离线构建",
    )
    WorkflowNodeType.CONDITION_BRANCH -> NodeVisualTheme(
        accentColor = Color(0xFFEAB308), // 分支黄
        icon = RuntimeIconName.Link,
        tag = "条件分支",
    )
    WorkflowNodeType.HUMAN_APPROVAL -> NodeVisualTheme(
        accentColor = Color(0xFFF43F5E), // 审批红
        icon = RuntimeIconName.Alert,
        tag = "人工把关",
    )
    WorkflowNodeType.HOST_ACTION -> NodeVisualTheme(
        accentColor = Color(0xFF14B8A6), // 宿主青
        icon = RuntimeIconName.Android,
        tag = "宿主系统",
    )
    WorkflowNodeType.DELAY -> NodeVisualTheme(
        accentColor = Color(0xFF94A3B8),
        icon = RuntimeIconName.Speed,
        tag = "延时等待",
    )
    WorkflowNodeType.SET_VARIABLE -> NodeVisualTheme(
        accentColor = Color(0xFF2DD4BF),
        icon = RuntimeIconName.Tune,
        tag = "设置变量",
    )
    WorkflowNodeType.TERMINAL_OUTPUT -> NodeVisualTheme(
        accentColor = Color(0xFF0EA5E9), // 归档天蓝
        icon = RuntimeIconName.Check,
        tag = "结果输出",
    )
}
