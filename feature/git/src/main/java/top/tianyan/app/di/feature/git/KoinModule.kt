package top.tianyan.app.di.feature.git

import org.koin.dsl.module
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.ui.git.GitCredentialsStore
import top.tianyan.app.ui.git.GitManager
import top.tianyan.app.ui.git.GitViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:git module. */
val featureGitModule = module {
    single<GitCredentialsStore> { GitCredentialsStore(context = get(), secretManager = get()) }

    single<GitManager> { GitManager(credentialsStore = get(), linuxRuntime = lazy { get<LinuxRuntime>() }) }

    viewModel<GitViewModel> {
        GitViewModel(
            workspaceManager = get(),
            gitManager = get(),
            credentialsStore = get(),
            providerClient = get(),
        )
    }
}
