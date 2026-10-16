package top.tianyan.app.harness.acp

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 一个可被会话绑定的「智能体引擎」声明。
 *
 * 移植自 OmniBot（小万）的 ACP Agent 目录模型：天衍本体提示词提供的是「人格」，
 * 而 Claude Code / Codex / DSH 这类引擎提供的是「执行体」——它们自带工具循环、
 * 文件读写与 shell，天衍只需按协议把会话交给它，而不是把它的提示词抄进自己的
 * 系统提示里假装是它。
 *
 * 因此本类型刻意只描述「如何发现与拉起」，不含任何提示词文本：引擎的行为由它
 * 自己的进程决定，抄一份人格文本进天衍的 frozen 前缀只会制造两个事实来源。
 */
@Serializable
data class AgentEngineProfile(
    val id: String,
    val name: String,
    val description: String = "",
    /** 对应插件中心（插件与工具中心 → 智能体生态）里的安装项 id。 */
    val toolId: String,
    /** 拉起 ACP 会话的入口命令，例如 claude-agent-acp。 */
    val command: String,
    /** 健康探测用的原始 CLI 名，例如 claude；用于判断引擎本体是否装好。 */
    val discoveryCommand: String = "",
    val arguments: List<String> = emptyList(),
    val publisher: String = "",
    /** npm 上的 ACP 适配器包名，安装配方据此补齐。 */
    val acpPackage: String = "",
    /** 需要本地原生构建工具链（如 DSH 的 node-pty）。 */
    val requiresNativeBuildTools: Boolean = false,
    /** 最低 Node 版本约束，空表示不校验。 */
    val requiresNode: String = "",
    /**
     * 引擎凭据来源的 provider id（对应 agent_providers.json 的 id，如 deepseek/moonshot）。
     * 为空表示该引擎不绑定单一 provider（如 OpenCode 多模型），沿用全局单 key 注入。
     */
    val credentialProvider: String = "",
    val declaredCapabilities: JsonObject = JsonObject(emptyMap()),
)

/** 目录文件（assets/agent_engines/engines.json）的序列化形态。 */
@Serializable
internal data class AgentEngineCatalogAsset(
    val schemaVersion: Int = 1,
    val retiredEngineIds: List<String> = emptyList(),
    val engines: List<AgentEngineProfile> = emptyList(),
)

/**
 * 目录解析的纯函数部分：不碰 assets、不碰 Android，因此可以在普通 JUnit 下断言
 * 校验规则（重复 id、缺失命令、未知 schema、退役项过滤）。资源装载单独放在
 * [AgentEngineCatalogLoader] 里，让「读文件」与「解析并校验」各自可测。
 */
object AgentEngineCatalogParser {
    const val SUPPORTED_SCHEMA_VERSION = 1

    fun parse(source: String, json: Json): List<AgentEngineProfile> {
        val asset = json.decodeFromString(AgentEngineCatalogAsset.serializer(), source)
        require(asset.schemaVersion == SUPPORTED_SCHEMA_VERSION) {
            "Unsupported agent engine catalog schema ${asset.schemaVersion}"
        }
        val retired = asset.retiredEngineIds.toSet()
        val engines = asset.engines
            .filterNot { it.id in retired }
            .onEach { engine ->
                require(engine.id.isNotBlank()) { "Agent engine id is required" }
                require(engine.command.isNotBlank()) { "Agent engine ${engine.id} has no command" }
                require(engine.toolId.isNotBlank()) { "Agent engine ${engine.id} has no toolId" }
                require(engine.name.isNotBlank()) { "Agent engine ${engine.id} has no name" }
            }
        require(engines.map { it.id }.distinct().size == engines.size) {
            "Agent engine catalog contains duplicate ids"
        }
        return engines
    }
}

/**
 * 装载随 APK 分发的引擎目录。
 *
 * 与 AgencyAgentCatalogLoader 同构：内置、离线、只读，不依赖网络也不依赖安装状态。
 * 「目录里有」与「本机装好了」是两件事——前者决定选择器能否列出，后者决定能否拉起，
 * 由 [AcpEngineConnectionFactory] 在真正建会话时判定。
 */
@Singleton
class AgentEngineCatalogLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    @Volatile
    private var cached: List<AgentEngineProfile>? = null

    fun load(): List<AgentEngineProfile> = cached ?: synchronized(this) {
        cached ?: parse(
            context.assets.open(CATALOG_ASSET).bufferedReader().use { it.readText() },
        ).also { cached = it }
    }

    internal fun parse(source: String): List<AgentEngineProfile> =
        AgentEngineCatalogParser.parse(source, json)

    private companion object {
        const val CATALOG_ASSET = "agent_engines/engines.json"
    }
}

/** 目录的只读索引视图：会话绑定与选择器共用同一份事实。 */
@Singleton
class AgentEngineCatalog @Inject constructor(
    private val loader: AgentEngineCatalogLoader,
) {
    fun all(): List<AgentEngineProfile> = loader.load()

    fun find(engineId: String): AgentEngineProfile? = AgentEngineIndex.find(all(), engineId)

    /** 会话 agentId 是否指向一个引擎（而非天衍本体的人格档案）。 */
    fun isEngine(agentId: String?): Boolean = AgentEngineIndex.isEngine(all(), agentId)
}

/**
 * 索引判定抽成纯函数：会话分流是「走引擎进程」还是「走天衍本体」的唯一判据，
 * 判错会让两个执行体同时说话。因此它必须能被单测直接钉住，不依赖 Android 资源。
 */
object AgentEngineIndex {
    fun find(engines: List<AgentEngineProfile>, engineId: String): AgentEngineProfile? =
        engineId.takeIf { it.isNotBlank() }?.let { id -> engines.firstOrNull { it.id == id } }

    fun isEngine(engines: List<AgentEngineProfile>, agentId: String?): Boolean =
        find(engines, agentId.orEmpty()) != null
}
