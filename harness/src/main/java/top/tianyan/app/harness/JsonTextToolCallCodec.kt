package top.tianyan.app.harness

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 模型无关的文本工具调用归一化器。
 *
 * OpenAI 兼容网关并不总能把模型原生工具协议转换成标准 `tool_calls`：有的会把
 * JSON 标记或带命名空间的 XML/参数标记原样放进 content。本解析器只根据响应形态
 * 识别协议，不根据模型名、供应商名或标签前缀做硬编码。
 *
 * 支持：
 * - `[[tool_call]]{"name":...,"arguments":...}[[/tool_call]]`
 * - `<tool_call>{"name":...,"arguments":...}</tool_call>`
 * - `<任意前缀_tool_call>{...}</任意前缀_tool_call>`
 * - `<任意前缀_tool_call>read<任意前缀_argkey>path<任意前缀_arg_value>file</...>`
 *
 * 所有结果仍会经过现有工具名映射、Schema 校验与审批策略；本层只负责协议解码。
 */
object TextToolCallCodec {
    private val bracketPattern = Regex(
        """\[\[tool_call\]\](.*?)\[\[/tool_call\]\]""",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val xmlStartPattern = Regex(
        """<(?:([A-Za-z][A-Za-z0-9_.:-]*)_)?tool_call>""",
        RegexOption.IGNORE_CASE,
    )

    data class Normalization(
        val calls: List<ApiToolCallSpec>,
        val displayText: String,
        val markerCount: Int,
        val invalidMarkerCount: Int,
    ) {
        val hasMarkers: Boolean get() = markerCount > 0
        val hasUnresolvedMarkers: Boolean get() = invalidMarkerCount > 0
    }

    fun normalize(json: Json, text: String): Normalization {
        if (text.isBlank()) return Normalization(emptyList(), text, 0, 0)

        val segments = buildList {
            bracketPattern.findAll(text).forEach { match ->
                add(ProtocolSegment(match.range, prefix = null, body = match.groupValues[1], kind = SegmentKind.JSON))
            }
            addAll(xmlSegments(text))
        }.sortedBy { it.range.first }

        if (segments.isEmpty()) return Normalization(emptyList(), text, 0, 0)

        // 防止极端兼容端输出嵌套/重叠标记导致同一段被解析或剥离两次。
        val nonOverlapping = buildList {
            var lastEnd = -1
            segments.forEach { segment ->
                if (segment.range.first > lastEnd) {
                    add(segment)
                    lastEnd = segment.range.last
                }
            }
        }
        val calls = nonOverlapping.mapNotNull { segment -> parseSegment(json, segment) }
        return Normalization(
            calls = calls,
            displayText = stripSegments(text, nonOverlapping),
            markerCount = nonOverlapping.size,
            invalidMarkerCount = nonOverlapping.size - calls.size,
        )
    }

    fun extract(json: Json, text: String): List<ApiToolCallSpec> = normalize(json, text).calls

    /** 标准结构化调用具有最高优先级；仅在其缺失时启用文本协议回退，防止重复执行。 */
    fun resolveCalls(
        structuredCalls: List<ApiToolCallSpec>,
        normalization: Normalization,
    ): List<ApiToolCallSpec> = structuredCalls.ifEmpty { normalization.calls }

    fun stripMarkers(text: String): String = normalize(Json { isLenient = true }, text).displayText

    /**
     * 把结构化调用编码回文本协议形态，用于 JSON_TEXT 模式的历史回放。
     *
     * 落库的 AssistantText 是剥离了工具标记的 displayText；若回放时直接跳过 ToolCall
     * 消息，模型看不到自己上一轮调用了什么参数，结果无法与调用关联，极易重复调用。
     * 以模型自己输出的同一协议格式（可被 [normalize] 完整还原）回放成 assistant 消息。
     */
    fun encodeCall(name: String, argumentsJson: String): String =
        "[[tool_call]]{\"name\":" + JsonPrimitive(name) +
            ",\"arguments\":" + (argumentsJson.trim().ifBlank { "{}" }) + "}[[/tool_call]]"

    private fun xmlSegments(text: String): List<ProtocolSegment> = buildList {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = xmlStartPattern.find(text, searchFrom) ?: break
            val prefix = start.groupValues[1].takeIf { it.isNotBlank() }
            val contentStart = start.range.last + 1
            val endTag = if (prefix == null) "</tool_call>" else "</${prefix}_tool_call>"
            val nextStart = xmlStartPattern.find(text, contentStart)
            val limit = nextStart?.range?.first ?: text.length
            var closingAt = -1
            var cursor = contentStart
            while (cursor < limit) {
                val candidate = text.indexOf("</", cursor)
                if (candidate < 0 || candidate >= limit) break
                if (text.regionMatches(candidate, endTag, 0, endTag.length, ignoreCase = true)) {
                    closingAt = candidate
                    break
                }
                cursor = candidate + 2
            }
            val hasClosingBeforeNext = closingAt >= 0
            val contentEndExclusive = when {
                hasClosingBeforeNext -> closingAt
                nextStart != null -> nextStart.range.first
                else -> text.length
            }
            val segmentEndExclusive = if (hasClosingBeforeNext) closingAt + endTag.length else contentEndExclusive
            add(
                ProtocolSegment(
                    range = start.range.first until segmentEndExclusive,
                    prefix = prefix,
                    body = text.substring(contentStart, contentEndExclusive),
                    kind = SegmentKind.XML,
                ),
            )
            searchFrom = segmentEndExclusive.coerceAtLeast(start.range.last + 1)
        }
    }

    private fun parseSegment(json: Json, segment: ProtocolSegment): ApiToolCallSpec? {
        val payload = segment.body.trim()
        if (payload.isBlank()) return null
        parseJsonPayload(json, payload, if (segment.kind == SegmentKind.JSON) "json" else "text")?.let { return it }
        if (segment.kind != SegmentKind.XML) return null
        return parseTaggedPayload(json, payload, segment.prefix)
    }

    private fun parseJsonPayload(json: Json, payload: String, idPrefix: String): ApiToolCallSpec? = runCatching {
        val obj = json.parseToJsonElement(payload) as? JsonObject ?: return@runCatching null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (name.isBlank()) return@runCatching null
        ApiToolCallSpec(
            id = "$idPrefix-${UUID.randomUUID()}",
            name = name,
            argumentsJson = normalizeArguments(obj["arguments"]),
        )
    }.getOrNull()

    private fun parseTaggedPayload(json: Json, payload: String, prefix: String?): ApiToolCallSpec? {
        val tagPrefix = prefix?.let { "${it}_" }.orEmpty()
        val firstKey = findArgumentTag(payload, 0, tagPrefix, "argkey", "arg_key") ?: return null
        val name = payload.substring(0, firstKey.start).trim()
        if (name.isBlank()) return null

        val arguments = linkedMapOf<String, JsonElement>()
        var keyMatch: ArgumentTag? = firstKey
        while (keyMatch != null) {
            val keyStart = keyMatch.endExclusive
            val valueMatch = findArgumentTag(payload, keyStart, tagPrefix, "argvalue", "arg_value") ?: return null
            val nextKeyBeforeValue = findArgumentTag(payload, keyStart, tagPrefix, "argkey", "arg_key")
            if (nextKeyBeforeValue != null && nextKeyBeforeValue.start < valueMatch.start) return null
            val key = payload.substring(keyStart, valueMatch.start).trim()
            if (key.isBlank()) return null
            val valueStart = valueMatch.endExclusive
            val nextKey = findArgumentTag(payload, valueStart, tagPrefix, "argkey", "arg_key")
            val rawValue = payload.substring(valueStart, nextKey?.start ?: payload.length).trim()
            arguments[key] = parseArgumentValue(json, rawValue)
            keyMatch = nextKey
        }
        return ApiToolCallSpec(
            id = "text-${UUID.randomUUID()}",
            name = name,
            argumentsJson = JsonObject(arguments).toString(),
        )
    }

    private data class ArgumentTag(val start: Int, val endExclusive: Int)

    private fun findArgumentTag(text: String, from: Int, prefix: String, vararg names: String): ArgumentTag? =
        names.mapNotNull { name ->
            val tag = "<$prefix$name>"
            text.indexOf(tag, from, ignoreCase = true).takeIf { it >= 0 }?.let { ArgumentTag(it, it + tag.length) }
        }.minByOrNull { it.start }

    private fun parseArgumentValue(json: Json, rawValue: String): JsonElement {
        if (rawValue.isBlank()) return JsonPrimitive("")
        val value = rawValue.trim()
        val looksLikeJsonLiteral = value.startsWith('{') ||
            value.startsWith('[') ||
            value.startsWith('"') ||
            value == "true" ||
            value == "false" ||
            value == "null" ||
            JSON_NUMBER.matches(value)
        if (!looksLikeJsonLiteral) return JsonPrimitive(value)
        return runCatching { json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
    }

    private fun normalizeArguments(raw: JsonElement?): String = when (raw) {
        null -> "{}"
        is JsonObject -> raw.toString()
        is JsonPrimitive -> raw.contentOrNull?.ifBlank { "{}" } ?: "{}"
        else -> "{}"
    }

    private fun stripSegments(text: String, segments: List<ProtocolSegment>): String = buildString {
        var cursor = 0
        segments.forEach { segment ->
            if (cursor < segment.range.first) append(text.substring(cursor, segment.range.first))
            cursor = segment.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }.trim()

    private data class ProtocolSegment(
        val range: IntRange,
        val prefix: String?,
        val body: String,
        val kind: SegmentKind,
    )

    private enum class SegmentKind { JSON, XML }

    private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
}
