package top.tianyan.app.core.datastore

import top.tianyan.app.core.security.SecretManager

/** 虚拟屏专用的手机操作模型。和聊天主模型的档案分开存。 */
data class PhoneAgentEndpoint(
    val baseUrl: String = "",
    val model: String = "",
    val apiKey: String = "",
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

internal fun encodePhoneAgentConfig(value: PhoneAgentEndpoint, secretManager: SecretManager): String? {
    val url = value.baseUrl.trim()
    val model = value.model.trim()
    val apiKey = value.apiKey.trim()
    if (url.isBlank() && model.isBlank() && apiKey.isBlank()) return null
    return secretManager.encrypt(listOf(url, model, apiKey).joinToString("\u001f"))
}

internal fun decodePhoneAgentConfig(stored: String?, secretManager: SecretManager): PhoneAgentEndpoint {
    val plain = stored?.let(secretManager::decrypt).orEmpty()
    if (plain.isBlank()) return PhoneAgentEndpoint()
    val parts = plain.split('\u001f', limit = 3)
    return PhoneAgentEndpoint(
        baseUrl = parts.getOrElse(0) { "" },
        model = parts.getOrElse(1) { "" },
        apiKey = parts.getOrElse(2) { "" },
    )
}
