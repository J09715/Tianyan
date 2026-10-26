package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.AiModelProfileExport
import top.tianyan.app.core.model.AiProfileImportMode

suspend fun SettingsViewModel.exportAllProfilesJson(includeApiKeys: Boolean): String =
    profileBackupCodec.exportAll(includeApiKeys)

suspend fun SettingsViewModel.exportSingleProfileJson(modelId: String, includeApiKeys: Boolean): String? =
    profileBackupCodec.exportSingle(modelId, includeApiKeys)

fun SettingsViewModel.parseProfilesFromJson(rawJson: String): Result<List<AiModelProfileExport>> =
    profileBackupCodec.parseProfiles(rawJson)

suspend fun SettingsViewModel.importProfilesFromJson(rawJson: String, mode: AiProfileImportMode = AiProfileImportMode.COPY): Result<Int> =
    withContext(Dispatchers.IO) { profileBackupCodec.importProfiles(rawJson, mode) }
