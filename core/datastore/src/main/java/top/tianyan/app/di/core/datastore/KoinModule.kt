package top.tianyan.app.di.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import org.koin.dsl.module
import top.tianyan.app.core.datastore.settingsDataStore
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.AgentServerPreferences
import top.tianyan.app.core.datastore.AppStatsPreferences
import top.tianyan.app.core.datastore.AppearancePreferences
import top.tianyan.app.core.datastore.BackupPreferenceStore
import top.tianyan.app.core.datastore.BackupPreferences
import top.tianyan.app.core.datastore.BrowserPreferences
import top.tianyan.app.core.datastore.FirstUseGuidePreferences
import top.tianyan.app.core.datastore.FtpPreferences
import top.tianyan.app.core.datastore.OnboardingPreferences
import top.tianyan.app.core.datastore.ProviderPreferences
import top.tianyan.app.core.datastore.RegistryPreferences
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.datastore.SshPreferences
import top.tianyan.app.core.datastore.TerminalPreferences
import top.tianyan.app.core.datastore.ToolPreferences
import top.tianyan.app.core.datastore.WorkshopPreferences

/** Dependency registrations owned by the core:datastore module. */
val coreDatastoreModule = module {
    // ChatIdentityPreferences 的主构造器收 DataStore<Preferences>，次构造器收 Context。
    // Koin 的图校验按主构造器反射，之前只注册了 `context = get()` 这条次构造器路径，
    // 校验时就会报 "Missing definition for 'dataStore'"。这里显式注册共享的 settings
    // DataStore（同一个 preferencesDataStore 委托实例），两条构造路径都能解析。
    single<DataStore<Preferences>> {
        get<android.content.Context>().applicationContext.settingsDataStore
    }
    single { top.tianyan.app.core.datastore.ChatIdentityPreferences(dataStore = get()) }
    single(org.koin.core.qualifier.named("chatIdentityImages")) {
        val context = get<android.content.Context>().applicationContext
        top.tianyan.app.core.datastore.ChatBackgroundImageStore(
            java.io.File(context.filesDir, top.tianyan.app.core.model.ChatIdentityStyle.IMAGE_DIRECTORY),
            { uri -> context.contentResolver.openInputStream(uri) }, maxBytes = 10L * 1024 * 1024,
        )
    }
    single { top.tianyan.app.core.datastore.ChatBackgroundPreferences(context = get()) }
    single { top.tianyan.app.core.datastore.ChatBackgroundImageStore(context = get()) }
    single { top.tianyan.app.core.datastore.PluginPreferences(context = get()) }
    single { top.tianyan.app.core.datastore.PluginLibraryPreferences(context = get()) }
    single { top.tianyan.app.core.datastore.ContainerBackendPreferences(context = get()) }
    single<top.tianyan.app.core.datastore.RemoteServerRepository> {
        top.tianyan.app.core.datastore.EncryptedRemoteServerRepository(get(), get())
    }
    single<BackupPreferenceStore> { BackupPreferences(context = get()) }
    single<AppearancePreferences> { AppearancePreferences(store = get()) }

    single<TerminalPreferences> { TerminalPreferences(store = get()) }

    single<RuntimePreferences> { RuntimePreferences(store = get()) }

    single<WorkshopPreferences> { WorkshopPreferences(store = get()) }

    single<SshPreferences> { SshPreferences(store = get()) }

    single<FtpPreferences> { FtpPreferences(store = get()) }

    single<AgentPreferences> { AgentPreferences(store = get()) }

    single<OnboardingPreferences> { OnboardingPreferences(store = get()) }

    single<ToolPreferences> { ToolPreferences(store = get()) }

    single<FirstUseGuidePreferences> { FirstUseGuidePreferences(store = get()) }

    single<RegistryPreferences> { RegistryPreferences(store = get()) }

    single<AppStatsPreferences> { AppStatsPreferences(store = get()) }

    single<ProviderPreferences> { ProviderPreferences(store = get()) }

    single<BrowserPreferences> { BrowserPreferences(store = get()) }

    single<AgentServerPreferences> { AgentServerPreferences(store = get()) }
    single { top.tianyan.app.core.datastore.McpClientPreferences(context = get()) }
    single { top.tianyan.app.core.datastore.McpToolPolicyPreferences(context = get()) }

    single<SettingsDataStore> { SettingsDataStore(context = get(), secretManager = get()) }
}
