package top.tianyan.app.ui.settings.skill

import androidx.compose.foundation.background
import top.tianyan.app.ui.theme.semanticColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.skill.AuditLevel
import top.tianyan.app.core.model.skill.CompatibilityLevel
import top.tianyan.app.core.tools.skill.SkillInstallInspection
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator

/**
 * 技能端侧静态安全审计与权限审查弹窗（基于 RuntimeAlertDialog 与 M3 Expressive 设计规范）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillSecurityAuditDialog(
    inspection: SkillInstallInspection,
    isCommitting: Boolean,
    onConfirmInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val audit = inspection.auditReport
    val compat = inspection.compatibilityResult
    val pkg = inspection.pkg
    val isBlocked = inspection.isBlocked

    val badgeColor = when (audit.level) {
        AuditLevel.SAFE -> semanticColors().success
        AuditLevel.INFO -> MaterialTheme.colorScheme.primary
        AuditLevel.WARNING -> semanticColors().warning
        AuditLevel.DANGER -> semanticColors().warning
        AuditLevel.BLOCKED -> MaterialTheme.colorScheme.error
    }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RuntimeIcon(
                    name = if (isBlocked) RuntimeIconName.Alert else RuntimeIconName.Shield,
                    tint = badgeColor,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = if (isBlocked) "安全阻断：禁止安装" else "技能安装与静态安全审查",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "PalmClaw / 天衍端侧安全边界审计",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. 技能基本信息卡片
                RuntimeCard(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = pkg.manifest.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Surface(
                                color = badgeColor.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Text(
                                    text = audit.level.label,
                                    color = badgeColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                        Text(
                            text = pkg.manifest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Text(
                                text = "版本: v${pkg.manifest.version}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "作者: ${pkg.manifest.author}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }

                // 2. 权限边界申请
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "申请权限与操作边界：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val allPerms = (audit.declaredPermissions + audit.detectedPermissions).toList()
                    if (allPerms.isEmpty()) {
                        Text(
                            text = "纯只读提示词扩展，无需申请外部系统权限",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            allPerms.forEach { perm ->
                                val isHighRisk = perm.isHighRisk
                                Surface(
                                    color = if (isHighRisk) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    ) {
                                        Text(
                                            text = perm.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isHighRisk) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isHighRisk) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 兼容性评估结果
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "环境兼容性判定：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = compat.level.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (compat.level) {
                            CompatibilityLevel.COMPATIBLE -> semanticColors().success
                            CompatibilityLevel.PARTIALLY_COMPATIBLE -> semanticColors().warning
                            CompatibilityLevel.INCOMPATIBLE -> MaterialTheme.colorScheme.error
                        },
                        fontWeight = FontWeight.Medium,
                    )
                    if (compat.missingTools.isNotEmpty()) {
                        Text(
                            text = "缺少依赖工具: ${compat.missingTools.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                // 4. 静态审计发现项（若有风险则展示详细代码段落）
                if (audit.findings.isNotEmpty()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Text(
                        text = "审计发现项 (${audit.findings.size} 项)：",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )

                    audit.findings.take(6).forEach { finding ->
                        val itemColor = when (finding.level) {
                            AuditLevel.BLOCKED, AuditLevel.DANGER -> MaterialTheme.colorScheme.error
                            AuditLevel.WARNING -> semanticColors().warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(itemColor.copy(alpha = 0.08f))
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "[${finding.ruleId}] ${finding.title}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = itemColor,
                            )
                            Text(
                                text = finding.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            finding.snippet?.let { snippet ->
                                Text(
                                    text = snippet,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .padding(top = 2.dp)
                                        .background(Color.Black.copy(alpha = 0.05f))
                                        .padding(4.dp),
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "⚠ 静态审计仅为初步防线，无法识别所有混淆与恶意行为；安装后该技能脚本的实际执行仍会逐条申请审批。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        },
        confirmButton = {
            if (isBlocked) {
                RuntimeButton(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("安全策略已阻断")
                }
            } else {
                RuntimeButton(
                    onClick = onConfirmInstall,
                    enabled = !isCommitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (audit.hasWarnings) semanticColors().warning else MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    if (isCommitting) {
                        RuntimeCircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text("正在安装…")
                    } else {
                        Text(if (audit.hasWarnings) "了解风险并确认安装" else "通过审查并安装")
                    }
                }
            }
        },
        dismissButton = {
            RuntimeOutlinedButton(onClick = onDismiss) {
                Text(if (isBlocked) "关闭" else "放弃")
            }
        },
    )
}
