package top.tianyan.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 列表符号列的固定宽度：保证多行条目换行后文字仍与首行左对齐（悬挂缩进）。 */
private val LIST_MARKER_WIDTH = 22.dp
/** 每级嵌套的缩进量。 */
private val LIST_INDENT_STEP = 14.dp

/**
 * 表格：表头浅底 + 分隔线 + 隔行底色，单元格统一内边距。
 * 列宽按字符宽度估算后夹逼，避免超长单元格把整行撑爆。
 */
@Composable
internal fun TableBlock(table: MdTable) {
    val colCount = (listOf(table.headers) + table.rows).maxOfOrNull { it.size } ?: 0
    if (colCount == 0) return
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val headerBg = if (isDark) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val stripeBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    val border = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
    val colWidths = (0 until colCount).map { c ->
        val widest = (listOf(table.headers) + table.rows)
            .mapNotNull { it.getOrNull(c) }
            .maxOfOrNull { displayWidthOf(it) } ?: 1
        (widest * 7.4f).dp.coerceIn(56.dp, 260.dp)
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(0.7.dp, border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(headerBg)) {
                (0 until colCount).forEach { c ->
                    TableCell(
                        text = table.headers.getOrNull(c) ?: "",
                        width = colWidths[c],
                        bold = true,
                    )
                }
            }
            HorizontalDivider(color = border)
            table.rows.forEachIndexed { index, row ->
                Row(
                    modifier = if (index % 2 == 1) Modifier.background(stripeBg) else Modifier,
                ) {
                    (0 until colCount).forEach { c ->
                        TableCell(
                            text = row.getOrNull(c) ?: "",
                            width = colWidths[c],
                            bold = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean) {
    InlineText(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        bold = bold,
        modifier = Modifier
            .width(width)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

/**
 * 列表：符号列固定宽度 + 正文列占满剩余宽度，正文换行后与首行对齐。
 * 嵌套层级通过起始内边距体现，有序列表按同级序号连续编号。
 */
@Composable
internal fun ListBlock(block: MdList) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        block.items.forEachIndexed { index, item ->
            val marker = if (block.ordered) "${index + 1}." else "\u2022"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = LIST_INDENT_STEP * item.depth),
            ) {
                Text(
                    text = marker,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(LIST_MARKER_WIDTH)
                        .padding(end = 8.dp),
                )
                InlineText(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 引用：左侧强调色竖条 + 淡底，正文用次要色，和正文段落明确区分。 */
@Composable
internal fun QuoteBlock(block: MdQuote) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(3.dp)
                .height(quoteBarHeight(block.lines.size))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), RoundedCornerShape(2.dp)),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp),
                )
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            block.lines.forEach { line ->
                InlineText(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 竖条高度按行数粗估，避免被 Row 拉伸到与内容等高时出现错位。 */
private fun quoteBarHeight(lines: Int) = (22 * lines.coerceAtLeast(1)).dp

/**
 * 估算字符串的显示宽度（以半角字符为单位）。
 *
 * Markdown 强调标记（`**`、`*`、`` ` ``）不占实际显示宽度，需先剥离；
 * CJK / 全角标点按 2 个单位计，其余按 1。用于表格列宽估算，无需精确字形度量。
 */
internal fun displayWidthOf(raw: String): Int {
    val text = raw
        .replace("**", "")
        .replace("`", "")
        .replace("*", "")
    var width = 0
    text.forEach { ch ->
        val code = ch.code
        width += when {
            code in 0x1100..0x115F -> 2                 // 韩文字母
            code in 0x2E80..0xA4CF -> 2                 // CJK 部首/汉字/日文假名
            code in 0xAC00..0xD7A3 -> 2                 // 韩文音节
            code in 0xF900..0xFAFF -> 2                 // CJK 兼容表意
            code in 0xFE30..0xFE6F -> 2                 // CJK 兼容形式
            code in 0xFF00..0xFF60 -> 2                 // 全角 ASCII
            code in 0xFFE0..0xFFE6 -> 2                 // 全角符号
            else -> 1
        }
    }
    return width.coerceAtLeast(1)
}
