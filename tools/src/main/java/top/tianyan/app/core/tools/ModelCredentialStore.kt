package top.tianyan.app.core.tools

/** Narrow encrypted credential port for model profile transfers. */
interface ModelCredentialStore {
    suspend fun setModelApiKeys(secretRef: String, values: List<String>)
    suspend fun readModelApiKeys(secretRef: String): List<String>
    suspend fun removeModelApiKey(secretRef: String)
}
