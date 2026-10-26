package top.tianyan.app.di.feature.workflow

import org.koin.dsl.module
import top.tianyan.app.ui.workflow.WorkflowViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:workflow module. */
val featureWorkflowModule = module {
    viewModel<WorkflowViewModel> {
        WorkflowViewModel(
            repository = get(),
            runManager = get(),
            scheduleRepository = get(),
            hud = get(),
            appContext = get(),
            aiModelRepository = get(),
            pathManager = get(),
            json = get(),
        )
    }
}
