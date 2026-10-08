package top.tianyan.app.ui.chat
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
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
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import androidx.compose.ui.text.style.TextAlign
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
            if (state.segments.isEmpty()) {
                "网段 · 当前资产都不是 IP，无法归段"
            } else {
                "全部 C 段 · ${state.segments.size} 个网段"
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
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
        },
    )
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 搜索：输入框 + 图标按钮。图标按钮改成有边框的小方按钮，
        // 原来它悬在输入框右侧、和输入框不等高，看着像没对齐。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.queryDraft,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索 IP / 域名 / 指纹", fontSize = 13.sp) },
                leadingIcon = {
                    RuntimeIcon(RuntimeIconName.Search, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            RuntimeOutlinedButton(
                onClick = onSubmitQuery,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("搜索", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }

        // 筛选条件排成一行可横向滚动：手机宽度放不下「服务 + 端口 + 来源 + 排序」四个控件，
        // 挤成两行会让下拉框窄到看不清当前选中项（截图里两个下拉只剩一个箭头）。
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.serviceDraft,
                onValueChange = onServiceChange,
                modifier = Modifier.width(110.dp),
                placeholder = { Text("服务", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            OutlinedTextField(
                value = state.portDraft,
                onValueChange = onPortChange,
                modifier = Modifier.width(92.dp),
                placeholder = { Text("端口", fontSize = 12.sp) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmitQuery() }),
            )
            // 两个下拉用 FilterChip 呈现当前值：胶囊本身就说明「这是个可切换的筛选」，
            // 而且宽度自适应、不会像下拉框那样窄到只剩箭头。
            FilterChip(
                selected = state.filter.provenance != null,
                onClick = { onProvenanceChange(nextProvenance(state.filter.provenance)) },
                label = {
                    Text(
                        when (state.filter.provenance) {
                            "passive" -> "仅被动"
                            "active" -> "仅主动"
                            else -> "来源不限"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                leadingIcon = {
                    RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(14.dp))
                },
            )
            ConsoleSelect(
                label = state.filter.sort.label,
                options = RedTeamConsoleModel.Sort.entries.map { it to it.label },
                onSelect = onSortChange,
                modifier = Modifier.width(148.dp),
            )
        }

        // 视图切换 + 结果计数。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RedTeamConsoleView.entries.forEach { view ->
                FilterChip(
                    selected = state.view == view,
                    onClick = { onViewChange(view) },
                    label = { Text(view.label, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                )
            }
            Text(
                "共 ${state.total} 条 · 显示 ${state.assets.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 来源筛选是三态循环：不限 → 仅被动 → 仅主动 → 不限。 */
private fun nextProvenance(current: String?): String? = when (current) {
    null -> "passive"
    "passive" -> "active"
    else -> null
}

/** 通用下拉：`label` 是当前值，`options` 是 (值, 显示名) 列表。 */
/** 下拉筛选：与设置页同一套 ExposedDropdownMenuBox，不手搓菜单。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ConsoleSelect(
    label: String,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelMedium,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
        // 行改成了两行式卡片，不再有列对齐，所以这里不放假表头——
        // 列名对不齐比没有列名更容易误读。改为计数 + 排序说明。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${state.assets.size} 台资产",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "按${state.filter.sort.label}排序 · 点条目展开详情",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
    // 两行式条目：第一行是「主机 + 状态」，第二行是「端口/服务 + 指纹」，
    // 右侧一个展开箭头。原来四列都用 weight 平分宽度，IP 被压得和日期一样窄，
    // 而「未分类」在每行都重复一遍，占掉了最该给端口和指纹的位置。
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onToggle() },
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 状态点：比「存活/离线」两个字更省位置，颜色本身就是信息。
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (asset.state) {
                                RedTeamConsoleModel.State.LIVE -> LiveAssetColor
                                RedTeamConsoleModel.State.DEAD -> DeadAssetColor
                                RedTeamConsoleModel.State.UNKNOWN -> MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Text(
                    asset.ip,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    formatEpochDay(asset.firstSeen),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                RuntimeIcon(
                    if (expanded) RuntimeIconName.ChevronUp else RuntimeIconName.ChevronDown,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                buildString {
                    val ports = asset.openPorts.joinToString("、") { it.port.toString() }
                    append(if (ports.isBlank()) "无开放端口" else "端口 $ports")
                    asset.openPorts.mapNotNull { it.service }.distinct().take(3)
                        .takeIf { it.isNotEmpty() }?.let { append(" · " + it.joinToString("/")) }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // 第三行只在真有信息时出现：域名 / 指纹 / 来源标记。
            // 「未分类」不再逐行显示——它由左侧段侧栏统一表达，逐行重复只是噪声。
            val extra = buildString {
                asset.primaryName?.let { append(it) }
                asset.fingerprints.take(2).mapNotNull { it.product ?: it.vendor }
                    .takeIf { it.isNotEmpty() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it.joinToString("/"))
                    }
                if (asset.passivePorts > 0 || asset.activePorts > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("被动 ${asset.passivePorts} 主动 ${asset.activePorts}")
                }
            }
            if (extra.isNotBlank()) {
                Text(
                    extra,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (expanded) {
                AssetDetail(detail = detail)
            }
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
