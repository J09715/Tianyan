package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.theme.Spacing

/**
 * 「凭据与文件」页签：凭据、已获得访问会话、攻击文件三组。
 *
 * 为什么单独一页：这三类都是**作战过程中产出的物料**，不是「发现」也不是「得分」。
 * 上游把它们从「会话隧道」里拆出来单独成页，是因为打完一轮后用户最常问的是
 * 「我现在手上有什么钥匙、有什么文件」——混在会话隧道里要翻半天。
 *
 * 默认折叠 + 凭据脱敏：凭据是明文存本机的，页签默认收起、正文默认打码，
 * 防止别人扫一眼屏幕就看到密码。点开才显示。
 */
@Composable
internal fun RedTeamArtifactsTab(
    rows: List<RedTeamConsoleModel.Fact>,
    loading: Boolean,
) {
    // 三组按 kind 拆：凭据 / 访问会话 / 攻击文件
    val credentials = remember(rows) { rows.filter { it.kind == "credential" } }
    val accesses = remember(rows) { rows.filter { it.kind == "access_session" } }
    val files = remember(rows) { rows.filter { it.kind == "attack_file" } }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        ArtifactSummary(
            credentials = credentials.size,
            accesses = accesses.size,
            files = files.size,
            loading = loading,
        )
        ArtifactGroup(
            icon = RuntimeIconName.Key,
            title = "凭据",
            subtitle = "默认脱敏，点开查看明文",
            rows = credentials,
            masked = true,
        )
        ArtifactGroup(
            icon = RuntimeIconName.Cable,
            title = "已获得访问会话",
            subtitle = "WebShell 与隧道之外的交互式会话",
            rows = accesses,
            masked = false,
        )
        ArtifactGroup(
            icon = RuntimeIconName.File,
            title = "攻击文件",
            subtitle = "落到目标上的载荷、脚本与导出",
            rows = files,
            masked = false,
        )
    }
}

@Composable
private fun ArtifactSummary(
    credentials: Int,
    accesses: Int,
    files: Int,
    loading: Boolean,
) {
    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                "凭据" to credentials,
                "访问会话" to accesses,
                "攻击文件" to files,
            ).forEach { (label, count) ->
                Column {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (loading) {
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ArtifactGroup(
    icon: RuntimeIconName,
    title: String,
    subtitle: String,
    rows: List<RedTeamConsoleModel.Fact>,
    masked: Boolean,
) {
    // 空组也渲染标题：用户需要知道「这一类确实没有」，而不是以为页签没做。
    var expanded by remember(title) { mutableStateOf(false) }
    // 凭据默认脱敏；用户主动点「显示」才展开明文。
    var revealed by remember(title) { mutableStateOf(!masked) }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            RuntimeIcon(icon, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(
                    text = "$title · ${rows.size}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (masked && rows.isNotEmpty()) {
                RuntimeIconButton(
                    onClick = { revealed = !revealed },
                    modifier = Modifier.size(48.dp),
                ) {
                    RuntimeIcon(
                        if (revealed) RuntimeIconName.VisibilityOff else RuntimeIconName.Visibility,
                        Modifier.size(18.dp),
                    )
                }
            }
            if (rows.isNotEmpty()) {
                RuntimeIconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(48.dp),
                ) {
                    RuntimeIcon(
                        if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                        Modifier.size(18.dp),
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                rows.forEach { row ->
                    ArtifactRow(row = row, revealed = revealed)
                }
            }
        }
    }
}

@Composable
private fun ArtifactRow(row: RedTeamConsoleModel.Fact, revealed: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = if (revealed) row.title else maskSecret(row.title),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val meta = listOfNotNull(
            row.target?.takeIf { it.isNotBlank() },
            row.status.takeIf { it.isNotBlank() && it != "observed" },
        ).joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 凭据脱敏：保留用户名与首尾各 2 个字符，中间打码。
 *
 * 只脱密码部分而不是整行 —— 用户需要认出「这是哪个账号的密码」，
 * 全打码等于什么都看不出来。
 */
internal fun maskSecret(title: String): String {
    // 常见形态 user:password 或 user/password
    val separatorIndex = title.indexOfFirst { it == ':' || it == '/' }
    if (separatorIndex <= 0 || separatorIndex >= title.length - 1) {
        return if (title.length <= 4) "****" else title.take(2) + "****" + title.takeLast(2)
    }
    val head = title.substring(0, separatorIndex + 1)
    val secret = title.substring(separatorIndex + 1)
    val masked = when {
        secret.length <= 4 -> "****"
        else -> secret.take(2) + "****" + secret.takeLast(2)
    }
    return head + masked
}
