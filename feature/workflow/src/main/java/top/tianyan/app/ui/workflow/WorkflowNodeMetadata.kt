package top.tianyan.app.ui.workflow

import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.ui.components.RuntimeIconName

data class NodeTypeMeta(
    val type: WorkflowNodeType,
    val label: String,
    val icon: RuntimeIconName,
    val summary: String,
    val guide: String,
)

fun WorkflowNodeType.metadata(): NodeTypeMeta = when (this) {
    WorkflowNodeType.PLUGIN_ACTION -> NodeTypeMeta(this, "插件动作", RuntimeIconName.Extension, "调用编译时注册的插件节点", "填写插件与扩展身份、JSON 参数。每次执行都需要确认，未知副作用不会自动重放。")
    WorkflowNodeType.TRIGGER -> NodeTypeMeta(
        type = this,
        label = "触发器",
        icon = RuntimeIconName.Play,
        summary = "流程启动入口，支持手动点击或外部事件触发",
        guide = "作为 DAG 图的起始节点。当您在顶部点击“运行调试”或接收到触发信号时，工作流从这里开始向下执行。",
    )
    WorkflowNodeType.BASH_COMMAND -> NodeTypeMeta(
        type = this,
        label = "Shell 命令行",
        icon = RuntimeIconName.Terminal,
        summary = "在 Linux PRoot 沙箱中执行一条 Shell 命令或脚本",
        guide = "可执行任何沙箱内已安装的命令（如 git, make, python, curl 等）。支持通过 \${WORKSPACE_PATH} 引用工程根目录，或使用 \${previous.output} 获取上游输出。",
    )
    WorkflowNodeType.PROCESS_SERVICE -> NodeTypeMeta(
        type = this,
        label = "后台常驻服务",
        icon = RuntimeIconName.Settings,
        summary = "启动沙箱后台常驻守护进程，不阻塞后续流程继续执行",
        guide = "适用于启动 http-server、Web 服务或监听器进程。进程 ID 会自动保存在变量 \${PROCESS_ID} 中供后续流程引用。",
    )
    WorkflowNodeType.AGENT_INFERENCE -> NodeTypeMeta(
        type = this,
        label = "AI 智能体推理",
        icon = RuntimeIconName.Sparkles,
        summary = "调度 AI 智能体结合任务指令与上游输出分析并生成结果",
        guide = "将任务指令（Prompt）与上游产出交给 AI 智能体处理。支持结合 \${previous.output} 进行代码审查、故障排查或生成修复补丁。",
    )
    WorkflowNodeType.SUBAGENT_DELEGATE -> NodeTypeMeta(
        type = this,
        label = "子智能体委派",
        icon = RuntimeIconName.Hub,
        summary = "委派独立专属子智能体执行更细粒度的任务",
        guide = "委派特定角色的子智能体（如单元测试生成器、构建错误分析员）处理专属任务，隔离主智能体上下文。",
    )
    WorkflowNodeType.TIANYAN_BUILD -> NodeTypeMeta(
        type = this,
        label = "天衍离线构建",
        icon = RuntimeIconName.Package,
        summary = "调用沙箱内离线构建引擎编译打包 Android/C++/Rust",
        guide = "调用沙箱内置的 tianyan-build 编译引擎。支持 android、cmake、cargo 等类型，可直接产出 APK 安装包或可执行二进制文件。",
    )
    WorkflowNodeType.CONDITION_BRANCH -> NodeTypeMeta(
        type = this,
        label = "条件分支路由",
        icon = RuntimeIconName.Link,
        summary = "求值表达式并按成立/不成立分流",
        guide = "支持 exitCode、output contains、\${变量} 比较，以及 && / ||。成立时 exitCode=0，否则为 1；请用边上的条件或 success/failure 端口承接分支。",
    )
    WorkflowNodeType.HUMAN_APPROVAL -> NodeTypeMeta(
        type = this,
        label = "人工审批把关",
        icon = RuntimeIconName.Alert,
        summary = "暂停流程并弹出确认对话框，等待人工确认或输入参数后继续",
        guide = "重要步骤（如发布、破坏性清理或上线）前的安全把关点。流程执行至此时将自动暂停并弹出弹窗，等待您手动确认或填写必要参数。",
    )
    WorkflowNodeType.HOST_ACTION -> NodeTypeMeta(
        type = this,
        label = "宿主系统动作",
        icon = RuntimeIconName.Android,
        summary = "打开应用、发广播、改设置、操控屏幕等 Android 宿主能力",
        guide = "穿透沙箱调用 Android：应用管理、Intent/广播、系统设置、GUI 自动化、通知与特权 shell。标「需特权」的动作要求 Shizuku 或 Root。",
    )
    WorkflowNodeType.DELAY -> NodeTypeMeta(
        type = this,
        label = "延时等待",
        icon = RuntimeIconName.Speed,
        summary = "暂停若干秒后再继续后续节点",
        guide = "用于等待界面切换、广播落地或给人工留出观察时间。",
    )
    WorkflowNodeType.SET_VARIABLE -> NodeTypeMeta(
        type = this,
        label = "设置变量",
        icon = RuntimeIconName.Tune,
        summary = "向后续节点注入 KEY=value 变量",
        guide = "每行一个赋值，右侧支持 \${VAR} 插值。写入的变量会进入全局上下文，可供条件与宿主动作引用。",
    )
    WorkflowNodeType.TERMINAL_OUTPUT -> NodeTypeMeta(
        type = this,
        label = "结果归档输出",
        icon = RuntimeIconName.Check,
        summary = "工作流终点，自动汇总所有输出日志、耗时与产出文件",
        guide = "DAG 流程的最终收尾节点。将自动收集并展示上游所有阶段的终端标准输出、退出码与生成的产物文件列表。",
    )
}
