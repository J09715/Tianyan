package top.tianyan.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** Protocol-only state, persisted inside the existing assistant entry JSON. Never display or redact its opaque fields. */
@Serializable
data class ResponsesTurn(
    val provider: String,
    val endpoint: String,
    val model: String,
    val output: List<JsonObject>,
    val credentialScope: String,
) {
    val payloadBytes: Int get() = output.toString().toByteArray(Charsets.UTF_8).size

    fun matches(config: ModelConfig): Boolean = config.responseApiEnabled && config.toolCallMode != ToolCallMode.JSON_TEXT && provider == config.provider &&
        endpoint == config.baseUrl.trimEnd('/') && model == config.model && credentialScope == credentialScope(config)

    companion object {
        // Bound local storage and request expansion. Oversized artifacts fall back to the ordinary transcript.
        const val MAX_PAYLOAD_BYTES = 256_000
        fun capture(config: ModelConfig, output: JsonArray?): ResponsesTurn? {
            if (output.isNullOrEmpty() || output.any { it !is JsonObject }) return null
            val turn = ResponsesTurn(config.provider, config.baseUrl.trimEnd('/'), config.model, output.map { it as JsonObject }, credentialScope(config))
            return turn.takeIf { it.payloadBytes <= MAX_PAYLOAD_BYTES }
        }
        private fun credentialScope(config: ModelConfig): String = MessageDigest.getInstance("SHA-256")
            .digest("${config.apiKey.orEmpty()}\u0000${config.customHeaders}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

/** Check the real input before the legacy sanitizer can insert placeholder tool results. */
internal fun validateResponsesTranscript(messages: List<ApiMessage>): List<ApiMessage> = messages.mapIndexed { index, message ->
    val turn = message.responsesTurn ?: return@mapIndexed message
    if (ResponsesReplay.mappingForRequest(turn, message, messages.drop(index + 1)) == null) {
        message.copy(responsesTurn = null)
    } else message
}

/** Validate the entire output group before replaying any opaque reasoning. */
internal object ResponsesReplay {
    private val json = Json

    fun text(turn: ResponsesTurn): String = turn.output.filter { it.string("type") == "message" }
        .flatMap { (it["content"] as? JsonArray).orEmpty() }
        .mapNotNull { it as? JsonObject }
        .joinToString("") { if (it.string("type") == "output_text") it.string("text") else "" }

    fun callMapping(turn: ResponsesTurn, message: ApiMessage): Map<String, String>? {
        if (message.content.orEmpty() != text(turn)) return null
        if (turn.output.any { it.string("type") !in setOf("reasoning", "message", "function_call") }) return null
        if (turn.output.any { it.string("status") in setOf("in_progress", "incomplete") }) return null
        val nativeCalls = turn.output.filter { it.string("type") == "function_call" }
        val remaining = message.tool_calls.orEmpty().toMutableList()
        if (remaining.size != nativeCalls.size) return null
        val mapping = linkedMapOf<String, String>()
        for (native in nativeCalls) {
            val id = native.string("call_id")
            if (id.isBlank() || id in mapping.values) return null
            val index = remaining.indexOfFirst { call ->
                call.function.name == native.string("name") && sameArguments(call.function.arguments, native.string("arguments"))
            }
            if (index < 0) return null
            val call = remaining.removeAt(index)
            if (call.id in mapping) return null
            mapping[call.id] = id
        }
        return mapping
    }

    fun canProject(turn: ResponsesTurn, message: ApiMessage, group: List<ToolCall>, history: List<HarnessMessage>): Boolean {
        val mapping = callMapping(turn, message) ?: return false
        // The projector drops unanswered calls; validate original group too, so a cancelled batch
        // cannot quietly replay only its successful half, or a synthetic sanitizer result.
        if (group.size != mapping.size) return false
        return group.all { call -> history.filterIsInstance<ToolResult>().any {
            it.toolCallId == call.id && !it.awaitingApproval
        } }
    }

    fun mappingForRequest(turn: ResponsesTurn, message: ApiMessage, following: List<ApiMessage>): Map<String, String>? {
        val mapping = callMapping(turn, message) ?: return null
        val results = following.takeWhile { it.role == "tool" }
        if (mapping.keys.any { id -> results.none { it.tool_call_id == id } }) return null
        return mapping
    }

    private fun sameArguments(left: String, right: String): Boolean =
        runCatching { json.parseToJsonElement(left.ifBlank { "{}" }) == json.parseToJsonElement(right.ifBlank { "{}" }) }
            .getOrDefault(false)

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
