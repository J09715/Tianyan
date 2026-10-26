package top.tianyan.app.core.model.workflow

import kotlinx.serialization.json.*

/** Deliberately bounded JSON Schema subset. Unsupported constraints fail before execution. */
object WorkflowResultSchema {
    const val CONFIG_KEY = "resultSchema"
    private const val MAX_SCHEMA_LENGTH = 32_768
    private const val MAX_RESULT_LENGTH = 65_536
    private const val MAX_DEPTH = 16
    private val types = setOf("object", "array", "string", "boolean", "integer", "number", "null")

    fun parse(raw: String): JsonObject {
        require(raw.length <= MAX_SCHEMA_LENGTH) { "结果 schema 超过 32 KiB" }
        val schema = parseBounded(raw, MAX_DEPTH * 2 + 4) as? JsonObject ?: error("结果 schema 必须为 JSON 对象")
        checkSchema(schema, 0)
        require(schema["type"] == JsonPrimitive("object")) { "结果 schema 顶层必须为 object" }
        return schema
    }

    fun validateResult(raw: String, schema: JsonObject): JsonObject {
        require(raw.length <= MAX_RESULT_LENGTH) { "结构化结果超过 64 KiB" }
        val value = parseBounded(raw.trim(), MAX_DEPTH + 1) as? JsonObject ?: error("结果必须为 JSON 对象，不能包含 Markdown 或说明文字")
        checkValue(value, schema, "$", 0)
        return value
    }

    private fun checkSchema(schema: JsonObject, depth: Int) {
        require(depth <= MAX_DEPTH) { "结果 schema 嵌套过深" }
        val type = (schema["type"] as? JsonPrimitive)?.content
        require(type in types) { "结果 schema 必须声明受支持的 type" }
        val allowed = setOf("type", "description", "enum") + when (type) {
            "object" -> setOf("properties", "required", "additionalProperties")
            "array" -> setOf("items")
            else -> emptySet()
        }
        require(schema.keys.all { it in allowed }) { "结果 schema 含不支持的约束：${schema.keys - allowed}" }
        schema["description"]?.let { require(it is JsonPrimitive && it.isString) { "description 必须为字符串" } }
        schema["enum"]?.let { require(it is JsonArray && it.isNotEmpty()) { "enum 必须为非空数组" } }
        if (type == "object") {
            val properties = schema["properties"]?.let {
                it as? JsonObject ?: error("properties 必须为对象")
            } ?: JsonObject(emptyMap())
            properties.forEach { (name, child) ->
                require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_\\-]*"))) { "结果字段名不支持：$name" }
                checkSchema(child as? JsonObject ?: error("字段 schema 必须为对象"), depth + 1)
            }
            schema["required"]?.let { required ->
                require(required is JsonArray && required.all {
                    it is JsonPrimitive && it.isString && it.content in properties
                }) { "required 必须列出 properties 中的字段名" }
            }
            schema["additionalProperties"]?.let {
                require(it == JsonPrimitive(true) || it == JsonPrimitive(false)) { "additionalProperties 只支持布尔值" }
            }
        }
        if (type == "array") checkSchema(schema["items"] as? JsonObject ?: error("array 必须声明 items"), depth + 1)
    }

    private fun checkValue(value: JsonElement, schema: JsonObject, path: String, depth: Int) {
        require(depth <= MAX_DEPTH) { "$path 结果嵌套过深" }
        val primitive = value as? JsonPrimitive
        val number = primitive?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
        val type = schema.getValue("type").jsonPrimitive.content
        val matches = when (type) {
            "object" -> value is JsonObject
            "array" -> value is JsonArray
            "string" -> primitive?.isString == true
            "boolean" -> primitive?.isString == false && primitive.booleanOrNull != null
            "number" -> number != null
            "integer" -> number != null && primitive?.content?.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { it <= 0 } == true
            "null" -> value == JsonNull
            else -> false
        }
        require(matches) { "$path 必须为 $type" }
        schema["enum"]?.let { require(value in it.jsonArray) { "$path 不在 enum 范围内" } }
        if (value is JsonObject) {
            val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
            schema["required"]?.jsonArray?.forEach {
                require(it.jsonPrimitive.content in value) { "$path 缺少字段 ${it.jsonPrimitive.content}" }
            }
            if (schema["additionalProperties"] == JsonPrimitive(false)) {
                require(value.keys.all { it in properties }) { "$path 含未声明字段：${value.keys - properties.keys}" }
            }
            value.forEach { (key, child) ->
                properties[key]?.let { checkValue(child, it.jsonObject, "$path.$key", depth + 1) }
            }
        }
        if (value is JsonArray) value.forEachIndexed { index, child ->
            checkValue(child, schema.getValue("items").jsonObject, "$path[$index]", depth + 1)
        }
    }

    /** Bound nesting before invoking the parser, including undeclared fields and enum values. */
    private fun parseBounded(raw: String, maxDepth: Int): JsonElement {
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= maxDepth) { "JSON 嵌套过深" } }
                '}', ']' -> depth--
            }
        }
        return Json.parseToJsonElement(raw)
    }
}

/** null means this is not a result reference; an empty string means a missing/ambiguous field. */
fun WorkflowRuntimeContext.resultReference(key: String, upstream: NodeExecutionOutput? = null): String? {
    val marker = ".result"
    val nodeId = (nodeOutputs.keys + "previous").sortedByDescending { it.length }.firstOrNull {
        key == "$it$marker" || key.startsWith("$it$marker.")
    } ?: return if (key.contains("$marker.") || key.endsWith(marker)) "" else null
    val output = if (nodeId == "previous") {
        upstream ?: upstreamNodeIds?.distinct()?.singleOrNull()?.let(nodeOutputs::get)
    } else nodeOutputs[nodeId]
    var value: JsonElement = output?.structuredResult ?: return ""
    val suffix = key.substring(nodeId.length + marker.length).removePrefix(".")
    if (suffix.isNotEmpty()) for (part in suffix.split('.')) {
        val current = value
        value = when (current) {
            is JsonObject -> current[part]
            is JsonArray -> part.toIntOrNull()?.let { current.getOrNull(it) }
            else -> null
        } ?: return ""
    }
    return when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
