package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshAuthentication
import top.tianyan.app.ui.components.*

@Composable
internal fun BackendChoiceCard(title: String, detail: String, icon: RuntimeIconName, selected: Boolean, onClick: () -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), onClick = onClick,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(icon, size = 40.dp, color = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) StatusBadge("当前")
        }
    }
}

@Composable
internal fun ConnectionProfileCard(server: RemoteServer, selected: Boolean, onSelect: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), onClick = onSelect,
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconTile(RuntimeIconName.Server, size = 36.dp, color = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${server.username}@${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RuntimeIconButton(onClick = onEdit, contentDescription = "编辑 ${server.name}") { RuntimeIcon(RuntimeIconName.Edit) }
                RuntimeIconButton(onClick = onRemove, contentDescription = "移除 ${server.name}") { RuntimeIcon(RuntimeIconName.Trash, tint = MaterialTheme.colorScheme.error) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(server.workspace, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusBadge(if (selected) "主系统" else if (server.authentication == SshAuthentication.PRIVATE_KEY) "密钥认证" else "密码认证")
            }
        }
    }
}
