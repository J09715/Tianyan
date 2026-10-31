package top.tianyan.app.ui.bot

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import top.tianyan.app.harness.MentionExtractor

internal data class GroupMentionQuery(val start: Int, val end: Int, val query: String)

/** Only a standalone @ token at the cursor opens suggestions; email addresses remain plain text. */
internal fun activeGroupMention(value: TextFieldValue): GroupMentionQuery? {
    if (!value.selection.collapsed) return null
    val cursor = value.selection.end.coerceIn(0, value.text.length)
    if (cursor == 0) return null
    val start = value.text.lastIndexOf('@', cursor - 1)
    if (start < 0 || (start > 0 && !value.text[start - 1].isWhitespace())) return null
    val query = value.text.substring(start + 1, cursor)
    if (query.any { MentionExtractor.isBoundaryChar(it) }) return null
    var end = cursor
    while (end < value.text.length && !MentionExtractor.isBoundaryChar(value.text[end])) end++
    return GroupMentionQuery(start, end, query)
}

internal fun insertGroupMention(value: TextFieldValue, name: String): TextFieldValue {
    val token = activeGroupMention(value) ?: return value
    val replacement = "@$name "
    val end = token.end + if (value.text.getOrNull(token.end) == ' ') 1 else 0
    return TextFieldValue(value.text.replaceRange(token.start, end, replacement),
        selection = TextRange(token.start + replacement.length))
}
