package top.tianyan.app.runtime.webchat

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.runtime.WorkspaceFileService

/**
 * 工作区文件域共享逻辑：/workspace/{project}/... 路径解析、结果解包与
 * 上传/文件管理路由。主桥（WebChatBridgeServer）与路由文件共用，规则变更只改这一处。
 */

/** 解析 /workspace/{project}/{relative}；越界（..）与非注册空间直接拒绝。 */
internal fun parseWorkspacePath(path: String): Pair<String, String> {
    val normalized = path.replace('\\', '/').trim().removeSuffix("/")
    require(normalized.startsWith("/workspace/")) { "仅允许访问已注册的 /workspace 工程" }
    val tail = normalized.removePrefix("/workspace/")
    val project = tail.substringBefore('/')
    val relative = tail.substringAfter('/', "")
    require(project.isNotBlank() && relative.split('/').none { it == ".." }) { "工作区路径无效" }
    return project to relative
}

/** 组回 Linux 侧展示路径（/workspace/{project}/{relative}）。 */
internal fun workspacePath(project: String, relative: String): String =
    "/workspace/$project" + relative.trim('/').takeIf(String::isNotEmpty)?.let { "/$it" }.orEmpty()

internal fun <T> AppResult<T>.orThrow(): T = when (this) {
    is AppResult.Success -> data
    is AppResult.Failure -> throw IllegalArgumentException(error.message)
}

/**
 * 二进制上传：body 为原始字节（application/octet-stream），path 由 query 传入。
 * AndroidHttpServer 已按 Content-Length 预读进内存，超限会直接 413。
 */
internal suspend fun WebChatBridgeServer.uploadWorkspaceFile(exchange: AndroidHttpExchange) {
    val path = requireNotNull(getQueryParam(exchange, "path")) { "缺少 path 参数" }
    val bytes = exchange.requestBody.readBytes()
    require(bytes.isNotEmpty()) { "上传内容为空" }
    require(bytes.size <= WorkspaceFileService.MAX_UPLOAD_BYTES) {
        "文件过大（上限 ${WorkspaceFileService.MAX_UPLOAD_BYTES / 1024 / 1024} MB）"
    }
    val (project, relative) = parseWorkspacePath(path)
    workspaceFiles.writeBytes(project, relative, bytes).orThrow()
    sendJson(exchange, 200, buildJsonObject {
        put("saved", true)
        put("size", bytes.size)
    })
    broadcastEvent("workspace_changed", buildJsonObject { put("path", workspacePath(project, relative)) }.toString())
}

/** 文件/目录管理动作：create_file / create_dir / delete / rename。 */
internal suspend fun WebChatBridgeServer.workspaceItemAction(exchange: AndroidHttpExchange) {
    val body = requestJson(exchange)
    val path = body["path"]?.jsonPrimitive?.content.orEmpty()
    val action = body["action"]?.jsonPrimitive?.content.orEmpty()
    val (project, relative) = parseWorkspacePath(path)
    when (action) {
        "create_file" -> workspaceFiles.createFile(project, relative).orThrow()
        "create_dir" -> workspaceFiles.createDirectory(project, relative).orThrow()
        "delete" -> workspaceFiles.deleteItem(project, relative).orThrow()
        "rename" -> {
            val newName = body["newName"]?.jsonPrimitive?.content.orEmpty()
            require(newName.isNotBlank()) { "缺少 newName" }
            workspaceFiles.renameItem(project, relative, newName).orThrow()
        }
        else -> throw IllegalArgumentException("不支持的操作：$action")
    }
    sendJson(exchange, 200, buildJsonObject { put("ok", true) })
    broadcastEvent("workspace_changed", buildJsonObject { put("path", workspacePath(project, relative)) }.toString())
}
