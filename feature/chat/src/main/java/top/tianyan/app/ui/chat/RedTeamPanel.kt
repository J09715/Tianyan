package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.RedTeamFactEntity
import top.tianyan.app.core.model.RedTeamFactKind
import top.tianyan.app.core.model.RedTeamGraphModel
import top.tianyan.app.core.model.RedTeamIpUtils
import top.tianyan.app.core.model.RedTeamPreflightReport
import top.tianyan.app.core.model.RedTeamPhase
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

/** Mobile red-team workspace: target binding plus the session's own fact inventory. */
@Composable
internal fun RedTeamPanel(
    session: HarnessSessionEntity,
    facts: List<RedTeamFactEntity>,
    onBindTarget: (String, String) -> Unit,
    skillHealth: RedTeamPreflightReport? = null,
    onRefreshSkillHealth: (() -> Unit)? = null,
) {
    var target by remember(session.id, session.redTeamTarget) { mutableStateOf(session.redTeamTarget.orEmpty()) }
    var scope by remember(session.id, session.redTeamScope) { mutableStateOf(session.redTeamScope) }
    var expanded by remember(session.id) { mutableStateOf(false) }
    var showGraph by remember(session.id) { mutableStateOf(true) }
    val phase = RedTeamPhase.fromId(session.redTeamPhase)
    val grouped = remember(facts) { facts.groupBy { it.kind } }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                Column(Modifier.weight(1f)) {
                    Text("红队模式 · 当前会话", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (session.redTeamTarget.isNullOrBlank()) "目标与作战状态仅属于本会话" else "已绑定 ${session.redTeamTarget}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                Text(if (session.redTeamTarget.isNullOrBlank()) "确认并绑定目标" else "更新当前会话目标", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            if (!session.redTeamTarget.isNullOrBlank()) {
                Surface(color = Color(0xFF2E7D32).copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Scope", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        session.redTeamScope.lineSequence().filter { it.isNotBlank() }.take(8).forEach {
                            Text(it.trim(), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // 资产图谱：C 段 → 资产 → 端口，按内网/外网分组。
            // 归段走与智能体同一份 RedTeamIpUtils，界面与工具看到的网段必须一致。
            val graph = remember(facts) {
                RedTeamGraphModel.build(
                    assets = facts
                        .filter { it.kind == RedTeamFactKind.ASSET.id }
                        .map { RedTeamGraphModel.GraphNode(it.id, it.title, it.target, it.status) },
                    edgeCount = facts.count { it.kind == RedTeamFactKind.EDGE.id },
                )
            }
            if (graph.segments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "资产图谱 · ${graph.segments.size} 段 / ${graph.assetCount} 资产",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    RuntimeButton(onClick = { showGraph = !showGraph }, shape = RoundedCornerShape(8.dp)) {
                        Text(if (showGraph) "收起" else "展开", maxLines = 1)
                    }
                }
                if (showGraph) {
                    graph.byScope.forEach { (scope, segments) ->
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${when (scope) {
                                        "internal" -> "内网"
                                        "external" -> "外网"
                                        else -> "未分类"
                                    }} · ${segments.sumOf { it.assets.size }} 资产",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (scope == "internal") Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                                )
                                segments.take(if (expanded) 12 else 4).forEach { segment ->
                                    Text(
                                        "▸ ${segment.cidr}（${segment.assets.size}）",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    segment.assets.take(if (expanded) 8 else 2).forEach { asset ->
                                        Text(
                                            "   • ${asset.target ?: asset.title}${asset.status.takeIf { it != "observed" }?.let { " [$it]" } ?: ""}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (graph.edgeCount > 0) {
                        Text(
                            "关系边 ${graph.edgeCount} 条",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 技能体检：能列出来 ≠ 能跑。缺环境变量/工具在这里就要提示，别等进了靶场才发现。
            skillHealth?.let { health ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "技能体检 · ${health.available}/${health.total} 可用",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    onRefreshSkillHealth?.let { refresh ->
                        RuntimeButton(onClick = refresh, shape = RoundedCornerShape(8.dp)) {
                            Text("重新体检", maxLines = 1)
                        }
                    }
                }
                val unusable = health.skills.filterNot { it.usable }
                Surface(
                    color = if (health.ready) Color(0xFF2E7D32).copy(alpha = 0.10f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.30f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (health.total == 0) {
                            Text("没有可体检的红队技能。", style = MaterialTheme.typography.labelSmall)
                        } else if (health.ready) {
                            Text("${health.total} 个技能全部可用。", style = MaterialTheme.typography.labelSmall)
                        } else {
                            unusable.take(if (expanded) 10 else 3).forEach { skill ->
                                Text(
                                    "• ${skill.name}：${skill.problems.firstOrNull() ?: skill.status}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                skill.fix.takeIf { it.isNotEmpty() }?.let { fix ->
                                    Text(
                                        "   修法：$fix",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        if (health.needsUser.isNotEmpty()) {
                            Text(
                                "需要你提供：" + health.needsUser.joinToString("、"),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Text("作战数据 · ${facts.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            if (facts.isEmpty()) {
                Text(
                    "尚无事实记录。派发角色后再把资产、漏洞、凭据与隧道写入当前会话。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                RedTeamFactKinds.filter { grouped.containsKey(it.id) }.forEach { kind ->
                    val rows = grouped.getValue(kind.id)
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(8.dp)) {
                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${kind.label} · ${rows.size}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            rows.take(if (expanded) 20 else 3).forEach { fact ->
                                Text(
                                    buildString {
                                        append("• ${fact.title}")
                                        fact.severity?.let { append(" [$it]") }
                                        fact.target?.let { append(" · $it") }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (facts.size > 3) {
                    RuntimeButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(if (expanded) "收起明细" else "展开全部 ${facts.size} 条", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Panels mirrored from the upstream web console, ordered by engagement flow. */
private val RedTeamFactKinds = listOf(
    RedTeamFactKind.ASSET,
    RedTeamFactKind.EDGE,
    RedTeamFactKind.VULNERABILITY,
    RedTeamFactKind.CREDENTIAL,
    RedTeamFactKind.ACCESS_SESSION,
    RedTeamFactKind.WEBSHELL,
    RedTeamFactKind.TUNNEL,
    RedTeamFactKind.ATTACK_STEP,
    RedTeamFactKind.SCORE_HIT,
    RedTeamFactKind.ATTACK_FILE,
    RedTeamFactKind.KNOWLEDGE,
    RedTeamFactKind.SKILL,
    RedTeamFactKind.EVENT,
)

private val RedTeamFactKind.label: String
    get() = when (this) {
        RedTeamFactKind.ENGAGEMENT -> "靶标"
        RedTeamFactKind.ASSET -> "资产"
        RedTeamFactKind.EDGE -> "关系"
        RedTeamFactKind.VULNERABILITY -> "漏洞"
        RedTeamFactKind.CREDENTIAL -> "凭据"
        RedTeamFactKind.ACCESS_SESSION -> "访问会话"
        RedTeamFactKind.WEBSHELL -> "WebShell"
        RedTeamFactKind.TUNNEL -> "隧道"
        RedTeamFactKind.ATTACK_STEP -> "攻击链"
        RedTeamFactKind.ATTACK_FILE -> "攻击文件"
        RedTeamFactKind.SCORE_HIT -> "评分"
        RedTeamFactKind.REPORT -> "报告"
        RedTeamFactKind.KNOWLEDGE -> "知识库"
        RedTeamFactKind.SKILL -> "技能"
        RedTeamFactKind.AGENT -> "智能体"
        RedTeamFactKind.EVENT -> "事件"
    }
