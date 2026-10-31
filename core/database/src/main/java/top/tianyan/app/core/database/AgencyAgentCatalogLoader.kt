package top.tianyan.app.core.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.model.AgentSubagent
import top.tianyan.app.core.model.AgentSubagentLocalization

@Serializable
private data class AgencyCatalogSource(
    val repository: String,
    val revision: String,
    val license: String,
    val agentCount: Int,
    val departmentCount: Int,
)

@Serializable
private data class AgencyCatalogAgent(
    val id: String,
    val name: String,
    val description: String,
    val departmentId: String,
    val promptPath: String,
    val sortOrder: Int,
)

@Serializable
private data class AgencyCatalogAsset(
    val schemaVersion: Int,
    val source: AgencyCatalogSource,
    val agents: List<AgencyCatalogAgent>,
)

@Serializable
private data class AgencyLocalizationAsset(
    val schemaVersion: Int,
    val agents: Map<String, AgentSubagentLocalization> = emptyMap(),
)

internal data class AgencyAgentCatalog(
    val revision: String,
    val profiles: List<AgentSubagent>,
)

/** Loads the complete, offline Agency Agents catalog bundled with the APK. */
class AgencyAgentCatalogLoader(
    private val context: Context,
    private val json: Json,
) {
    private val loadMutex = Mutex()
    @Volatile private var cachedCatalog: AgencyAgentCatalog? = null
    @Volatile private var cachedSource: AgencyCatalogSource? = null
    @Volatile private var cachedLocalization: Map<String, AgentSubagentLocalization>? = null

    internal suspend fun sourceRevision(): String = source().revision

    internal suspend fun load(): AgencyAgentCatalog = cachedCatalog ?: loadMutex.withLock {        cachedCatalog ?: withContext(Dispatchers.IO) {
            val catalog = readJson<AgencyCatalogAsset>(CATALOG_ASSET)
            require(catalog.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
                "Unsupported Agency Agents catalog schema ${catalog.schemaVersion}"
            }
            require(catalog.agents.size == catalog.source.agentCount) {
                "Agency Agents catalog count does not match source metadata"
            }
            AgencyAgentCatalog(
                revision = catalog.source.revision,
                profiles = catalog.agents.map { agent ->
                    AgentSubagent(
                        id = agent.id,
                        name = agent.name,
                        description = agent.description,
                        systemPrompt = stripFrontmatter(readAsset(agent.promptPath)),
                        departmentId = agent.departmentId,
                        isEnabled = true,
                        isBuiltin = true,
                        sortOrder = agent.sortOrder,
                    )
                },
            )
        }.also { cachedCatalog = it }
    }

    /** 中文界面文案；资产缺失时回退英文目录，不阻塞市场加载。 */
    internal suspend fun localization(): Map<String, AgentSubagentLocalization> =
        cachedLocalization ?: loadMutex.withLock {
            cachedLocalization ?: withContext(Dispatchers.IO) {
                runCatching {
                    readJson<AgencyLocalizationAsset>(LOCALIZATION_ASSET).agents
                }.getOrDefault(emptyMap())
            }.also { cachedLocalization = it }
        }

    private suspend fun source(): AgencyCatalogSource = cachedSource ?: loadMutex.withLock {
        cachedSource ?: withContext(Dispatchers.IO) {
            readJson<AgencyCatalogSource>(SOURCE_ASSET)
        }.also { cachedSource = it }
    }

    private inline fun <reified T> readJson(path: String): T = json.decodeFromString(readAsset(path))

    private fun readAsset(path: String): String = context.assets.open(path).bufferedReader().use { it.readText() }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = 1
        private const val SOURCE_ASSET = "agency_agents/source.json"
        private const val CATALOG_ASSET = "agency_agents/catalog.json"
        private const val LOCALIZATION_ASSET = "agency_agents/zh-CN.json"

        internal fun stripFrontmatter(markdown: String): String {
            val normalized = markdown.replace("\r\n", "\n")
            if (!normalized.startsWith("---\n")) return normalized.trim()
            val end = normalized.indexOf("\n---\n", startIndex = 4)
            return if (end < 0) normalized.trim() else normalized.substring(end + 5).trim()
        }
    }
}
