package top.tianyan.app.harness.diagnostics

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.database.RequestDiagnosticsRepository
import java.security.MessageDigest

@Serializable
data class RequestContextSection(
    val label: String,
    val preview: String,
    /** Digest of the complete redacted, media-stripped section before display clipping. */
    val fingerprint: String? = null,
    val previewTruncated: Boolean = false,
)

/** A redacted view of a constructed request; this is not proof of successful delivery. */
@Serializable
data class RequestContextSnapshot(
    val sessionId: String,
    val operationId: String,
    val round: Int,
    val attempt: Int,
    val capturedAt: Long,
    val protocol: String,
    val bodyBytes: Long,
    val sections: List<RequestContextSection>,
    val previewTruncated: Boolean,
    val omittedSectionCount: Int = 0,
)

/** Bounded redacted diagnostics, optionally backed by an encrypted archive. */
class RequestDiagnosticsStore(private val redactor: SensitiveDataRedactor, repository: RequestDiagnosticsRepository? = null) {
    private val json = Json { prettyPrint = true }
    private val archive = RequestDiagnosticsArchive(repository)
    val snapshots = archive.snapshots
    val archiveStatus = archive.status

    fun removeSession(sessionId: String) = archive.remove(sessionId)
    suspend fun removeSessionDurably(sessionId: String) { removeSession(sessionId); awaitPersistence() }
    suspend fun awaitPersistence() = archive.awaitPersistence()
    fun close() = archive.close()

    fun record(
        sessionId: String,
        operationId: String,
        round: Int,
        attempt: Int,
        protocol: String,
        body: String,
        bodyBytes: Long,
        secrets: Collection<String> = emptyList(),
        governance: ContextGovernanceReport? = null,
    ) {
        if (sessionId.length > 256 || operationId.length > 256 || protocol.length > 256) return
        // Remove known credentials before parsing, and redact the entire entry before clipping.
        // This also removes secret echoes in a different message in the same request.
        val knownSecrets = secrets.filter { it.isNotEmpty() }.distinct().sortedByDescending { it.length }
        fun removeKnown(value: String): String {
            var safe = value
            knownSecrets.forEach { safe = safe.replace(it, "[REDACTED]") }
            return safe
        }
        val root = json.parseToJsonElement(removeKnown(body)) as? JsonObject ?: return
        val cleaned = stripMedia(root) as JsonObject
        val allSections = buildList {
            governance?.let { add(RequestContextSection("context_governance (local)",
                removeKnown(json.encodeToString(ContextGovernanceReport.serializer(), it)))) }
            cleaned.forEach { (key, value) ->
                if ((key == "messages" || key == "input") && value is JsonArray) {
                    value.forEachIndexed { index, item ->
                        val role = (item as? JsonObject)?.let {
                            (it["role"] as? JsonPrimitive)?.contentOrNull
                                ?: (it["type"] as? JsonPrimitive)?.contentOrNull
                        }.orEmpty().takeIf { it in SECTION_ROLES }.orEmpty()
                        add(RequestContextSection("$key[${index + 1}] $role", json.encodeToString(JsonElement.serializer(), item)))
                    }
                } else {
                    add(RequestContextSection(key, json.encodeToString(JsonElement.serializer(), value)))
                }
            }
        }
        // Redaction may make a numeric JSON value non-JSON; previews intentionally remain text.
        val previews = redactor.redact(allSections.joinToString("\u0000") { it.preview }).split('\u0000')
        var remaining = MAX_PREVIEW_CHARS
        var truncated = allSections.size > MAX_SECTIONS
        val sections = allSections.zip(previews).filter { it.first.label.length <= 256 }.take(MAX_SECTIONS).map { (section, preview) ->
            val length = minOf(preview.length, MAX_SECTION_CHARS, remaining)
            if (length < preview.length) truncated = true
            remaining -= length
            section.copy(
                preview = preview.take(length),
                fingerprint = MessageDigest.getInstance("SHA-256").digest(preview.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) },
                previewTruncated = length < preview.length,
            )
        }
        truncated = truncated || sections.size < allSections.size
        val snapshot = RequestContextSnapshot(
            sessionId, operationId, round, attempt, System.currentTimeMillis(),
            protocol, bodyBytes, sections, truncated, (allSections.size - sections.size).coerceAtLeast(0),
        )
        archive.append(snapshot)
    }

    private fun stripMedia(element: JsonElement, key: String = ""): JsonElement = when {
        key in setOf("data", "encrypted_content", "signature") -> JsonPrimitive("[BINARY_OR_OPAQUE_OMITTED]")
        element is JsonObject -> JsonObject(element.mapValues { (name, value) -> stripMedia(value, name) })
        element is JsonArray -> JsonArray(element.map { stripMedia(it) })
        element is JsonPrimitive && element.isString && element.content.startsWith("data:", true) ->
            JsonPrimitive("[MEDIA_OMITTED]")
        else -> element
    }

    companion object {
        private val SECTION_ROLES = setOf("system", "user", "assistant", "tool", "function_call", "function_call_output", "message", "reasoning")
        const val MAX_PREVIEW_CHARS = 64_000
        const val MAX_SECTION_CHARS = 8_000
        const val MAX_SECTIONS = 128
        const val MAX_SESSIONS = 8
        const val MAX_REQUESTS_PER_SESSION = 8
    }
}
