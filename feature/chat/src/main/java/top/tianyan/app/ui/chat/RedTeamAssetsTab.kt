package top.tianyan.app.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton

/** 图谱配色，逐项对齐上游 canvas：段 / 存活资产 / 离线资产 / 端口 / 域名。 */
private val SegmentColor = Color(0xFF6366F1)
private val LiveAssetColor = Color(0xFF10B981)
private val DeadAssetColor = Color(0xFF9CA3AF)
private val PortColor = Color(0xFFF59E0B)
private val DomainColor = Color(0xFF8B5CF6)
private val ContainsEdgeColor = Color(0x4D6366F1)
private val OtherEdgeColor = Color(0x598BA3B8)

/**
 * 资产测绘页签：段侧栏 + 工具条 + 列表 / 图谱。
 *
 * 列表与图谱共用同一份过滤结果——两边各自查一次，很容易出现「列表 12 台、图谱 15 台」，
 * 而用户没有任何办法判断哪个是对的。
 */
@Composable
internal fun RedTeamAssetsTab(
    state: RedTeamConsoleState,
    onSelectSegment: (String?) -> Unit,
    onQueryChange: (String) -> Unit,
    onServiceChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onProvenanceChange: (String?) -> Unit,
    onSortChange: (RedTeamConsoleModel.Sort) -> Unit,
    onViewChange: (RedTeamConsoleView) -> Unit,
    onToggleAsset: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SegmentRail(state = state, onSelectSegment = onSelectSegment)

        RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(12.dp)) {
            AssetToolbar(
                state = state,
                onQueryChange = onQueryChange,
                onServiceChange = onServiceChange,
                onPortChange = onPortChange,
                onSubmitQuery = onSubmitQuery,
                onProvenanceChange = onProvenanceChange,
                onSortChange = onSortChange,
                onViewChange = onViewChange,
            )
            if (state.loading) {
                RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            state.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        if (state.view == RedTeamConsoleView.GRAPH) {
            AssetGraphCard(state = state)
        } else {
            AssetListCard(state = state, onToggleAsset = onToggleAsset)
        }

        ConsoleFooter(state = state)
    }
}

/** 段侧栏：全部 C 段 + 每个网段的资产/端口/来源构成。 */
@Composable
private fun SegmentRail(state: RedTeamConsoleState, onSelectSegment: (String?) -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Text(
            "全部 C 段 · ${state.segments.size} 个网段",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SegmentChip(
                selected = state.selectedSegment == null,
                title = "全部",
                subtitle = "${state.stats.assets} 资产",
                onClick = { onSelectSegment(null) },
            )
            state.segments.forEach { segment ->
                SegmentChip(
                    selected = state.selectedSegment == segment.cidr,
                    title = segment.cidr,
                    subtitle = buildString {
                        append(segment.subtitle)
                        // ASN / 国家城市有就带上：上游 segment 表存了这几列，
                        // 面板不显示等于导入时白存。
                        listOfNotNull(segment.asn, segment.country, segment.city).takeIf { it.isNotEmpty() }
                            ?.let { append(" · " + it.joinToString(" ")) }
                        if (segment.passivePorts > 0 || segment.activePorts > 0) {
                            append(" · 被动 ${segment.passivePorts} 主动 ${segment.activePorts}")
                        }
                    },
                    onClick = { onSelectSegment(segment.cidr) },
                )
            }
        }
    }
}

@Composable
private fun SegmentChip(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    RuntimeTextButton(
        onClick = onClick,
        modifier = Modifier.width(172.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Column {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AssetToolbar(
    state: RedTeamConsoleState,
    onQueryChange: (String) -> Unit,
    onServiceChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onProvenanceChange: (String?) -> Unit,
    onSortChange: (RedTeamConsoleModel.Sort) -> Unit,
    onViewChange: (RedTeamConsoleView) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.queryDraft,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索 IP / 域名 / 指纹（回车）", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            RuntimeTextButton(onClick = onSubmitQuery) {
                RuntimeIcon(RuntimeIconName.Search, Modifier.size(18.dp))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.serviceDraft,
                onValueChange = onServiceChange,
                modifier = Modifier.width(96.dp),
                placeholder = { Text("服务", fontSize = 11.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            OutlinedTextField(
                value = state.portDraft,
                onValueChange = onPortChange,
                modifier = Modifier.width(76.dp),
                placeholder = { Text("端口", fontSize = 11.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            ConsoleSelect(
                label = when (state.filter.provenance) {
                    "passive" -> "仅被动"
                    "active" -> "仅主动"
                    else -> "来源不限"
                },
                options = listOf(null to "来源不限", "passive" to "仅被动", "active" to "仅主动"),
                onSelect = onProvenanceChange,
                modifier = Modifier.weight(1f),
            )
            ConsoleSelect(
                label = state.filter.sort.label,
                options = RedTeamConsoleModel.Sort.entries.map { it to it.label },
                onSelect = onSortChange,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            RedTeamConsoleView.entries.forEach { view ->
                val selected = state.view == view
                RuntimeTextButton(
                    onClick = { onViewChange(view) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Text(
                        view.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
            Text(
                "共 ${state.total} 条，显示 ${state.assets.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 通用下拉：`label` 是当前值，`options` 是 (值, 显示名) 列表。 */
@Composable
private fun <T> ConsoleSelect(
    label: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        RuntimeTextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(14.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text, fontSize = 13.sp) },
                    onClick = {
                        expanded = false
                        onSelect(value)
                    },
                )
            }
        }
    }
}

@Composable
private fun AssetListCard(state: RedTeamConsoleState, onToggleAsset: (String) -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("IP" to 1f, "状态" to 0.7f, "端口 / 服务" to 1.4f, "首见" to 0.8f).forEach { (title, weight) ->
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(weight),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.assets.isEmpty()) {
            Text(
                if (state.loading) "加载中…" else "没有匹配的资产",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
            )
            return@RuntimeCard
        }
        state.assets.forEach { asset ->
            AssetRow(
                asset = asset,
                expanded = state.expandedAsset == asset.id,
                // 详情只属于当前展开的那台资产；换台之后旧的详情不能串过去。
                detail = state.detail?.takeIf { state.expandedAsset == asset.id },
                onToggle = { onToggleAsset(asset.id) },
            )
        }
    }
}

@Composable
private fun AssetRow(
    asset: RedTeamConsoleModel.Asset,
    expanded: Boolean,
    detail: RedTeamConsoleModel.Detail?,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
            )
            .padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                asset.ip,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (asset.state) {
                    RedTeamConsoleModel.State.LIVE -> "存活"
                    RedTeamConsoleModel.State.DEAD -> "离线"
                    RedTeamConsoleModel.State.UNKNOWN -> "未知"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (asset.state) {
                    RedTeamConsoleModel.State.LIVE -> LiveAssetColor
                    RedTeamConsoleModel.State.DEAD -> DeadAssetColor
                    RedTeamConsoleModel.State.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(0.7f),
                maxLines = 1,
            )
            Text(
                asset.openPorts.joinToString(",") { it.port.toString() }.ifBlank { "—" } +
                    asset.openPorts.mapNotNull { it.service }.distinct().take(2)
                        .joinToString("", prefix = " ") { it },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatEpochDay(asset.firstSeen),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.8f),
                maxLines = 1,
            )
        }
        Text(
            buildString {
                if (asset.primaryName != null) append("${asset.primaryName} · ")
                append(asset.segmentCidr ?: "未分类")
                if (asset.passivePorts > 0) append(" · 被动 ${asset.passivePorts}")
                if (asset.activePorts > 0) append(" · 主动 ${asset.activePorts}")
                asset.fingerprints.take(2).mapNotNull { it.product ?: it.vendor }.takeIf { it.isNotEmpty() }
                    ?.let { append(" · " + it.joinToString("/")) }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RuntimeTextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
            Text(if (expanded) "收起详情" else "展开详情", style = MaterialTheme.typography.labelSmall)
        }

        if (expanded) {
            AssetDetail(detail = detail)
        }
    }
}

@Composable
private fun AssetDetail(detail: RedTeamConsoleModel.Detail?) {
    val asset = detail?.asset ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 4.dp)
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DetailBlock("主机名", asset.names.map { it.name }.ifEmpty { listOf("—") }.joinToString("、"))
        DetailBlock("C 段", asset.segmentCidr ?: "未分类")
        DetailBlock("首见 / 末见", "${formatEpochTime(asset.firstSeen)} · ${formatEpochTime(asset.lastSeen)}")

        DetailBlock(
            "开放端口 / 服务",
            asset.openPorts.joinToString("\n") { port ->
                buildString {
                    append("${port.port}/${port.proto}")
                    listOfNotNull(port.service, port.product, port.version).takeIf { it.isNotEmpty() }
                        ?.let { append("  " + it.joinToString(" ")) }
                    append("  [${if (port.provenance == "passive") "被动" else "主动"}]")
                }
            }.ifBlank { "—" },
        )

        DetailBlock(
            "指纹",
            asset.fingerprints.joinToString("\n") { fingerprint ->
                buildString {
                    fingerprint.category?.let { append("$it  ") }
                    append(listOfNotNull(fingerprint.vendor, fingerprint.product, fingerprint.version).joinToString(" "))
                    fingerprint.evidence?.let { append("（$it）") }
                    append("  [${if (fingerprint.provenance == "passive") "被动" else "主动"}]")
                }
            }.ifBlank { "—" },
        )

        val observations = detail.observations.take(8)
        DetailBlock(
            "采集溯源（最近 ${observations.size} 条）",
            observations.joinToString("\n") { row ->
                buildString {
                    append(row.attr ?: "—")
                    row.value?.let { append("：$it") }
                    row.tool?.let { append(" · $it") }
                    row.collectedAt?.let { append(" · ${formatEpochTime(it)}") }
                }
            }.ifBlank { "暂无采集记录" },
        )
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    Column {
        Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
    }
}

/**
 * 图谱：按「段 → 资产 → 端口」做确定性环形布局。
 *
 * 上游是力导向迭代；移动端每帧跑迭代会明显掉帧，而且布局每次都在抖，
 * 用户点开的瞬间看到的位置和下一眼不一样。环形布局是确定性的：
 * 同一个靶标每次打开位置一致，代价是边交叉多一些。
 */
@Composable
private fun AssetGraphCard(state: RedTeamConsoleState) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        when {
            state.graphLoading -> Text("图谱加载中…", style = MaterialTheme.typography.bodySmall)
            state.graph == null -> Text("暂无数据", style = MaterialTheme.typography.bodySmall)
            state.graph.nodes.isEmpty() -> Text("暂无数据", style = MaterialTheme.typography.bodySmall)
            else -> {
                val graph = state.graph
                Canvas(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(size.width, size.height) / 2f - 18.dp.toPx()
                    val segments = graph.nodes.filter { it.kind == "segment" }
                    val positions = mutableMapOf<String, Offset>()

                    segments.forEachIndexed { index, node ->
                        val angle = if (segments.isEmpty()) 0.0 else (index.toDouble() / segments.size) * 2 * Math.PI
                        positions[node.id] = Offset(
                            center.x + (radius * 0.55f * cos(angle)).toFloat(),
                            center.y + (radius * 0.55f * sin(angle)).toFloat(),
                        )
                    }

                    graph.nodes.filter { it.kind == "asset" }.forEachIndexed { index, node ->
                        val angle = (index.toDouble() / graph.nodes.count { it.kind == "asset" }.coerceAtLeast(1)) * 2 * Math.PI
                        positions[node.id] = Offset(
                            center.x + (radius * cos(angle)).toFloat(),
                            center.y + (radius * sin(angle)).toFloat(),
                        )
                    }

                    graph.edges.forEach { edge ->
                        val from = positions[edge.source] ?: return@forEach
                        val to = positions[edge.target] ?: return@forEach
                        drawLine(
                            color = if (edge.relation == "contains") ContainsEdgeColor else OtherEdgeColor,
                            start = from,
                            end = to,
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    graph.nodes.forEach { node ->
                        val at = positions[node.id] ?: return@forEach
                        // 颜色与半径分开算：离线资产要和存活资产一样大，
                        // 只是换成灰的——否则「离线」会被读成「小角色」。
                        val color = when (node.kind) {
                            "segment" -> SegmentColor
                            "asset" -> if (node.state == RedTeamConsoleModel.State.DEAD) DeadAssetColor else LiveAssetColor
                            "port" -> PortColor
                            else -> DomainColor
                        }
                        val radiusDp = when (node.kind) {
                            "segment" -> 13f
                            "asset" -> 7f
                            "port" -> 4.5f
                            else -> 6f
                        }
                        drawCircle(color = color, radius = radiusDp.dp.toPx(), center = at)
                    }
                }
                Text(
                    "图例：C 段 / 存活资产 / 离线资产 / 开放端口 / 域名 · ${state.graph.nodes.size} 节点 ${state.graph.edges.size} 边",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 底栏统计：与上游面板同口径。 */
@Composable
private fun ConsoleFooter(state: RedTeamConsoleState) {
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
        Text(
            buildString {
                append("C 段 ${state.stats.segments}")
                append(" · 资产 ${state.stats.assets}（存活 ${state.stats.liveAssets}）")
                append(" · 端口 ${state.stats.openPorts}")
                append(" · 指纹 ${state.stats.fingerprints}")
                append(" · 被动/主动 ${state.stats.passiveSignals}/${state.stats.activeSignals}")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
