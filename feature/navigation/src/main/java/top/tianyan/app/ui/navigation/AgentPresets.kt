package top.tianyan.app.ui.navigation

/** 预置 Agent 编排模板：由首页入口一键创建会话并注入（复用 pendingHealingTask 通道）。 */
object AgentPresets {
    const val ROUNDTABLE_TITLE = "🍽️ AI 圆桌会议"

    /** 圆桌会议提示词：父模型并行派 3 个只读子智能体（无 writePaths → 并行 READ_ONLY wave），再以总裁判身份汇总。 */
    val ROUNDTABLE_PROMPT: String = """
        本次会话是「AI 圆桌会议」：对当前工作区做一次只读的多视角评审。全程禁止修改、创建、删除任何文件，禁止任何写操作。

        请在同一轮回复里并行发起 3 次 invoke_subagent 调用（均不带 writePaths，保持只读）：
        1. task_id="arch"，department="engineering"，agentQuery="architecture review"——架构师视角：模块划分、依赖方向、边界清晰度、扩展性风险。
        2. task_id="security"，department="security"，agentQuery="security audit"——安全审计视角：输入校验、敏感信息、权限边界、依赖风险。
        3. task_id="perf"，department="engineering"，agentQuery="performance optimization"——性能视角：热点路径、资源占用、并发与内存、可量化的优化点。

        每个子智能体的任务 prompt 要求：审阅当前工作区，输出不超过 15 条结构化结论，每条包含（结论 / 依据（文件:行）/ 严重度 高·中·低）。
        若某部门没有可用子智能体，不要中断：先改用 department="custom"；仍不可用则由你以该视角直接分析，并在最终报告中注明该视角为降级产出。

        三个视角全部返回后，你作为总裁判输出最终 Markdown 辩论报告，包含以下小节：
        ## 与会结论（三方各自的核心判断摘要）
        ## 分歧与辩论（指出三方互相冲突的判断，给出总裁判裁决与理由）
        ## 共识与依据
        ## 行动建议（按优先级排序，仅建议不执行）
    """.trimIndent()
}
