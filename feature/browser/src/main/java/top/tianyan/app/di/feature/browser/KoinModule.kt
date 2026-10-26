package top.tianyan.app.di.feature.browser

import org.koin.dsl.module
import top.tianyan.app.ui.browser.BrowserViewModel
import org.koin.core.module.dsl.viewModel

/** Dependency registrations owned by the feature:browser module. */
val featureBrowserModule = module {
    viewModel<BrowserViewModel> {
        BrowserViewModel(
            registry = get(),
            eventBus = get(),
            browserPreferences = get(),
        )
    }
}
