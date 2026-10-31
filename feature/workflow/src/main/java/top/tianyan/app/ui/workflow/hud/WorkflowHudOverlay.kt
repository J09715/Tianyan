package top.tianyan.app.ui.workflow.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.runtime.gui.WorkflowGuiHudBridge

/**
 * 工作流悬浮窗（药丸形）：状态指示 + 阶段文案 + 停止按钮。
 * 运行中：小进度圈/状态点 + "思考中/操作中" + 停止；结束后：状态色点 + 结果文案 + 关闭。
 */
@Composable
fun WorkflowHudOverlay(
    session: WorkflowGuiHudBridge.Session,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val phaseColor = when (session.phase) {
        WorkflowGuiHudBridge.Phase.SUCCESS -> MaterialTheme.colorScheme.primary
        WorkflowGuiHudBridge.Phase.FAILED -> MaterialTheme.colorScheme.error
        WorkflowGuiHudBridge.Phase.CANCELLED -> MaterialTheme.colorScheme.outline
        WorkflowGuiHudBridge.Phase.ACTING -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.secondary
    }
    val phaseLabel = when (session.phase) {
        WorkflowGuiHudBridge.Phase.THINKING -> "思考中"
        WorkflowGuiHudBridge.Phase.ACTING -> "操作中"
        WorkflowGuiHudBridge.Phase.SUCCESS -> "成功"
        WorkflowGuiHudBridge.Phase.FAILED -> "失败"
        WorkflowGuiHudBridge.Phase.CANCELLED -> "已停止"
        WorkflowGuiHudBridge.Phase.IDLE -> "空闲"
    }

    Surface(
        modifier = Modifier
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .heightIn(min = 40.dp),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        tonalElevation = 3.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        ) {
            if (session.active) {
                if (session.phase == WorkflowGuiHudBridge.Phase.ACTING) {
                    // 屏幕操作期间悬浮窗通常已摘除，此处仅兜底展示状态点
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(phaseColor, CircleShape),
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = phaseColor,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(phaseColor, CircleShape),
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (session.active) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onStop)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "停止",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "✕",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
