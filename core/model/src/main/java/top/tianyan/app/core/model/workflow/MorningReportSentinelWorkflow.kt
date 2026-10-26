package top.tianyan.app.core.model.workflow

/**
 * 晨报哨兵：每天定时对当前工作区做只读巡检（Git 状态 / 最近提交 / 未提交改动），
 * AI 汇总成一份晨间简报后投递系统通知。
 *
 * 由首页「晨报哨兵」卡片创建 DAILY 定时计划驱动；也可通过 /wf morning_report_sentinel 手动触发。
 */
object MorningReportSentinel {
    const val WORKFLOW_ID = "morning_report_sentinel"

    val definition: WorkflowDefinition = WorkflowDefinition(
        id = WORKFLOW_ID,
        name = "晨报哨兵",
        description = "只读巡检当前工作区：Git 未提交变更、最近提交与改动统计，AI 汇总成晨间简报并推送系统通知。",
        category = "巡检",
        isBuiltin = true,
        trigger = WorkflowTrigger.Manual("/wf $WORKFLOW_ID"),
        nodes = listOf(
            WorkflowNode("start", WorkflowNodeType.TRIGGER, "开始", canvasX = 40f, canvasY = 120f),
            WorkflowNode(
                "git_survey",
                WorkflowNodeType.BASH_COMMAND,
                "Git 巡检",
                config = mapOf(
                    "command" to
                        "git rev-parse --is-inside-work-tree 2>/dev/null && " +
                        "{ echo '=== 未提交变更 ==='; git status --short; echo '=== 最近提交 ==='; " +
                        "git log --oneline -8; echo '=== 改动统计 ==='; git diff --stat; } || " +
                        "echo '当前工作区不是 git 仓库，本次跳过 Git 巡检'",
                ),
                timeoutSeconds = 120,
                canvasX = 280f,
                canvasY = 120f,
            ),
            WorkflowNode(
                "summarize",
                WorkflowNodeType.AGENT_INFERENCE,
                "AI 汇总晨报",
                config = mapOf(
                    "prompt" to
                        "你是晨报哨兵。以下是对工作区（路径：\${'$'}{WORKSPACE_PATH}）的只读巡检输出。" +
                        "请汇总成一份简短的中文晨报，包含：1) 未提交变更概览（按模块/主题归类）；" +
                        "2) 最近提交进展；3) 今日建议关注事项（如无则省略）。" +
                        "严格只读分析：不得修改、暂存、提交或推送任何文件，不得执行任何写操作。\n" +
                        "巡检输出：\n\${'$'}{git_survey.output}",
                ),
                timeoutSeconds = 600,
                canvasX = 520f,
                canvasY = 120f,
            ),
            WorkflowNode(
                "notify",
                WorkflowNodeType.HOST_ACTION,
                "推送晨报通知",
                config = mapOf(
                    "action" to "notification",
                    "title" to "晨报哨兵",
                    "text" to "\${'$'}{summarize.output}",
                ),
                failurePolicy = FailurePolicy.CONTINUE,
                canvasX = 760f,
                canvasY = 120f,
            ),
            WorkflowNode("done", WorkflowNodeType.TERMINAL_OUTPUT, "晨报完成", canvasX = 1000f, canvasY = 120f),
        ),
        edges = chain("start", "git_survey", "summarize", "notify", "done"),
    )

    private fun chain(vararg ids: String): List<WorkflowEdge> = ids.toList().zipWithNext().mapIndexed { index, pair ->
        WorkflowEdge("edge_$index", pair.first, "success", pair.second)
    }
}
