package top.tianyan.app.feature.a2uipoc

import java.net.URI
import java.text.NumberFormat
import java.util.Locale
import android.icu.text.PluralRules

/**
 * A2UI 媒体与文案的纯逻辑。
 *
 * 链接只接受绝对的 http/https（规范要求的 scheme 白名单，拒绝 javascript/data/file 等）。
 * 文案格式化服务官方 [androidx.a2ui.model.catalog.functions.A2uiMessageFormatter]：
 * pluralize 会把分类拼成 ICU `{count, plural, ...}` 再交给宿主。
 */
internal object TianyanA2uiMediaPolicy {

    fun httpUrlOrNull(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.US) ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return uri.toASCIIString()
    }

    /**
     * 格式化 ICU plural 模板。多余参数忽略；模板点名的参数缺失时抛 [IllegalArgumentException]。
     * 设备上用 CLDR [android.icu.text.PluralRules]；单元测试环境没有该类时退回 one/other。
     */
    fun formatMessage(pattern: String, locale: Locale, arguments: Map<String, Any>): String {
        val trimmed = pattern.trim()
        val head = PLURAL_HEAD.find(trimmed) ?: return literalOrReject(trimmed)
        if (!trimmed.endsWith('}')) throw IllegalArgumentException("plural 模板不完整")
        val name = head.groupValues[1]
        val body = trimmed.substring(head.range.last + 1, trimmed.length - 1)
        val raw = arguments[name] ?: throw IllegalArgumentException("缺少文案参数：$name")
        val number = (raw as? Number)?.toDouble()
            ?: raw.toString().toDoubleOrNull()
            ?: throw IllegalArgumentException("文案参数 $name 不是数字")
        val forms = parsePluralBody(body)
        val other = forms["other"] ?: throw IllegalArgumentException("plural 缺少 other")
        val text = forms[selectPluralCategory(locale, number)] ?: other
        return text.replace("#", NumberFormat.getNumberInstance(locale).format(number))
    }

    private fun literalOrReject(pattern: String): String {
        if ('{' in pattern) throw IllegalArgumentException("无法解析文案模板")
        return pattern
    }

    private fun parsePluralBody(body: String): Map<String, String> {
        val forms = linkedMapOf<String, String>()
        var index = 0
        while (index < body.length) {
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length) break
            val nameStart = index
            while (index < body.length && body[index].isLetter()) index++
            val category = body.substring(nameStart, index)
            if (category.isEmpty()) throw IllegalArgumentException("plural 分类名为空")
            while (index < body.length && body[index].isWhitespace()) index++
            if (index >= body.length || body[index] != '{') {
                throw IllegalArgumentException("plural 分类 $category 缺少内容")
            }
            index++
            val contentStart = index
            var depth = 1
            while (index < body.length && depth > 0) {
                when (body[index]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                if (depth > 0) index++
            }
            if (depth != 0) throw IllegalArgumentException("plural 分类 $category 括号不闭合")
            forms[category] = body.substring(contentStart, index)
            index++
        }
        return forms
    }

    private fun selectPluralCategory(locale: Locale, value: Double): String = runCatching {
        PluralRules.forLocale(locale).select(value)
    }.getOrElse {
        if (value == 1.0) "one" else "other"
    }

    private val PLURAL_HEAD = Regex("""^\{(\w+),\s*plural,\s*""")
}
