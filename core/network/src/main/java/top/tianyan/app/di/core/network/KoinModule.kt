package top.tianyan.app.di.core.network

import org.koin.dsl.module
import top.tianyan.app.core.network.AppUpdateManager
import top.tianyan.app.core.network.CcSwitchClient
import top.tianyan.app.core.network.ChecksumVerifier
import top.tianyan.app.core.network.HttpClientProvider
import top.tianyan.app.core.network.ResumableFileDownloader

/** Dependency registrations owned by the core:network module. */
val coreNetworkModule = module {
    single<AppUpdateManager> { AppUpdateManager(context = get(), httpClient = get()) }

    single<CcSwitchClient> { CcSwitchClient(httpClientProvider = get()) }

    single<ChecksumVerifier> { ChecksumVerifier() }

    single<ResumableFileDownloader> { ResumableFileDownloader(httpClient = get(), checksumVerifier = get()) }

    single<HttpClientProvider> { HttpClientProvider() }
}
