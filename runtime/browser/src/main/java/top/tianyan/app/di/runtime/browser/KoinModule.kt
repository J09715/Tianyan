package top.tianyan.app.di.runtime.browser

import org.koin.dsl.module
import top.tianyan.app.runtime.browser.BrowserEventBus
import top.tianyan.app.runtime.browser.BrowserRegistry
import top.tianyan.app.runtime.browser.di.BrowserModule.provideEventBus
import top.tianyan.app.runtime.browser.di.BrowserModule.provideRegistry

/** Dependency registrations owned by the runtime:browser module. */
val runtimeBrowserModule = module {
    single<BrowserEventBus> { provideEventBus() }

    single<BrowserRegistry> { provideRegistry(eventBus = get()) }
}
