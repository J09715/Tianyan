package top.tianyan.app.core.database.repository

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Bot 级 MCP 能力白名单的 JSON 编解码（从 PersonaBotRepository 抽出以控制文件尺寸）。
 * null = 不限制（团队共享库全量可用）；损坏数据解码为 null，宁可放开也不误锁。
 */
internal fun decodeCapabilityServers(raw: String?): List<String>? = raw?.let {
    runCatching { botCapabilityJson.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull()
}

internal fun encodeCapabilityServers(names: List<String>?): String? = names?.let {
    botCapabilityJson.encodeToString(ListSerializer(String.serializer()), it)
}

private val botCapabilityJson = Json { ignoreUnknownKeys = true }
