package top.tianyan.app.di.feature.workspace

import org.koin.dsl.module
import top.tianyan.app.ui.workspace.WorkshopSettingsViewModel
import top.tianyan.app.ui.workspace.WorkshopSigningViewModel
import top.tianyan.app.ui.workspace.WorkspaceBuildTaskCoordinator
import top.tianyan.app.ui.workspace.WorkspaceViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:workspace module. */
val featureWorkspaceModule = module {
    viewModel<WorkshopSettingsViewModel> {
        WorkshopSettingsViewModel(
            context = get(),
            preferences = get(),
            linuxRuntime = get(),
            pathManager = get(),
            assetSynchronizer = get(),
            buildScripts = get(),
            workspaceManager = get(),
        )
    }

    viewModel<WorkshopSigningViewModel> { WorkshopSigningViewModel(signingManager = get()) }

    single<WorkspaceBuildTaskCoordinator> {
        WorkspaceBuildTaskCoordinator(
            context = get(),
            runner = get(),
            notifier = get(),
            backgroundTaskRegistry = get(),
            workflowSignals = get(),
        )
    }

    viewModel<WorkspaceViewModel> {
        WorkspaceViewModel(
            context = get(),
            workspaceManager = get(),
            buildCoordinator = get(),
            toolManager = get(),
            linuxRuntime = get(),
            workshopPreferences = get(),
            projectTemplateStore = get(),
            firstUseGuidePreferences = get(),
        )
    }
}
