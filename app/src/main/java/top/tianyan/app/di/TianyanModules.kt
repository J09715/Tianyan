package top.tianyan.app.di

import org.koin.dsl.module
import top.tianyan.app.di.feature.navigation.navigationModule
import top.tianyan.app.di.app.appModule
import top.tianyan.app.di.core.common.coreCommonModule
import top.tianyan.app.di.core.database.coreDatabaseModule
import top.tianyan.app.di.core.datastore.coreDatastoreModule
import top.tianyan.app.di.core.network.coreNetworkModule
import top.tianyan.app.di.core.security.coreSecurityModule
import top.tianyan.app.di.feature.onboarding.featureOnboardingModule
import top.tianyan.app.di.harness.harnessModule
import top.tianyan.app.di.harness.botHarnessModule
import top.tianyan.app.di.project.template.projectTemplateModule
import top.tianyan.app.di.runtime.runtimeModule
import top.tianyan.app.di.runtime.browser.runtimeBrowserModule
import top.tianyan.app.di.tools.toolsModule

/** Complete process graph; Android context is supplied at application startup. */
val tianyanModule = module {
    includes(
        navigationModule,
        appModule,
        coreCommonModule,
        coreDatabaseModule,
        coreDatastoreModule,
        coreNetworkModule,
        coreSecurityModule,
        featureOnboardingModule,
        harnessModule,
        botHarnessModule,
        projectTemplateModule,
        runtimeModule,
        runtimeBrowserModule,
        toolsModule,
    )
}
