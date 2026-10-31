package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.McpToolInfo
import top.tianyan.app.core.model.McpToolPolicy
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

private val RISK_OPTIONS = listOf(
    null to "继承",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "critical" to "严重",
)

/** 单个 MCP 工具的紧凑策略行：名称/描述 + 风险覆盖 + 启停。 */
@Composable
internal fun McpToolPolicyRow(
    tool: McpToolInfo,
    policy: McpToolPolicy,
    onEnabledChange: (Boolean) -> Unit,
    onRiskChange: (String?) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (policy.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (tool.description.isNotBlank()) {
                Text(
                    text = tool.description.replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            TextButton(
                onClick = { menuExpanded = true },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = riskLabel(policy.riskOverride),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (policy.riskOverride != null) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                RISK_OPTIONS.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, style = MaterialTheme.typography.labelMedium) },
                        onClick = {
                            menuExpanded = false
                            onRiskChange(value)
                        },
                    )
                }
            }
        }
        Switch(checked = policy.enabled, onCheckedChange = onEnabledChange)
    }
}

private fun riskLabel(risk: String?): String = when (risk) {
    "low" -> "风险：低"
    "normal", "medium" -> "风险：中"
    "high" -> "风险：高"
    "critical" -> "风险：严重"
    else -> "风险：继承"
}
