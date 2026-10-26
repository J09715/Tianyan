package top.tianyan.app.runtime.browser.di

import top.tianyan.app.runtime.browser.BrowserEventBus
import top.tianyan.app.runtime.browser.BrowserRegistry
import top.tianyan.app.runtime.browser.BrowserRegistryImpl

object BrowserModule {
    fun provideEventBus(): BrowserEventBus = BrowserEventBus()

    fun provideRegistry(eventBus: BrowserEventBus): BrowserRegistry = BrowserRegistryImpl(eventBus)
}
