package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCheckbox as Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIconName
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.feature.a2uipoc.A2uiPocSurfaceCard
import top.tianyan.app.harness.AskUserQuestions
import top.tianyan.app.ui.theme.semanticColors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * 工具调用卡的运行态配色：组合期读取 [semanticColors]，随明暗主题切换。
 * 原先的文件级十六进制常量在三套主题（标准 M3 / 动态取色 / 液态玻璃）下都固定为深色值，两头都不达标。
 */
private val DotRunning: Color @Composable get() = semanticColors().warning
private val DotSuccess: Color @Composable get() = semanticColors().success
private val DotFailed: Color @Composable get() = MaterialTheme.colorScheme.error

@Composable
internal fun ToolCard(
    call: ToolCall,
    result: ToolResult?,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)?,
    running: Boolean,
    liveStatus: String?,
    showReasoning: Boolean = false,
    defaultExpanded: Boolean = false,
    onRetry: () -> Unit = {},
) {
    var expanded by remember(call.id) { mutableStateOf(false) }
    val dotColor = when {
        result == null && running -> DotRunning
        result?.success == true -> DotSuccess
        result?.success == false -> DotFailed
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    // 状态点补充文本语义，避免 TalkBack 只感知到一块颜色
    val dotDesc = when {
        result == null && running -> stringResource(R.string.chat_tool_state_running)
        result?.success == true -> stringResource(R.string.chat_tool_state_success)
        result?.success == false -> stringResource(R.string.chat_tool_state_failed)
        else -> null
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showReasoning) {
                call.reasoning?.takeIf { it.isNotBlank() }
                    ?.let {
                        ThinkingBlock(
                            id = call.id,
                            reasoning = it,
                            defaultExpanded = defaultExpanded,
                        )
                    }
            }
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(), onClick = { expanded = !expanded },
                shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(0.dp),
                containerColor = Color.Transparent,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.size(18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ToolStatusIndicator(toolIcon(call.tool), dotColor, dotDesc)
                    }
                    Text(
                        toolDisplayName(call),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        toolArgsSummary(call).replace('\n', ' ').replace('\r', ' '),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    // 代码改动统计徽章 (+N -M)
                    val diffStat = remember(call, result) {
                        if (call.tool == HarnessTool.WRITE || call.tool == HarnessTool.EDIT) parseDiffStat(call, result) else null
                    }
                    diffStat?.let { (added, deleted) ->
                        DiffStatBadge(added = added, deleted = deleted)
                    }
                    val durationText = if (result != null) {
                        formatChatDuration(result.durationMs ?: (result.createdAt - call.createdAt).coerceAtLeast(0L))
                    } else if (running) {
                        var elapsed by remember(call.id) { mutableStateOf(0L) }
                        LaunchedEffect(call.id) {
                            while (true) {
                                elapsed = (System.currentTimeMillis() - call.createdAt).coerceAtLeast(0L)
                                // 500ms 刷新足够展示秒级耗时；过密轮询会让长会话持续重组抖动
                                delay(500)
                            }
                        }
                        formatChatDuration(elapsed)
                    } else {
                        null
                    }
                    durationText?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace, maxLines = 1, softWrap = false,
                        )
                    }
                    RuntimeIcon(
                        if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                        Modifier.size(18.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val downloadingPrefix = stringResource(R.string.chat_downloading_prefix)
            val verifyingDownload = stringResource(R.string.chat_verifying_download)
            val downloadStatus = liveStatus?.takeIf {
                call.tool == HarnessTool.DOWNLOAD && result == null && running &&
                    (it.startsWith(downloadingPrefix) || it.startsWith(verifyingDownload))
            }
            if (downloadStatus != null) {
                Text(
                    downloadStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                val percent = Regex("\\((\\d+)%\\)").find(downloadStatus)
                    ?.groupValues?.getOrNull(1)?.toFloatOrNull()?.div(100f)
                if (percent == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { percent },
                        modifier = Modifier.fillMaxWidth().height(2.dp),
                        color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    )
                }
            }

            // A2UI PoC：render_surface 生成的界面直接内嵌聊天流
            if (call.tool == HarnessTool.RENDER_SURFACE && result?.success == true) {
                A2uiPocSurfaceCard(call = call, modifier = Modifier.fillMaxWidth())
            }

            // 展开 Diff 与输出详情视图
            if (expanded) {
                ToolDiffView(
                    call = call,
                    result = result,
                    workspace = workspace,
                    onOpenFile = onOpenFile,
                )
                if (result != null && !running) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onRetry) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.chat_retry_from_tool), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApprovalRequestCard(
    request: AgentApprovalRequestEntity,
    onApprove: (rememberForSession: Boolean) -> Unit,
    onReject: () -> Unit,
) {
    val riskColor = when (request.riskLevel) {
        "critical" -> MaterialTheme.colorScheme.error
        "high" -> semanticColors().warning
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, riskColor.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Shield, Modifier.size(18.dp), riskColor)
                Text(stringResource(R.string.chat_approval_required), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                Surface(color = riskColor.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
                    Text(
                        request.riskLevel.uppercase(),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = riskColor,
                    )
                }
            }
            Text(request.summary, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            Text(request.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 过期倒计时：审批 TTL 默认 10 分钟，超时未决会被 Harness 判定失效。
            var remainingMinutes by remember(request.expiresAt) { mutableStateOf(Int.MAX_VALUE) }
            LaunchedEffect(request.expiresAt) {
                while (true) {
                    val remaining = request.expiresAt - System.currentTimeMillis()
                    remainingMinutes = if (remaining <= 0) 0 else ((remaining + 59_999) / 60_000).toInt()
                    if (remainingMinutes == 0) break
                    delay(15_000)
                }
            }
            if (remainingMinutes > 0 && remainingMinutes != Int.MAX_VALUE) {
                val urgent = remainingMinutes <= 2
                Text(
                    if (remainingMinutes == 1) {
                        stringResource(R.string.chat_approval_expires_soon)
                    } else {
                        stringResource(R.string.chat_approval_expires_minutes, remainingMinutes)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 「本会话内记住」：critical 风险不提供该选项（host 侧同样拒绝写入授权表）。
            var rememberForSession by remember { mutableStateOf(false) }
            if (!request.riskLevel.equals("critical", ignoreCase = true)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberForSession = !rememberForSession },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = rememberForSession,
                        onCheckedChange = { rememberForSession = it },
                    )
                    Text(
                        stringResource(R.string.chat_approval_remember_session),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 审批处理期间禁用按钮，防止重复提交
            var resolving by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        resolving = true
                        onReject()
                    },
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.chat_reject)) }
                Button(
                    onClick = {
                        resolving = true
                        onApprove(rememberForSession)
                    },
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.chat_approve_continue)) }
            }
        }
    }
}

/** ask_user 问题卡：渲染智能体的结构化提问（候选项 chips + 可选自定义输入），与 ApprovalRequestCard 同级复用 pending 请求流。提交答案走 resolveQuestion（答案即工具结果）；「忽略」走既有拒绝路径（模型收到未回答结果）。自定义输入与选项互斥：某题输入非空时优先采用输入。 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun AskUserCard(
    request: AgentApprovalRequestEntity,
    onAnswer: (requestId: String, answersJson: String) -> Unit,
    onSkip: () -> Unit,
) {
    val questions = remember(request.id) {
        runCatching {
            AskUserQuestions.parse(
                Json.parseToJsonElement(request.argumentsJson).jsonObject,
            )
        }.getOrNull().orEmpty()
    }
    val selected = remember(request.id) { mutableStateMapOf<Int, String?>() }
    val custom = remember(request.id) { mutableStateMapOf<Int, String?>() }
    val allAnswered = questions.indices.all { index ->
        !selected[index].isNullOrBlank() || !custom[index].isNullOrBlank()
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeIcon(RuntimeIconName.Chat, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.chat_ask_user_title), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
            }
            if (questions.isEmpty()) {
                Text(stringResource(R.string.chat_ask_user_parse_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            questions.forEachIndexed { index, question ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        buildString {
                            question.header?.let { append("[$it] ") }
                            append(question.question)
                        },
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                    if (question.options.isNotEmpty()) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            question.options.forEach { option ->
                                AskUserOptionChip(
                                    label = option.label,
                                    description = option.description,
                                    isSelected = selected[index] == option.label &&
                                        custom[index].isNullOrBlank(),
                                    onClick = {
                                        if (selected[index] == option.label && custom[index].isNullOrBlank()) {
                                            selected[index] = null
                                        } else {
                                            selected[index] = option.label
                                            custom.remove(index)
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (question.allowCustom) {
                        OutlinedTextField(
                            value = custom[index].orEmpty(),
                            onValueChange = { value ->
                                custom[index] = value
                                if (value.isNotBlank()) selected.remove(index)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(R.string.chat_ask_user_custom_hint), style = MaterialTheme.typography.bodySmall) },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            singleLine = true,
                        )
                    }
                }
            }
            var resolving by remember { mutableStateOf(false) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        resolving = true
                        onSkip()
                    },
                    enabled = !resolving,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.chat_ask_user_skip)) }
                Button(
                    onClick = {
                        resolving = true
                        val answers = buildJsonArray {
                            questions.indices.forEach { index ->
                                val answer = custom[index]?.trim()?.ifBlank { null }
                                    ?: selected[index]?.trim()?.ifBlank { null }
                                    ?: return@forEach
                                add(
                                    buildJsonObject {
                                        put("index", index)
                                        put("answer", answer)
                                    },
                                )
                            }
                        }
                        onAnswer(request.id, answers.toString())
                    },
                    enabled = !resolving && allAnswered,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.chat_ask_user_submit)) }
            }
        }
    }
}

@Composable
private fun AskUserOptionChip(
    label: String,
    description: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        onClick = onClick,
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 工具类型 → 语义前缀图标（与思考块的 Brain 图标同一视觉语言）。形状标识工具类别；颜色由调用处以状态色着色。穷尽 HarnessTool，新增工具有编译器兜底。 */
internal fun toolIcon(tool: HarnessTool): RuntimeIconName = when (tool) {
    HarnessTool.READ -> RuntimeIconName.Document; HarnessTool.WRITE -> RuntimeIconName.WriteDocument
    HarnessTool.EDIT -> RuntimeIconName.Edit
    HarnessTool.BASE -> RuntimeIconName.Terminal
    HarnessTool.PROCESS -> RuntimeIconName.Play
    HarnessTool.HOST -> RuntimeIconName.Phone
    HarnessTool.DOWNLOAD -> RuntimeIconName.Download
    HarnessTool.MEMORY -> RuntimeIconName.Bookmark
    HarnessTool.PLAN -> RuntimeIconName.Checklist
    HarnessTool.SCRATCHPAD -> RuntimeIconName.StickyNote
    HarnessTool.HISTORY_SEARCH -> RuntimeIconName.Search
    HarnessTool.HISTORY_READ -> RuntimeIconName.History
    HarnessTool.BUILD_SCRIPT -> RuntimeIconName.Hammer
    HarnessTool.SUBAGENT -> RuntimeIconName.Bot
    HarnessTool.MCP -> RuntimeIconName.Extension
    HarnessTool.LOAD_RULE -> RuntimeIconName.BookOpen
    HarnessTool.LOAD_SKILL -> RuntimeIconName.BookOpen
    HarnessTool.COMPRESS -> RuntimeIconName.Compress
    HarnessTool.ASK_USER -> RuntimeIconName.Chat
    HarnessTool.RENDER_SURFACE -> RuntimeIconName.Palette
    HarnessTool.RED_TEAM -> RuntimeIconName.Shield
}

internal fun toolArgsSummary(call: ToolCall): String {
    val entries = call.args.toMap().entries.take(3)
        .joinToString(", ") { (key, value) -> "$key=${value.toString().take(40)}" }
    return entries
}

/** 代码行数增减变更徽章：类似 GitHub 的 +27 -8。绿色展示增加行数，红色展示删除行数。 */
@Composable
internal fun DiffStatBadge(
    added: Int,
    deleted: Int,
    modifier: Modifier = Modifier,
) {
    if (added == 0 && deleted == 0) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (added > 0) {
            Text(
                text = "+$added",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
                color = DotSuccess,
            )
        }
        if (deleted > 0) {
            Text(
                text = "-$deleted",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                ),
                color = DotFailed,
            )
        }
    }
}

private val DIFF_STAT_REGEX = Regex("""DIFF_STAT:\s*\+(\d+)\s*-(\d+)""")

/** 从工具输出或参数中解析代码变更统计（增加行数, 删除行数）。 */
internal fun parseDiffStat(call: ToolCall, result: ToolResult?): Pair<Int, Int>? {
    // 若工具执行已完成且失败，不展示代码变更统计
    if (result != null && !result.success) return null

    // 优先从 ToolResult.output 中标准化 DIFF_STAT 标记提取
    if (result != null) {
        val match = DIFF_STAT_REGEX.find(result.output)
        if (match != null) {
            val added = match.groupValues[1].toIntOrNull() ?: 0
            val deleted = match.groupValues[2].toIntOrNull() ?: 0
            if (added > 0 || deleted > 0) return added to deleted
        }
    }
    // 兜底：从 ToolCall 参数中计算行数
    return when (call.tool) {
        HarnessTool.WRITE -> {
            val content = call.args["content"]?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrBlank()) {
                val lines = content.lines().size
                lines to 0
            } else null
        }
        HarnessTool.EDIT -> {
            val newText = call.args["newText"]?.jsonPrimitive?.contentOrNull
            val oldText = call.args["oldText"]?.jsonPrimitive?.contentOrNull
            if (newText != null || oldText != null) {
                val added = newText?.lines()?.size ?: 0
                val deleted = oldText?.lines()?.size ?: 0
                if (added > 0 || deleted > 0) added to deleted else null
            } else null
        }
        else -> null
    }
}
