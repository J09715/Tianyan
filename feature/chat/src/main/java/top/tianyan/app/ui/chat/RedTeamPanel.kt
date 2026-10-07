package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.RedTeamPhase
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun RedTeamPanel(
    session: HarnessSessionEntity,
    onBindTarget: (String, String) -> Unit,
) {
    var target by remember(session.id, session.redTeamTarget) { mutableStateOf(session.redTeamTarget.orEmpty()) }
    var scope by remember(session.id, session.redTeamScope) { mutableStateOf(session.redTeamScope) }
    val phase = RedTeamPhase.fromId(session.redTeamPhase)
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text("红队模式 · 当前会话", fontWeight = FontWeight.Bold)
                    Text("目标与作战状态仅属于本会话", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(phase.displayName, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            }
            OutlinedTextField(
                value = target,
                onValueChange = { target = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标") },
                placeholder = { Text("域名、URL、IP、CIDR 或目标单位") },
                singleLine = true,
            )
            OutlinedTextField(
                value = scope,
                onValueChange = { scope = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("范围 Scope") },
                placeholder = { Text("每行一个允许的域名、IP 或 CIDR") },
                minLines = 2,
                maxLines = 4,
            )
            RuntimeButton(
                onClick = { onBindTarget(target.trim(), scope.trim()) },
                enabled = target.trim().isNotEmpty() && scope.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (session.redTeamTarget.isNullOrBlank()) "确认并绑定目标" else "更新当前会话目标")
            }
            if (session.redTeamTarget != null) {
                Text("已绑定：${session.redTeamTarget}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
            }
        }
    }
}
