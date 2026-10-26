package top.tianyan.app.plugin.packages

import kotlinx.serialization.json.*

/** Deliberately small, strict Schema dialect. Unknown constraints never become silent allowances. */
object PackageSchemas {
    fun requireSupported(schema: JsonObject) {
        require(schema.toString().length <= 32_768) { "插件 Schema 过大" }
        fun inspect(node: JsonObject, depth: Int) {
            require(depth <= 12) { "插件 Schema 嵌套过深" }
            val type = node["type"]?.jsonPrimitive?.content
            require(type in setOf("object", "array", "string", "integer", "number", "boolean")) { "插件 Schema 类型不支持" }
            val allowed = setOf("type", "description", "enum") + when (type) {
                "object" -> setOf("properties", "required", "additionalProperties")
                "array" -> setOf("items", "minItems", "maxItems")
                "string" -> setOf("minLength", "maxLength")
                "integer", "number" -> setOf("minimum", "maximum")
                else -> emptySet()
            }
            require(node.keys.all { it in allowed }) { "插件 Schema 含不支持的约束" }
            node["description"]?.let { require(it is JsonPrimitive && it.isString && it.content.length <= 2_048) }
            node["enum"]?.let { values -> require(values is JsonArray && values.size in 1..64 && values.distinct().size == values.size && values.all { it is JsonPrimitive && it != JsonNull }) }
            if (type == "object") {
                require(node["properties"] == null || node["properties"] is JsonObject)
                val properties = node["properties"] as? JsonObject ?: JsonObject(emptyMap())
                require(properties.size <= 64 && properties.keys.all { it.isNotEmpty() && it.length <= 128 })
                properties.values.forEach { inspect(requireNotNull(it as? JsonObject), depth + 1) }
                node["required"]?.let { names -> require(names is JsonArray && names.distinct().size == names.size && names.all { it is JsonPrimitive && it.isString && it.content in properties }) }
                node["additionalProperties"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) }
            }
            if (type == "array") inspect(requireNotNull(node["items"] as? JsonObject) { "数组 Schema 缺少 items" }, depth + 1)
            listOf("minItems", "maxItems", "minLength", "maxLength").forEach { key ->
                node[key]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { n -> n in 0..65_536 } == true) }
            }
            listOf("minimum", "maximum").forEach { key -> node[key]?.let { require(it is JsonPrimitive && !it.isString && it.doubleOrNull?.isFinite() == true) } }
        }
        inspect(schema, 0)
        require(schema["type"]?.jsonPrimitive?.content == "object") { "插件参数根必须为 object" }
    }

    fun valid(schema: JsonObject, arguments: JsonObject): Boolean {
        if (arguments.toString().length > 32_768) return false
        var entries = 0
        fun check(definition: JsonObject, value: JsonElement, depth: Int): Boolean {
            if (depth > 16 || ++entries > 4_096) return false
            val type = definition["type"]?.jsonPrimitive?.content
            definition["enum"]?.let { if (value !in it.jsonArray) return false }
            fun within(number: Double, minimum: String, maximum: String): Boolean =
                definition[minimum]?.jsonPrimitive?.doubleOrNull?.let { number >= it } != false &&
                    definition[maximum]?.jsonPrimitive?.doubleOrNull?.let { number <= it } != false
            return when (type) {
                "object" -> {
                    if (value !is JsonObject) return false
                    val properties = definition["properties"] as? JsonObject ?: JsonObject(emptyMap())
                    if (definition["required"]?.jsonArray?.any { it.jsonPrimitive.content !in value } == true) return false
                    val extra = definition["additionalProperties"]?.jsonPrimitive?.booleanOrNull == true
                    value.all { (key, item) ->
                        val child = properties[key] as? JsonObject
                        if (child != null) check(child, item, depth + 1)
                        else extra && checkAny(item, depth + 1)
                    }
                }
                "array" -> value is JsonArray && within(value.size.toDouble(), "minItems", "maxItems") && value.all { check(definition.getValue("items").jsonObject, it, depth + 1) }
                "string" -> value is JsonPrimitive && value.isString && within(value.content.length.toDouble(), "minLength", "maxLength")
                "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
                "integer", "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.let {
                    it.isFinite() && (type != "integer" || value.longOrNull != null) && within(it, "minimum", "maximum")
                } == true
                else -> false
            }
        }
        return check(schema, arguments, 0)
    }
    private fun checkAny(value: JsonElement, depth: Int): Boolean = depth <= 16 && when (value) {
        is JsonObject -> value.size <= 64 && value.values.all { checkAny(it, depth + 1) }
        is JsonArray -> value.size <= 1_024 && value.all { checkAny(it, depth + 1) }
        else -> true
    }
}
