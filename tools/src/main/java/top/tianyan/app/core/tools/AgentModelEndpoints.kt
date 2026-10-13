package top.tianyan.app.core.tools

/** Resolves model-list endpoints for provider defaults and user-supplied base URLs. */
object AgentModelEndpoints {
    fun forProvider(provider: AgentProviderDefinition, rawBaseUrl: String): List<String> {
        val base = ProviderEndpointPolicy.normalizeUrl(rawBaseUrl).trimEnd('/')
        val providerBase = ProviderEndpointPolicy.normalizeUrl(provider.baseUrl).trimEnd('/')
        val providerModels = provider.modelsUrl.trim()
        val userOverride = base.isNotBlank() && (providerBase.isBlank() || !base.equals(providerBase, ignoreCase = true))

        if (!userOverride && providerModels.isNotBlank()) {
            return listOf(ProviderEndpointPolicy.normalizeUrl(providerModels))
        }
        if (base.isBlank()) {
            return providerModels.takeIf { it.isNotBlank() }
                ?.let { listOf(ProviderEndpointPolicy.normalizeUrl(it)) }
                .orEmpty()
        }
        return forBaseUrl(base)
    }

    fun forBaseUrl(rawBaseUrl: String): List<String> {
        val base = ProviderEndpointPolicy.normalizeUrl(rawBaseUrl).trimEnd('/')
        if (base.isBlank()) return emptyList()

        val withV1 = Regex("/v1/?$").containsMatchIn(base)
        val withoutV1 = base.replace(Regex("/v1/?$"), "")
        val candidates = mutableListOf<String>()

        fun addNoDup(url: String) {
            val normalized = ProviderEndpointPolicy.normalizeUrl(url)
            if (normalized.isNotBlank() && !candidates.any { it.equals(normalized, ignoreCase = true) }) {
                candidates += normalized
            }
        }

        if (withV1) {
            addNoDup("$base/models")
            addNoDup("$withoutV1/v1/models")
            addNoDup("$withoutV1/models")
            addNoDup("$withoutV1/api/tags")
        } else {
            addNoDup("$base/v1/models")
            addNoDup("$base/models")
            addNoDup("$base/api/tags")
        }
        return candidates
    }
}
