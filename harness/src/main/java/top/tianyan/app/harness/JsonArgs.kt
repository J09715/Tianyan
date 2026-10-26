package top.tianyan.app.harness

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具参数读取与校验的唯一入口。
 *
 * 拆分各 ToolBackend 时，`requireString` / `optionalLong` / 字符串取值曾在 7 个文件里各存一份，
 * 错误文案与长度上限只能靠人工对齐。这里收敛为单点，保证行为一致、改一处即全仓生效。
 */
internal object JsonArgs {

    /** 单个字符串参数的长度上限（1 MiB），防止畸形入参撑爆上下文。 */
    const val MAX_ARG_LENGTH = 1024 * 1024

    /** 读取必填字符串参数：缺失/空白/超长均抛出带参数名的 [IllegalArgumentException]。 */
    fun requireString(args: JsonObject, key: String): String {
        val value = args[key]?.jsonPrimitive?.content
        require(!value.isNullOrBlank()) { "缺少参数：$key" }
        require(value.length <= MAX_ARG_LENGTH) { "参数 $key 过长（${value.length} 字符，上限 $MAX_ARG_LENGTH）" }
        return value
    }

    /** 读取可选字符串参数：缺失或空白返回 null。 */
    fun optionalString(args: JsonObject, key: String): String? =
        args[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    /** 读取可选整数参数并做范围校验；缺省返回 [default]。 */
    fun optionalLong(args: JsonObject, key: String, default: Long, min: Long, max: Long): Long {
        val raw = args[key]?.jsonPrimitive?.content?.trim() ?: return default
        val value = raw.toLongOrNull() ?: throw IllegalArgumentException("参数 $key 必须是整数")
        require(value in min..max) { "参数 $key 必须在 $min-$max 之间" }
        return value
    }

    /** 读取必填整数参数（不做范围约束）。 */
    fun requireInt(args: JsonObject, key: String): Int {
        val raw = args[key]?.jsonPrimitive?.content?.trim()
        require(!raw.isNullOrBlank()) { "缺少参数：$key" }
        return raw.toIntOrNull() ?: throw IllegalArgumentException("参数 $key 必须是整数")
    }
}