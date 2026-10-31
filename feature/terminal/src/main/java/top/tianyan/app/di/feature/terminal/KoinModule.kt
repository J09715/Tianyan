package top.tianyan.app.di.feature.terminal

import org.koin.dsl.module
import top.tianyan.app.ui.terminal.TerminalViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:terminal module. */
val featureTerminalModule = module {
    viewModel<TerminalViewModel> {
        TerminalViewModel(
            context = get(),
            terminalManager = get(),
            sessionClientRouter = get(),
            workspaceManager = get(),
            settingsDataStore = get(),
            firstUseGuidePreferences = get(),
            linuxRuntime = get(),
            remoteServers = get(),
            workspaceCatalog = get(),
        )
    }
}
