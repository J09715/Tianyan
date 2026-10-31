package top.tianyan.app.core.tools

import java.util.Base64
import kotlinx.serialization.json.*
import top.tianyan.app.plugin.packages.SemanticVersion

data class RemotePluginPart(val url: String, val size: Long, val sha256: String, val mirrors: Map<String, String> = emptyMap())
data class RemotePluginRelease(
    val pluginId: String, val toolId: String, val name: String, val description: String,
    val execution: String, val version: String, val minimumApi: Int, val maximumApi: Int,
    val size: Long, val sha256: String, val parts: List<RemotePluginPart>,
    val packageSchema: Int = 2,
) {
    val compatible: Boolean get() = minimumApi <= 1 && maximumApi >= 1
}
data class RemotePluginCatalog(val revision: Long, val releases: List<RemotePluginRelease>) {
    fun latest(): List<RemotePluginRelease> = releases.filter { it.compatible }
        .groupBy { it.pluginId }.values.map { versions -> versions.maxBy { SemanticVersion.parse(it.version)!! } }

    companion object {
        fun verify(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): RemotePluginCatalog {
            require(bytes.size <= 1024 * 1024) { "插件目录过大" }
            require(PluginSignatureVerifier.verify(bytes,
                Base64.getDecoder().decode(signature.toString(Charsets.UTF_8).trim()), publicKey)) { "插件目录签名无效" }
            return parse(bytes.toString(Charsets.UTF_8))
        }

        internal fun parse(text: String): RemotePluginCatalog {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root.getValue("schemaVersion").jsonPrimitive.int == 1)
            val revision = root.getValue("revision").jsonPrimitive.long.also { require(it > 0) }
            val plugins = root.getValue("plugins").jsonArray.also { require(it.size <= 1000) }
            val identities = mutableSetOf<String>()
            val toolIds = mutableSetOf<String>()
            val releases = plugins.flatMap { element ->
                val p = element.jsonObject
                val id = p.string("pluginId").also { require(it.matches(Regex("[a-z0-9]+(?:[.-][a-z0-9]+)+")) && identities.add(it)) }
                val tool = p.string("toolId").also { require(it.matches(Regex("[a-z0-9][a-z0-9-]{1,63}")) && toolIds.add(it)) }
                val execution = p.string("execution").also { require(it in listOf("PROCESS", "WEB")) }
                val versions = mutableSetOf<String>()
                p.getValue("releases").jsonArray.also { require(it.size <= 100) }.map { item ->
                    val r = item.jsonObject
                    val packageSchema = r["packageSchema"]?.jsonPrimitive?.int ?: 2
                    require(packageSchema in 1..2 && (packageSchema == 2 || execution == "PROCESS"))
                    val version = r.string("version").also { require(SemanticVersion.parse(it) != null && versions.add(it)) }
                    val api = r.getValue("pluginApi").jsonObject
                    require(r.string("platform") == "linux-arm64")
                    require(r.string("channel") == "stable" && '-' !in version.substringBefore('+'))
                    val size = r.getValue("size").jsonPrimitive.long.also { require(it in 1..MAX_BYTES) }
                    val parts = r.getValue("parts").jsonArray.also { require(it.size in 1..64) }.map { part ->
                        val obj = part.jsonObject
                        val mirrors = obj["mirrors"]?.jsonObject?.mapValues { (channel, value) ->
                            require(channel == "GITEE") { "未知插件分发渠道" }
                            value.jsonPrimitive.content.also(::validateUrl)
                        }.orEmpty()
                        RemotePluginPart(obj.string("url").also(::validateUrl),
                            obj.getValue("size").jsonPrimitive.long.also { require(it in 1..MAX_BYTES) }, obj.hash(), mirrors)
                    }
                    require(parts.sumOf { it.size } == size)
                    val min = api.getValue("minimum").jsonPrimitive.int
                    val max = api.getValue("maximum").jsonPrimitive.int
                    require(min >= 1 && max >= min)
                    RemotePluginRelease(id, tool, p.string("name"), p.string("description"), execution,
                        version, min, max, size, r.hash(), parts, packageSchema)
                }
            }
            return RemotePluginCatalog(revision, releases)
        }
        const val MAX_BYTES = 8L * 1024 * 1024 * 1024
        private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content.also { require(it.length <= 4096) }
        private fun JsonObject.hash() = string("sha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        private fun validateUrl(value: String) {
            val uri = java.net.URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null)
        }
    }
}
