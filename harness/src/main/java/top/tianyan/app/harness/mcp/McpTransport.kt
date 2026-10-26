package top.tianyan.app.harness.mcp

import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.McpToolInfo

/** 健康探测结果：离线时 `error` 携带当前执行环境下的具体原因（如远程主机缺少可执行文件），供 UI/模型给出可读提示。 */
data class McpCheckResult(val online: Boolean, val error: String? = null)

interface McpTransport {
    suspend fun check(server: McpServerConfig): McpCheckResult
    suspend fun discover(server: McpServerConfig): List<McpToolInfo>
    suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String>
}

internal fun McpToolDto.toInfo(server: McpServerConfig, parametersJson: String) = McpToolInfo(
    serverId = server.id, serverName = server.name, name = name,
    description = description, parametersJson = parametersJson,
    annotations = annotations,
)
