package top.tianyan.app.harness.directory

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * codemode 沙箱的纯 Kotlin JSON 模块：`stringify` 与 `parse` 都不经过 Rhino 的 `NativeJSON`。
 *
 * 两条通路各自踩过 release 专属地雷：
 * - `stringify`：`NativeJSON.stringify` 会触发 JavaToJSONConverters 的 clinit，引用桌面端
 *   java.beans，Android 上必炸。
 * - `parse`：v1.4.0 release 实测每次调用都抛
 *   `NullPointerException: Attempt to invoke interface method ... on a null object reference`，
 *   连原始值 `JSON.parse('123')` 也崩；异常在反射层抛出，脚本内 `try/catch` 无法拦截，
 *   整个脚本连同已完成的内层调用一并失败。而 `capability.call` 的 output 正是 JSON 文本，
 *   parse 是脚本侧数据加工的第一步，属于硬需求。
 *
 * 独立成文件同时也避开 400 行的文件尺寸棘轮（运行器原本已 374 行）。
 */
internal object CapabilityJsonText {

    /** 递归深度上限，防止自引用/病态深嵌套把栈打爆，序列化与解析共用。 */
    private const val MAX_JSON_DEPTH = 32

    /**
     * 纯 Kotlin 的 JS 值序列化，替代 Rhino 的 [NativeJSON.stringify]。
     *
     * 崩溃机理（javap 对 rhino-1.9.1 实测，全库仅此一条通路）：
     * `NativeJSON.stringify` → `NativeJSON.str()` → 遇到非字符串原始值时进入
     * `NativeJSON.javaToJSON()` → `Context.getJavaToJSONConverter()`，
     * 而该方法体里只有一条指令：`getstatic JavaToJSONConverters.STRING`。
     * 读取静态字段会强制初始化 `JavaToJSONConverters`，其 <clinit> 依赖
     * java.beans.Introspector / PropertyDescriptor / BeanInfo / BeanDescriptor /
     * IntrospectionException —— 这 5 个类在 Android 运行时全部不存在，
     * 于是抛 NoClassDefFoundError 并被包装为 ExceptionInInitializerError。
     * 原有的 `setJavaToJSONConverter { ... }` 规避无效：javap 显示 setter 对该类的
     * 引用次数为 0，getter 直读静态常量，调用方无从阻止类初始化。
     *
     * `NativeJSON.parse` 同样不可依赖：v1.4.0 release 实测每次调用都抛
     * NullPointerException（"Attempt to invoke interface method … on a null object reference"），
     * 连原始值 JSON.parse('123') 也崩；异常在反射层抛出，脚本内 try/catch 无法拦截，
     * 整个脚本连同已完成的内层调用一并失败。故 parse 也改为纯 Kotlin 实现（见 [parseJsonText]），
     * 本类不再引用 Rhino 的 NativeJSON。
     * 也正因如此，用字符串完成值的单测会命中 CharSequence 快路径而始终为绿，
     * 真机上任何数字完成值或对象参数却必崩——这是本地单测的固有盲区。
     */
    internal fun stringify(value: Any?, depth: Int = 0): String {
        if (depth >= MAX_JSON_DEPTH) return "\"<max-depth>\""
        return when {
            // 数组空洞的 getProperty 得到 NOT_FOUND，按 JSON.stringify 编码为 null。
            value == null || value === Scriptable.NOT_FOUND || value is Undefined -> "null"
            value is CharSequence -> jsonStringLiteral(value.toString())
            value is Boolean -> if (value) "true" else "false"
            value is Number -> jsonNumberLiteral(value.toDouble())
            value is NativeArray -> (0 until value.length.toInt()).joinToString(",", "[", "]") { index ->
                stringify(ScriptableObject.getProperty(value, index), depth + 1)
            }
            value is Function -> "null"
            value is Scriptable -> value.ids
                .mapNotNull { id ->
                    val name = id?.toString() ?: return@mapNotNull null
                    val member = value.get(name, value)
                    if (member === Scriptable.NOT_FOUND || member is Undefined || member is Function) {
                        null
                    } else {
                        jsonStringLiteral(name) + ":" + stringify(member, depth + 1)
                    }
                }
                .joinToString(",", "{", "}")
            else -> jsonStringLiteral(value.toString())
        }
    }

    private fun jsonStringLiteral(raw: String): String = buildString(raw.length + 16) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (ch < ' ') append("\\u" + ch.code.toString(16).padStart(4, '0')) else append(ch)
            }
        }
        append('"')
    }

    private fun jsonNumberLiteral(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "null"
        value == Math.floor(value) && Math.abs(value) < 1e15 -> value.toLong().toString()
        else -> value.toString()
    }

    /** 把纯 Kotlin 的 `JSON.stringify` / `JSON.parse` 覆盖进沙箱全局。 */
    fun install(
        cx: Context,
        scope: Scriptable,
        define: (Scriptable, String, (Context, Scriptable, Array<Any?>) -> Any?) -> Unit,
    ) {
        val json = cx.newObject(scope)
        define(json, "stringify") { _, _, args -> stringify(args.getOrNull(0)) }
        define(json, "parse") { cx1, scope1, args ->
            val text = args.getOrNull(0)?.toString()
                ?: throw Context.reportRuntimeError("JSON.parse 需要字符串参数")
            parse(cx1, scope1, text)
        }
        ScriptableObject.putProperty(scope, "JSON", json)
    }

    /**
     * 纯 Kotlin 的 JSON 文本解析，替代 Rhino 的 `NativeJSON.parse`。
     *
     * 解析失败抛 EvaluatorException（`Context.reportRuntimeError`），脚本内 `try/catch` 可捕获，
     * 与原生 `JSON.parse` 抛 SyntaxError 的语义对齐；不支持 reviver 参数（沙箱内无使用场景）。
     */
    fun parse(cx: Context, scope: Scriptable, text: String): Any? =
        Reader(text, cx, scope).readDocument()

    /**
     * 递归下降 JSON 解析器：只产出 Rhino 原生对象/数组/原始值，不经过任何 Java 互操作反射，
     * 因此不受 R8 改名与 Android 运行时缺类影响。
     */
    private class Reader(
        private val text: String,
        private val cx: Context,
        private val scope: Scriptable,
    ) {
        private var pos = 0

        fun readDocument(): Any? {
            val value = readValue(0)
            skipWhitespace()
            if (pos != text.length) fail("文本结尾存在多余字符")
            return value
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_JSON_DEPTH) fail("嵌套层数超过 $MAX_JSON_DEPTH")
            skipWhitespace()
            if (pos >= text.length) fail("文本意外结束")
            return when (val ch = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readKeyword("true", true)
                'f' -> readKeyword("false", false)
                'n' -> readKeyword("null", null)
                else -> if (ch == '-' || ch in '0'..'9') readNumber() else fail("无法识别的字符 '$ch'")
            }
        }

        private fun readObject(depth: Int): Any? {
            pos++ // '{'
            val result = cx.newObject(scope)
            skipWhitespace()
            if (peek() == '}') {
                pos++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("对象的键后缺少 ':'")
                pos++
                ScriptableObject.putProperty(result, key, readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return result
                    }
                    else -> fail("对象成员之间缺少 ',' 或 '}'")
                }
            }
        }

        private fun readArray(depth: Int): Any? {
            pos++ // '['
            val items = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                pos++
                return cx.newArray(scope, items.toTypedArray())
            }
            while (true) {
                items.add(readValue(depth + 1))
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return cx.newArray(scope, items.toTypedArray())
                    }
                    else -> fail("数组元素之间缺少 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // 起始引号
            val builder = StringBuilder()
            while (pos < text.length) {
                when (val ch = text[pos++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("转义序列不完整")
                        when (val escape = text[pos++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("\\u 转义不完整")
                                val hex = text.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("非法的 \\u 转义 '$hex'")
                                builder.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("非法的转义 '\\$escape'")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
            fail("字符串未闭合")
        }

        private fun readNumber(): Double {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (peek() == '.') {
                pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (pos < text.length && text[pos] in '0'..'9') pos++
            }
            val raw = text.substring(start, pos)
            return raw.toDoubleOrNull() ?: fail("非法的数字 '$raw'")
        }

        private fun readKeyword(keyword: String, value: Any?): Any? {
            if (!text.startsWith(keyword, pos)) fail("非法字面量")
            pos += keyword.length
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? = text.getOrNull(pos)

        private fun fail(message: String): Nothing =
            throw Context.reportRuntimeError("JSON 解析失败（位置 $pos）：$message")
    }
}
