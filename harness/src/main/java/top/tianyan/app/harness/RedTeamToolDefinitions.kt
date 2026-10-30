package top.tianyan.app.harness

/**
 * 红队演练工具定义。
 *
 * 单独成文件：ProviderClient 已到行数棘轮上限，而这块是纯声明，与请求构建无耦合。
 *
 * **只在红队会话挂载**（见 `ModelConfig.redTeamEnabled`）：普通会话的 tools 数组里没有它，
 * 模型既不会主动尝试，硬调也会被 ToolRouting 的会话绑定校验拦下。
 */
internal object RedTeamToolDefinitions {
        /**
         * 红队演练工具定义。
         *
         * **只在红队会话挂载**：普通会话的 tools 数组里没有它，模型既不会主动尝试，
         * 也无法在硬调时通过（ToolRouting 还会再校验一次会话绑定，双层保护）。
         *
         * action 枚举直接来自 RedTeamToolSchema，避免两处清单漂移。
         */
        val TOOLS: List<ApiToolDefinition> = listOf(
            ApiToolDefinition(
                function = ApiFunctionDefinition(
                    name = "red_team",
                    description = "红队演练工作台：记录与查询资产/端口/服务/漏洞/得分事实，派发子智能体执行侦察与利用，管理演练阶段。仅在红队会话可用。先 bind 目标与授权范围，再按 recon→scan→exploit→report 阶段推进。只读动作（asset_list/asset_get/vuln_list/vuln_get/agent_slot/skill_health 等）不打断用户；写入与派发动作会请求审批。",
                    parameters = top.tianyan.app.harness.redteam.RedTeamToolSchema.parameters(),
                ),
            ),
        )
}
