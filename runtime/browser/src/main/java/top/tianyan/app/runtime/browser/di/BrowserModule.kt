package top.tianyan.app.runtime.browser.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import top.tianyan.app.runtime.browser.BrowserEventBus
import top.tianyan.app.runtime.browser.BrowserRegistry
import top.tianyan.app.runtime.browser.BrowserRegistryImpl

@Module
@InstallIn(SingletonComponent::class)
object BrowserModule {
    @Provides @Singleton
    fun provideEventBus(): BrowserEventBus = BrowserEventBus()

    @Provides @Singleton
    fun provideRegistry(eventBus: BrowserEventBus): BrowserRegistry = BrowserRegistryImpl(eventBus)
}
