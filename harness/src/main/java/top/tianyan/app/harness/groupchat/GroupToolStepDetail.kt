package top.tianyan.app.harness.groupchat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Pulls the human-facing object out of a tool call so the office can show
 * "writing PixelOfficeSprite.kt" instead of "write_file".
 */
internal fun toolStepDetail(toolName: String, args: JsonObject): String? {
    val source = (args["arguments"] as? JsonObject) ?: args
    val name = ((args["tool"] as? JsonPrimitive)?.contentOrNull ?: toolName).lowercase()
    if (name.contains("assign") || name.contains("subagent") || name.contains("bot-management")) {
        stringArg(source, "bot_name", "name", "tool")?.let { return it.take(MAX_DETAIL) }
    }
    stringArg(source, "path", "file_path", "filePath", "target", "target_path", "targetPath")
        ?.let { return fileName(it) }
    stringArg(source, "command", "cmd")?.let { command ->
        return command.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_DETAIL)
    }
    stringArg(source, "query", "pattern", "q")?.let { return it.trim().take(MAX_DETAIL) }
    stringArg(source, "url")?.let { url ->
        return url.substringAfter("://").take(MAX_DETAIL)
    }
    stringArg(source, "tool")?.let { return it.take(MAX_DETAIL) }
    return null
}

private fun stringArg(args: JsonObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

private fun fileName(path: String): String =
    path.replace('\\', '/').trimEnd('/').substringAfterLast('/').take(MAX_DETAIL)

private const val MAX_DETAIL = 42
