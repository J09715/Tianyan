package top.tianyan.app.di.harness

import org.koin.dsl.module
import top.tianyan.app.core.tools.ExternalProviderProbe
import top.tianyan.app.harness.LlmApiRegistry
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.ProviderModelResolver
import top.tianyan.app.harness.ProviderTransport
import top.tianyan.app.harness.plugins.ExternalHarnessPlugins
import top.tianyan.app.harness.plugins.ExternalProviderConnectionProbe
import top.tianyan.app.harness.plugins.HarnessPlugin
import top.tianyan.app.harness.plugins.HarnessPluginRegistry
import top.tianyan.app.harness.plugins.builtinHarnessPlugins

internal val providerModule = module {
    single<ExternalProviderProbe> { ExternalProviderConnectionProbe(get(), get(), get()) }
    single { ExternalHarnessPlugins(get(), get()) }
    single { val external = get<ExternalHarnessPlugins>()
        HarnessPluginRegistry(builtinHarnessPlugins() + getAll<HarnessPlugin>(), external::plugins)
    }
    single { ProviderModelResolver(providerRepository = get(), modelDao = get(), settingsDataStore = get()) }
    single { val plugins = get<HarnessPluginRegistry>(); val json = get<kotlinx.serialization.json.Json>()
        ProviderTransport(client = get(), json = json, budgetGuard = get(), adapters = { client ->
            LlmApiRegistry(
                plugins.providers(
                    client,
                    json
                ), liveOverrides = { plugins.providerOverrides(client, json) })
        }) }
    single { ProviderClient(modelResolver = get(), transport = get()) }
}
