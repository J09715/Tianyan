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
 * - `<｜DSML｜tool_calls><｜DSML｜invoke name="...">…`（网关/中转的标记形态）
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

    /**
     * DSML 形态：部分 DeepSeek 系网关/中转把工具调用以标记文本塞进 content，
     * 而不是填结构化 tool_calls 字段。若不识别，本轮会被判成「零工具调用」
     * 而静默走完成分支——用户看到的是「无缘无故停了」。
     *
     * 线上抓样（`｜` 为 U+FF5C 全角竖线；部分网关用半角 `|`，两者都容忍）：
     *
     * ```
     * <｜DSML｜tool_calls>
     *   <｜DSML｜invoke name="edit_file">
     *     <｜DSML｜parameter name="file_path" string="true">lab.py</｜DSML｜parameter>
     *   </｜DSML｜invoke>
     * </｜DSML｜tool_calls>
     * ```
     *
     * 命中条件刻意收紧为「tool_calls 开标记 + 至少一个完整 invoke」——
     * 避免把模型在正文里复述该格式（讨论它、贴日志）误判成真实调用。
     * 这一层比既有 XML 分支严格，是刻意的不对称。
     */
    private val dsmlOpenPattern = Regex("""<[｜|]\s*DSML\s*[｜|]\s*tool_calls\s*>""", RegexOption.IGNORE_CASE)
    private val dsmlClosePattern = Regex("""</[｜|]\s*DSML\s*[｜|]\s*tool_calls\s*>""", RegexOption.IGNORE_CASE)
    private val dsmlInvokePattern = Regex(
        """<[｜|]\s*DSML\s*[｜|]\s*invoke\s+name\s*=\s*"([^"]*)"\s*>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*invoke\s*>""",
        RegexOption.IGNORE_CASE,
    )
    private val dsmlParameterPattern = Regex(
        """<[｜|]\s*DSML\s*[｜|]\s*parameter\s+name\s*=\s*"([^"]*)"([^>]*)>([\s\S]*?)</[｜|]\s*DSML\s*[｜|]\s*parameter\s*>""",
        RegexOption.IGNORE_CASE,
    )

    /** `string="true"` 显式声明值按字符串处理，不做 JSON 字面量推断。 */
    private val dsmlStringAttrPattern = Regex("""string\s*=\s*"true"""", RegexOption.IGNORE_CASE)

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
            addAll(dsmlSegments(text))
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
        // DSML 的一个 tool_calls 块可含多个 invoke，因此按段展开为 0..N 个调用。
        val parsed = nonOverlapping.map { segment -> parseSegment(json, segment) }
        val calls = parsed.flatten()
        return Normalization(
            calls = calls,
            displayText = stripSegments(text, nonOverlapping),
            markerCount = nonOverlapping.size,
            // 一个标记段未能解出任何调用才算无效；DSML 多调用不改变这个口径，
            // 否则 invalidMarkerCount 会变成负数。
            invalidMarkerCount = parsed.count { it.isEmpty() },
        )
    }

    fun extract(json: Json, text: String): List<ApiToolCallSpec> = normalize(json, text).calls

    /** 标准结构化调用具有最高优先级；仅在其缺失时启用文本协议回退，防止重复执行。 */
    fun resolveCalls(
        structuredCalls: List<ApiToolCallSpec>,
        normalization: Normalization,
    ): List<ApiToolCallSpec> = structuredCalls.ifEmpty { normalization.calls }

    fun stripMarkers(text: String): String = normalize(Json { isLenient = true }, text).displayText

    private fun xmlSegments(text: String): List<ProtocolSegment> = buildList {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val start = xmlStartPattern.find(text, searchFrom) ?: break
            val prefix = start.groupValues[1].takeIf { it.isNotBlank() }
            val contentStart = start.range.last + 1
            val endTag = if (prefix == null) "</tool_call>" else "</${prefix}_tool_call>"
            val closingAt = text.indexOf(endTag, contentStart, ignoreCase = true)
            val nextStart = xmlStartPattern.find(text, contentStart)
            val hasClosingBeforeNext = closingAt >= 0 && (nextStart == null || closingAt < nextStart.range.first)
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

    /**
     * 收集 DSML 标记段。只认「开标记 → 闭标记」这种完整形态：
     * 正文里出现开标记但没有闭标记（例如模型正在讨论该格式）时整段丢弃，
     * 避免把复述误判成调用。
     */
    private fun dsmlSegments(text: String): List<ProtocolSegment> = buildList {
        var searchFrom = 0
        while (searchFrom < text.length) {
            val open = dsmlOpenPattern.find(text, searchFrom) ?: break
            val bodyStart = open.range.last + 1
            val close = dsmlClosePattern.find(text, bodyStart)
            // 没有闭合标记：不再往后找，直接结束——后面的内容不属于任何完整块。
            if (close == null) break
            add(
                ProtocolSegment(
                    range = open.range.first until close.range.last + 1,
                    prefix = null,
                    body = text.substring(bodyStart, close.range.first),
                    kind = SegmentKind.DSML,
                ),
            )
            searchFrom = close.range.last + 1
        }
    }

    /** 返回该标记段解出的全部调用；DSML 一段可含多个 invoke，其余形态至多一个。 */
    private fun parseSegment(json: Json, segment: ProtocolSegment): List<ApiToolCallSpec> {
        if (segment.kind == SegmentKind.DSML) return parseDsmlPayload(json, segment.body)
        val payload = segment.body.trim()
        if (payload.isBlank()) return emptyList()
        parseJsonPayload(json, payload, if (segment.kind == SegmentKind.JSON) "json" else "text")
            ?.let { return listOf(it) }
        if (segment.kind != SegmentKind.XML) return emptyList()
        return listOfNotNull(parseTaggedPayload(json, payload, segment.prefix))
    }

    /**
     * 解析 DSML 段体（tool_calls 开闭标记之间的内容）。
     *
     * 只认完整 `<…invoke>…</…invoke>`，未闭合的 invoke 一律丢弃——
     * 宁可少认一个调用，也不要把正文里对格式的复述当成真实调用。
     */
    private fun parseDsmlPayload(json: Json, body: String): List<ApiToolCallSpec> {
        val calls = mutableListOf<ApiToolCallSpec>()
        dsmlInvokePattern.findAll(body).forEach { invoke ->
            val name = invoke.groupValues[1].trim()
            if (name.isBlank()) return@forEach
            val arguments = linkedMapOf<String, JsonElement>()
            dsmlParameterPattern.findAll(invoke.groupValues[2]).forEach { param ->
                val key = param.groupValues[1].trim()
                if (key.isBlank()) return@forEach
                val attrs = param.groupValues[2]
                val rawValue = param.groupValues[3]
                arguments[key] = if (dsmlStringAttrPattern.containsMatchIn(attrs)) {
                    // 网关显式标了 string="true"：原样当字符串，不做 JSON 字面量推断，
                    // 否则形如 "1.0" 的版本号会被还原成数字而丢失书写形式。
                    JsonPrimitive(decodeDsmlEntities(rawValue))
                } else {
                    parseArgumentValue(json, decodeDsmlEntities(rawValue))
                }
            }
            calls += ApiToolCallSpec(
                id = "dsml-${UUID.randomUUID()}",
                name = name,
                argumentsJson = JsonObject(arguments).toString(),
            )
        }
        return calls
    }

    /** 值体内可能被网关做最小 XML 转义；只还原最常见的三种，不做完整实体解码。 */
    private fun decodeDsmlEntities(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

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
        val escapedPrefix = prefix?.let { Regex.escape("${it}_") }.orEmpty()
        val keyPattern = Regex("""<$escapedPrefix(?:argkey|arg_key)>""", RegexOption.IGNORE_CASE)
        val valuePattern = Regex("""<$escapedPrefix(?:argvalue|arg_value)>""", RegexOption.IGNORE_CASE)
        val firstKey = keyPattern.find(payload) ?: return null
        val name = payload.substring(0, firstKey.range.first).trim()
        if (name.isBlank()) return null

        val arguments = linkedMapOf<String, JsonElement>()
        var keyMatch: MatchResult? = firstKey
        while (keyMatch != null) {
            val keyStart = keyMatch.range.last + 1
            val valueMatch = valuePattern.find(payload, keyStart) ?: return null
            val nextKeyBeforeValue = keyPattern.find(payload, keyStart)
            if (nextKeyBeforeValue != null && nextKeyBeforeValue.range.first < valueMatch.range.first) return null
            val key = payload.substring(keyStart, valueMatch.range.first).trim()
            if (key.isBlank()) return null
            val valueStart = valueMatch.range.last + 1
            val nextKey = keyPattern.find(payload, valueStart)
            val rawValue = payload.substring(valueStart, nextKey?.range?.first ?: payload.length).trim()
            arguments[key] = parseArgumentValue(json, rawValue)
            keyMatch = nextKey
        }
        return ApiToolCallSpec(
            id = "text-${UUID.randomUUID()}",
            name = name,
            argumentsJson = JsonObject(arguments).toString(),
        )
    }

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

    private enum class SegmentKind { JSON, XML, DSML }

    private val JSON_NUMBER = Regex("""-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?""")
}
