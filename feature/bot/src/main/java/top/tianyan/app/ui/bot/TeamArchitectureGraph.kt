package top.tianyan.app.ui.bot

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.bot.R
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeTextButton

/** Inline preview opens a separate full-window page without discarding the team draft. */
@Composable
internal fun TeamArchitectureGraph(team: GroupTeam, governance: GroupGovernance, members: List<Bot>,
    editable: Boolean = false, fullScreen: Boolean = false, runtime: TeamGraphRuntime? = null, onSelectMember: (String) -> Unit = {}) {
    val graph = remember(team, governance) { layoutTeamGraph(team, governance) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var fit by rememberSaveable { mutableStateOf(!fullScreen) }
    var manualScale by rememberSaveable { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    Column(if (fullScreen) Modifier.fillMaxSize() else Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("实线：直属管理　虚线：审议／准奏　红色虚线：封驳", style = MaterialTheme.typography.bodySmall)
        if (!fullScreen) RuntimeTextButton(onClick = { expanded = true }) { Text(stringResource(R.string.fbot_graph_open)) }
        else Text(stringResource(if (runtime != null) R.string.fbot_graph_runtime_hint else if (editable) R.string.fbot_graph_edit_hint else R.string.fbot_graph_view_hint), style = MaterialTheme.typography.bodySmall)
        Row {
            RuntimeTextButton(onClick = { fit = true }) { Text("全图") }
            RuntimeTextButton(onClick = { fit = false; manualScale = 1f }) { Text("原尺寸") }
            RuntimeTextButton(onClick = { manualScale = (manualScale / 1.4f).coerceAtLeast(.1f); fit = false }) { Text("−") }
            RuntimeTextButton(onClick = { manualScale = (manualScale * 1.4f).coerceAtMost(2f); fit = false }) { Text("＋") }
        }
        BoxWithConstraints(if (fullScreen) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
            val fitScale = minOf(1f, maxWidth.value / graph.width,
                if (fullScreen) maxHeight.value / graph.height else 1f)
            val scale = if (fit) fitScale else manualScale
            // Keep +/- relative to the visible fit scale, including after a window resize.
            SideEffect { if (fit) manualScale = fitScale }
            val viewport = if (fullScreen) Modifier.fillMaxSize().clipToBounds().verticalScroll(vertical)
                else Modifier.fillMaxWidth()
            Box(viewport.testTag(if (fullScreen) "team-graph-viewport" else "team-graph-preview")) {
                Box(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                    Box(Modifier.size((graph.width * scale).dp, (graph.height * scale).dp)) {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(graph.width.dp, graph.height.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
                            TeamGraphDrawing(graph, team, members, fullScreen && (editable || runtime != null), runtime, onSelectMember)
                        }
                    }
                }
                if (!fullScreen) Box(Modifier.matchParentSize().testTag("team-graph-open").clickable(
                    onClickLabel = stringResource(R.string.fbot_graph_open), onClick = { expanded = true }))
            }
        }
        if (!fullScreen) governance.rules.forEach { rule ->
            val names = members.associateBy { it.id }
            Text("${rule.name}：${names[rule.authorId]?.name} → 审议 ${names[rule.reviewerId]?.name} → 准奏执行 ${names[rule.executorId]?.name}；封驳返回起草者",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
    }
    if (expanded && !fullScreen) TeamArchitecturePage(team, governance, members, editable, { expanded = false }, runtime) { id ->
        expanded = false
        onSelectMember(id)
    }
}

@Composable
private fun TeamGraphDrawing(graph: TeamGraphLayout, team: GroupTeam, members: List<Bot>,
    editable: Boolean, runtime: TeamGraphRuntime?, onSelectMember: (String) -> Unit) {
    val managementColor = MaterialTheme.colorScheme.primary
    val reviewColor = MaterialTheme.colorScheme.tertiary
    val rejectionColor = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val names = members.associateBy { it.id }
    Canvas(Modifier.fillMaxSize()) {
        val nodes = graph.nodes.associateBy { it.id }
        graph.edges.forEach { edge ->
            val from = nodes[edge.from] ?: return@forEach
            val to = nodes[edge.to] ?: return@forEach
            val management = edge.kind == TeamGraphEdgeKind.MANAGEMENT
            val rejection = edge.kind == TeamGraphEdgeKind.REJECTION
            val active = runtime?.edges?.containsKey(edge) == true
            val color = if (management) managementColor else if (rejection) rejectionColor else reviewColor
            val start: Offset
            val end: Offset
            val path = Path()
            if (management) {
                start = Offset((from.x + 80f).dp.toPx(), (from.y + 76f).dp.toPx())
                end = Offset((to.x + 80f).dp.toPx(), to.y.dp.toPx())
                path.moveTo(start.x, start.y)
                val middle = (start.y + end.y) / 2
                path.lineTo(start.x, middle); path.lineTo(end.x, middle); path.lineTo(end.x, end.y)
            } else {
                val right = to.x > from.x
                start = Offset((from.x + if (right) 160f else 0f).dp.toPx(), (from.y + if (rejection) 62f else 25f).dp.toPx())
                end = Offset((to.x + if (right) 0f else 160f).dp.toPx(), (to.y + if (rejection) 62f else 25f).dp.toPx())
                path.moveTo(start.x, start.y)
                val lane = minOf(start.y, end.y) + (if (rejection) 44.dp.toPx() else -34.dp.toPx())
                path.cubicTo(start.x + (end.x - start.x) / 3, lane, start.x + (end.x - start.x) * 2 / 3, lane, end.x, end.y)
            }
            drawPath(path, color, style = Stroke((if (active) 4 else 2).dp.toPx(), pathEffect = if (management) null else PathEffect.dashPathEffect(floatArrayOf(9.dp.toPx(), 5.dp.toPx()))))
            val angle = if (management) Math.PI.toFloat() / 2 else atan2(end.y - start.y, end.x - start.x)
            val arrow = Path().apply {
                moveTo(end.x, end.y)
                lineTo(end.x - 9.dp.toPx() * cos(angle - .45f), end.y - 9.dp.toPx() * sin(angle - .45f))
                moveTo(end.x, end.y)
                lineTo(end.x - 9.dp.toPx() * cos(angle + .45f), end.y - 9.dp.toPx() * sin(angle + .45f))
            }
            drawPath(arrow, color, style = Stroke(2.dp.toPx()))
            val label = when (edge.kind) {
                TeamGraphEdgeKind.MANAGEMENT -> "直属"
                TeamGraphEdgeKind.REVIEW -> "送审"
                TeamGraphEdgeKind.APPROVED_EXECUTION -> "准奏执行"
                TeamGraphEdgeKind.REJECTION -> "封驳"
                TeamGraphEdgeKind.ESCALATION -> "主控裁决"
            }
            val labelY = if (management) (start.y + end.y) / 2 - 16.dp.toPx()
                else minOf(start.y, end.y) + (if (rejection) 24.dp.toPx() else -34.dp.toPx())
            if (runtime?.edges?.containsKey(edge) != true) drawText(measurer, label, topLeft = Offset((start.x + end.x) / 2 - 14.dp.toPx(), labelY), style = labelStyle.copy(color = color))
        }
    }
    runtime?.edges?.forEach { (edge, activity) ->
        val from = graph.nodes.firstOrNull { it.id == edge.from } ?: return@forEach
        val to = graph.nodes.firstOrNull { it.id == edge.to } ?: return@forEach
        val rejection = edge.kind == TeamGraphEdgeKind.REJECTION
        val x = (from.x + to.x) / 2 + 80f
        val y = if (edge.kind == TeamGraphEdgeKind.MANAGEMENT) (from.y + 76f + to.y) / 2 - 18f
            else minOf(from.y, to.y) + if (rejection) 80f else -24f
        RuntimeTextButton(modifier = Modifier.offset((x - 96).dp, y.dp).width(192.dp).height(36.dp).testTag("team-edge-${edge.from}-${edge.to}"),
            contentPadding = PaddingValues(2.dp), onClick = { onSelectMember(if (edge.kind == TeamGraphEdgeKind.MANAGEMENT) edge.to else "proposal:" + activity.proposalIds.joinToString(",")) }) {
            Text(activity.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    graph.nodes.forEach { node ->
        RuntimeCard(modifier = Modifier.offset(node.x.dp, node.y.dp).width(160.dp).heightIn(min = 76.dp).testTag("team-graph-member-${node.id}"),
            onClick = if (editable) ({ onSelectMember(node.id) }) else null, contentPadding = PaddingValues(10.dp)) {
            Column {
                Text(names[node.id]?.name ?: node.id, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (node.id == team.adminBotId) "团队主控／裁决" else names[node.id]?.role.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, maxLines = if (runtime == null) 2 else 1, overflow = TextOverflow.Ellipsis)
                runtime?.nodes?.get(node.id)?.let { activity -> Text(activity.label, style = MaterialTheme.typography.labelSmall, color = if (activity.blocked) rejectionColor else reviewColor) }
            }
        }
    }
}
