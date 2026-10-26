package top.tianyan.app.di.harness

import org.koin.core.scope.Scope
import top.tianyan.app.harness.plugins.HarnessPluginRegistry
import top.tianyan.app.harness.plugins.PluginNodeExecutor
import top.tianyan.app.harness.workflow.*

internal fun builtinWorkflowExecutors(scope: Scope): Set<NodeExecutor> = with(scope) {
    setOf(get<PassthroughNodeExecutor>(), get<ApprovalNodeExecutor>(), get<LinuxNodeExecutor>(), get<AgentNodeExecutor>(),
        get<ConditionNodeExecutor>(), get<SetVariableNodeExecutor>(), get<HostActionNodeExecutor>(),
        PluginNodeExecutor(get(), get(), get())) + get<HarnessPluginRegistry>().workflowExecutors()
}
