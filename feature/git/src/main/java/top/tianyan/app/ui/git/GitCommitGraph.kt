package top.tianyan.app.ui.git

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.ui.components.RuntimeCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 泳道配色（MGit 风格多色提交树） */
private val LANE_COLORS = listOf(
    Color(0xFF3F8FFF),
    Color(0xFF7C4DFF),
    Color(0xFF2E9E5B),
    Color(0xFFE65100),
    Color(0xFFD81B60),
    Color(0xFF00897B),
    Color(0xFF8D6E63),
    Color(0xFF5C6BC0),
    Color(0xFFF06292),
    Color(0xFF9575CD),
)

private const val MAX_LANES = 10
private val LANE_WIDTH = 14.dp
private val ROW_HEIGHT = 62.dp

private fun laneColor(lane: Int): Color = LANE_COLORS[lane.coerceIn(0, LANE_COLORS.lastIndex)]

/**
 * 单行提交：左侧泳道图（穿线 + 合并/分叉斜线 + 节点），右侧哈希/refs/主题/作者/时间。
 */
@Composable
internal fun CommitGraphRow(row: GitCommitRow) {
    val clamped: (Int) -> Int = { it.coerceIn(0, MAX_LANES - 1) }
    val graphWidth = LANE_WIDTH * minOf(row.laneCount.coerceAtLeast(row.lane + 1), MAX_LANES)

    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Canvas(
            modifier = Modifier
                .width(graphWidth)
                .fillMaxHeight(),
        ) {
            val laneW = LANE_WIDTH.toPx()
            val height = size.height
            val centerY = height / 2
            val stroke = 2.dp.toPx()
            fun x(lane: Int): Float = laneW * clamped(lane) + laneW / 2

            val nodeColor = laneColor(row.lane)

            // 上半段：无关泳道竖直穿过
            row.preStraight.forEach { lane ->
                drawLine(laneColor(lane), Offset(x(lane), 0f), Offset(x(lane), centerY), stroke)
            }
            // 上半段：本泳道竖线进入节点
            drawLine(nodeColor, Offset(x(row.lane), 0f), Offset(x(row.lane), centerY), stroke)
            // 上半段：合并点（其他泳道斜向汇入节点）
            row.mergeInPositions.forEach { lane ->
                drawLine(nodeColor, Offset(x(lane), 0f), Offset(x(row.lane), centerY), stroke)
            }
            // 节点圆点（有子提交的外环 + 实心核）
            drawCircle(nodeColor, radius = 4.5.dp.toPx(), center = Offset(x(row.lane), centerY))
            drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.8.dp.toPx(), center = Offset(x(row.lane), centerY))

            // 下半段：无关泳道竖直穿过
            row.postStraight.forEach { lane ->
                drawLine(laneColor(lane), Offset(x(lane), centerY), Offset(x(lane), height), stroke)
            }
            // 下半段：连向各父提交（同泳道竖线 / 分叉斜线）
            row.parentLanes.forEach { lane ->
                drawLine(nodeColor, Offset(x(row.lane), centerY), Offset(x(lane), height), stroke)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = row.shortHash,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = laneColor(row.lane),
                )
                // refs 最多 3 个，避免长徽章（origin/HEAD 等）把日期挤出换行
                row.refs.take(3).forEach { ref -> RefBadge(ref) }
                Spacer(Modifier.weight(1f))
                // 日期单行不换行：之前未限制 maxLines 导致 "09-14" 被挤成 "09/-1/4" 三行
                Text(
                    text = formatDate(row.commitTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    softWrap = false,
                )
            }
            Text(
                text = row.subject.ifBlank { "(no message)" },
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.author,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RefBadge(ref: GitRefLabel) {
    val (tint, label) = when (ref.type) {
        GitRefType.HEAD -> Color(0xFFE65100) to "HEAD"
        GitRefType.BRANCH -> Color(0xFF7C4DFF) to ref.name
        GitRefType.REMOTE -> Color(0xFF3F8FFF) to ref.name
        GitRefType.TAG -> Color(0xFF2E9E5B) to ref.name
    }
    Surface(
        color = tint.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.widthIn(max = 80.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
