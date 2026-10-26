package top.tianyan.app.di.feature.navigation

import org.koin.dsl.module
import top.tianyan.app.plugin.ui.PluginPages
import top.tianyan.app.plugin.runtime.PluginController
import top.tianyan.app.ui.navigation.NativePluginPlatform
import top.tianyan.app.di.feature.browser.featureBrowserModule
import top.tianyan.app.di.feature.bot.featureBotModule
import top.tianyan.app.di.feature.chat.featureChatModule
import top.tianyan.app.di.feature.custom.iteration.featureCustomIterationModule
import top.tianyan.app.di.feature.developer.featureDeveloperModule
import top.tianyan.app.di.feature.git.featureGitModule
import top.tianyan.app.di.feature.home.featureHomeModule
import top.tianyan.app.di.feature.settings.featureSettingsModule
import top.tianyan.app.di.feature.terminal.featureTerminalModule
import top.tianyan.app.di.feature.workflow.featureWorkflowModule
import top.tianyan.app.di.feature.workspace.featureWorkspaceModule

/** Feature composition stays at the navigation boundary. */
val navigationModule = module {
    single { NativePluginPlatform(prootSource = { context ->
        val linux = get<top.tianyan.app.runtime.LinuxRuntime>()
        val servers = get<top.tianyan.app.core.datastore.RemoteServerRepository>()
        val source = top.tianyan.app.runtime.service.ProotWebAssetService(linux,
            top.tianyan.app.plugin.ui.web.WebPluginDemo.assets(context), { servers.activeId.value == null })
        top.tianyan.app.plugin.ui.web.WebPluginAssetLease(emptyMap(), available = { source.isAvailable },
            release = { source.close() }, load = { source.snapshot() })
    }) }
    single<PluginPages> { get<NativePluginPlatform>().pages }
    single<PluginController> { get<NativePluginPlatform>().controller(get()) }
    includes(
        featureBrowserModule,
        featureBotModule,
        featureChatModule,
        featureCustomIterationModule,
        featureDeveloperModule,
        featureGitModule,
        featureHomeModule,
        featureSettingsModule,
        featureTerminalModule,
        featureWorkflowModule,
        featureWorkspaceModule,
    )
}
