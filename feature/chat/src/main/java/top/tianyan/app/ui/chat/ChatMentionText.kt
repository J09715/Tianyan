package top.tianyan.app.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import top.tianyan.app.harness.MentionExtractor

/** 构建精准匹配技能与插件实体的正则表达式（优先长词带空格全称匹配） */

internal fun buildMentionRegex(knownNames: List<String>): Regex {
    val sorted = knownNames.filter { it.isNotBlank() }.sortedByDescending { it.length }
    val escaped = sorted.map { Regex.escape(it) }
    val generic = """[^${MentionExtractor.MENTION_HARD_BOUNDARY}]+"""
    // 与 MentionExtractor 同一套：已知名单最长优先 + 邮箱/词边界保护 + 硬终止符。
    val body = if (escaped.isNotEmpty()) {
        """${escaped.joinToString("|")}|$generic"""
    } else {
        generic
    }
    return Regex("""(?<![\w.+-])@($body)""")
}

/** 为文本中的 @能力 实体添加自适应半透明高亮样式（支持带空格全称） */
internal fun formatMentionText(
    text: String,
    knownNames: List<String>,
    mentionColor: Color,
    mentionBg: Color,
): AnnotatedString {
    if (!text.contains("@")) return AnnotatedString(text)
    val builder = AnnotatedString.Builder(text)
    val regex = buildMentionRegex(knownNames)
    for (match in regex.findAll(text)) {
        val range = match.range
        builder.addStyle(
            SpanStyle(
                color = mentionColor,
                fontWeight = FontWeight.SemiBold,
                background = mentionBg,
            ),
            range.first,
            range.last + 1,
        )
    }
    return builder.toAnnotatedString()
}

/**
 * 🌟 输入框内 @能力 实体富文本语法高亮变换器
 * 将 `@xxx` 自动渲染为优雅的主题色半透明胶囊样式（对齐 Telegram / 微信 / Discord 设计，支持带空格全称）
 */
internal class MentionVisualTransformation(
    private val knownNames: List<String>,
    private val mentionColor: Color,
    private val mentionBg: Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val transformed = formatMentionText(text.text, knownNames, mentionColor, mentionBg)
        return TransformedText(transformed, OffsetMapping.Identity)
    }
}

