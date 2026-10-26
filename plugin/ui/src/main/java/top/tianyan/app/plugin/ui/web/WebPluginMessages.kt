package top.tianyan.app.plugin.ui.web

import kotlinx.serialization.json.*
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.runtime.PluginCapabilityCall
import top.tianyan.app.plugin.runtime.PluginCapabilityResult
import top.tianyan.app.plugin.runtime.PluginCapabilityInput

/** No identity, environment, shell, credential or authorization fields accepted on the wire. */
internal object WebPluginMessages {
    fun decode(text: String): PluginCapabilityCall {
        require(text.length <= 4096)
        // Bound depth before parsing while allowing punctuation inside string values.
        var quoted = false
        var escaped = false
        var depth = 0
        var fields = 0
        for (character in text) {
            if (quoted) {
                if (escaped) escaped = false
                else if (character == '\\') escaped = true
                else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{' -> { depth++; require(depth == 1) }
                '}' -> { depth--; require(depth == 0) }
                '[' , ']' -> error("Nested values are unsupported")
                ':' -> fields++
            }
        }
        val root = Json.parseToJsonElement(text).jsonObject
        require(fields == root.size) // Reject duplicate fields even when the parser keeps the last.
        require(root.keys == setOf("id", "action", "target") || root.keys == setOf("id", "action", "target", "text"))
        fun string(key: String, limit: Int): String {
            val value = root.getValue(key).jsonPrimitive
            require(value.isString && value.content.length in 1..limit)
            return value.content
        }
        val id = string("id", 64)
        require(id.matches(Regex("[A-Za-z0-9_-]+")))
        val call = PluginCapabilityCall(id, PluginCapability(string("action", 128), string("target", 256)),
            if ("text" in root) string("text", PluginCapabilityInput.MAX_TEXT_LENGTH) else null)
        require(PluginCapabilityInput.valid(call))
        return call
    }

    fun encode(result: PluginCapabilityResult): String = buildJsonObject {
        put("id", result.id)
        put("success", result.success)
        put("text", result.text)
    }.toString()

    /** Incremental chunk of a live-service stream; the final envelope keeps `more` absent/false. */
    fun encodeChunk(id: String, text: String): String = buildJsonObject {
        put("id", id)
        put("success", true)
        put("text", text)
        put("more", true)
    }.toString()
}
