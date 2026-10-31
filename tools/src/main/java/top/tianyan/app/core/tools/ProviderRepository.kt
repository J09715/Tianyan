package top.tianyan.app.core.tools

import top.tianyan.app.core.datastore.ProviderPreferences
import kotlinx.coroutines.flow.Flow

/** Provider persistence boundary used by Settings UI and tool adapters. */
class ProviderRepository(
    private val providerPreferences: ProviderPreferences,
) : ModelCredentialStore {
    val provider: Flow<String> = providerPreferences.provider
    val baseUrl: Flow<String> = providerPreferences.baseUrl
    val model: Flow<String> = providerPreferences.model
    val apiKeyConfigured: Flow<Boolean> = providerPreferences.apiKeyConfigured

    suspend fun setProvider(value: String) = providerPreferences.setProvider(value)
    suspend fun setBaseUrl(value: String) = providerPreferences.setBaseUrl(value)
    suspend fun setModel(value: String) = providerPreferences.setModel(value)
    suspend fun setApiKey(value: String) = providerPreferences.setApiKey(value)
    suspend fun readApiKey(): String? = providerPreferences.readApiKey()
    suspend fun setModelApiKey(secretRef: String, value: String) = providerPreferences.setModelApiKey(secretRef, value)
    suspend fun readModelApiKey(secretRef: String): String? = providerPreferences.readModelApiKey(secretRef)
    override suspend fun setModelApiKeys(secretRef: String, values: List<String>) = providerPreferences.setModelApiKeys(secretRef, values)
    override suspend fun readModelApiKeys(secretRef: String): List<String> = providerPreferences.readModelApiKeys(secretRef)
    override suspend fun removeModelApiKey(secretRef: String) = providerPreferences.removeModelApiKey(secretRef)
}
