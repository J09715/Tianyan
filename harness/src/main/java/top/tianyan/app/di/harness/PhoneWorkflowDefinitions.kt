package top.tianyan.app.di.harness

import org.koin.core.module.Module
import top.tianyan.app.harness.PhoneAgentServices
import top.tianyan.app.harness.workflow.HostActionNodeExecutor
import top.tianyan.app.harness.workflow.VirtualScreenWorkflowExecutor
import top.tianyan.app.harness.workflow.VirtualScreenWorkflowRuns
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskRegistry
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator

internal fun Module.phoneWorkflowDefinitions() {
    factory<HostActionNodeExecutor> {
        HostActionNodeExecutor(
            appContext = get(), gui = get(), privilegeManager = get(), linuxRuntime = get(),
            guiPilot = get(), virtualScreen = get(),
        )
    }
    single<PhoneAgentServices> { PhoneAgentServices(events = get(), workflows = get()) }
    single<PhoneTaskRegistry> { get<VirtualDisplayCoordinator>().phoneTasks }
    single<VirtualScreenWorkflowRuns> { VirtualScreenWorkflowRuns(registry = get()) }
    single<VirtualScreenWorkflowExecutor> {
        VirtualScreenWorkflowExecutor(coordinator = get(), toolkit = get(), services = get(), runs = get())
    }
}
