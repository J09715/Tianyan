package top.tianyan.app.ui.chat

import top.tianyan.app.ui.components.StreamingMarkdownText
import top.tianyan.app.harness.MessageAttachment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCard
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.LruCache
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.CapabilityEvent
import top.tianyan.app.harness.SkillSuggestion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.harness.ModelSwitchEvent
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.checkpoint.RewindScope
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.context.GlobalContext
import top.tianyan.app.core.common.navigation.AppNavigationTarget
import top.tianyan.app.core.common.navigation.GlobalNavigationBus
import top.tianyan.app.core.common.translation.TranslationManager
import top.tianyan.app.core.common.translation.TranslationModelStatus

/** 消息气泡：用户/助手气泡、思考块、任务计划卡、能力事件卡。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UserBubble(
    message: UserMessage,
    knownMentionNames: List<String> = emptyList(),
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCreateBranch: () -> Unit,
    onRewind: (RewindScope) -> Unit = {},
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    // 删除消息为破坏性操作，先经确认对话框
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    // 撤回到此轮：先选择撤回范围（代码/对话/两者）
    var showRewindScopeDialog by rememberSaveable { mutableStateOf(false) }
    var previewImageUrl by remember { mutableStateOf<String?>(null) } // 缩略图点击→全屏预览

    val copyText = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_user_request_clipboard), message.text))
        Toast.makeText(context, context.getString(R.string.chat_request_copied), Toast.LENGTH_SHORT).show()
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 🌟 1. 多模态图片预览卡片
            if (message.imageUrls.isNotEmpty()) { LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(bottom = 2.dp),
                ) {
                    itemsIndexed(
                        items = message.imageUrls,
                        key = { index, _ -> "${message.id}:$index" },
                    ) { index, imageUrl ->
                        ImageThumbnail(
                            imageUrl = imageUrl,
                            cacheKey = "chat-image-${message.id}-$index",
                            onClick = { previewImageUrl = imageUrl },
                        )
                    }
                }
            }
            UserAttachmentCards(message.attachments, context); previewImageUrl?.let { u -> ChatImagePreviewDialog(u) { previewImageUrl = null } } // 附件卡片 + 图片预览
            if (message.text.isNotBlank()) { // 🌟 2. 用户文字气泡
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(24.dp, 8.dp, 24.dp, 24.dp),
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .clickable { showMenu = true },
                ) {
                    val mentionColor = MaterialTheme.colorScheme.primary
                    val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    val annotatedText = remember(message.text, knownMentionNames, mentionColor, mentionBg) {
                        formatMentionText(message.text, knownMentionNames, mentionColor, mentionBg)
                    }
                    SelectionContainer {
                        Text(
                            text = annotatedText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            } else if (message.imageUrls.isNotEmpty()) {
                // 纯图片消息：显示小菜单按钮
                Surface(
                    onClick = { showMenu = true },
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = CircleShape,
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        RuntimeIcon(
                            RuntimeIconName.More,
                            Modifier.size(15.dp),
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_copy)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Copy, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    copyText()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_edit_resend)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Edit, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    onEdit()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_create_branch)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    onCreateBranch()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_rewind)) },
                leadingIcon = { RuntimeIcon(RuntimeIconName.Reverse, Modifier.size(16.dp)) },
                onClick = {
                    showMenu = false
                    showRewindScopeDialog = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_delete), color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    RuntimeIcon(
                        RuntimeIconName.Trash,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showMenu = false
                    showDeleteConfirm = true
                },
            )
        }
    }

    // 删除消息二次确认
    if (showDeleteConfirm) {
        RuntimeAlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.chat_delete_message_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.chat_delete_message_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }

    // 撤回范围选择：仅代码 / 仅对话 / 两者
    if (showRewindScopeDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showRewindScopeDialog = false },
            title = { Text(stringResource(R.string.chat_rewind_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.chat_rewind_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_code),
                        description = stringResource(R.string.chat_rewind_code_desc),
                        icon = RuntimeIconName.Code,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.CODE) },
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_conversation),
                        description = stringResource(R.string.chat_rewind_conversation_desc),
                        icon = RuntimeIconName.Chat,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.CONVERSATION) },
                    )
                    RewindScopeOption(
                        label = stringResource(R.string.chat_rewind_both),
                        description = stringResource(R.string.chat_rewind_both_desc),
                        icon = RuntimeIconName.Reverse,
                        onClick = { showRewindScopeDialog = false; onRewind(RewindScope.BOTH) },
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showRewindScopeDialog = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

/** 撤回范围选择行：图标 + 标题 + 说明，整行可点。 */
@Composable
private fun RewindScopeOption(
    label: String,
    description: String,
    icon: RuntimeIconName,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeIcon(icon, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 🌟 多模态图片优雅缩略图卡片（支持 Base64 Data URL 与本地文件路径）。
 *
 * Base64 解码不能放在 remember 里：remember 的计算发生在 Compose 主线程，历史大图会
 * 直接阻塞切换智枢的首帧。交给 Coil 后，DataUriFetcher/图片解码在后台执行，并按缩略图
 * 的实际尺寸采样；稳定的 cacheKey 也让 Navigation3 重组页面时直接命中内存缓存。
 */
@Composable
internal fun ImageThumbnail(
    imageUrl: String,
    cacheKey: String,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val thumbnailPx = with(LocalDensity.current) { 130.dp.roundToPx() }
    val request = remember(imageUrl, cacheKey, thumbnailPx) {
        val data: Any = when {
            imageUrl.startsWith("file://") -> File(imageUrl.removePrefix("file://"))
            imageUrl.startsWith("/") -> File(imageUrl)
            else -> imageUrl
        }
        ImageRequest.Builder(context)
            .data(data)
            .size(thumbnailPx, thumbnailPx)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .build()
    }

    AsyncImage(
        model = request,
        contentDescription = stringResource(R.string.chat_user_image),
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(width = 130.dp, height = 130.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp),
            )
            // 与文字气泡一致的点击语义：弹出操作菜单
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
internal fun AssistantBubble(
    message: AssistantText,
    defaultExpanded: Boolean,
    autoTranslate: Boolean = false,
    live: Boolean = false,
    showRegenerate: Boolean = false,
    onRegenerate: () -> Unit = {},
    onCreateBranch: () -> Unit = {},
    onNavigateToSettings: (() -> Unit)? = null,
) {
    val reasoning = message.reasoning
    val context = LocalContext.current
    // 生成图判定：live（流式进行中）直接跳过扫描 —— 此时 base64 尚未写完，扫描必然 miss，
    // 却会在每个流式分片对整段（可能数 MB）文本跑一次 contains。改为仅在流结束后的稳定文本上判定，
    // 缓存键也随之从「含 text.length」收敛为「按 message.id」，避免键随每帧变化导致缓存永久失效。
    val generatedImagePayload = remember(message.id, live) {
        if (live) {
            false
        } else {
            val cacheKey = message.id
            generatedImageFlagCache.get(cacheKey)
                ?: message.text.contains("data:image/", ignoreCase = true).also { found ->
                    generatedImageFlagCache.put(cacheKey, found)
                }
        }
    }

    val copyAll = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_ai_response_clipboard), message.text))
        Toast.makeText(context, context.getString(R.string.chat_response_copied), Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!reasoning.isNullOrBlank()) {
            ThinkingBlock(
                id = message.id,
                reasoning = reasoning,
                defaultExpanded = defaultExpanded,
                live = live,
                durationMs = message.reasoningMs,
                autoTranslate = autoTranslate,
                onNavigateToSettings = onNavigateToSettings,
            )
        }

        if (!live && !generatedImagePayload) {
            val planSteps = remember(message.text) { extractTaskPlanSteps(message.text) }
            if (planSteps.size >= 2) {
                TaskPlanCard(steps = planSteps)
            }
        }

        if (message.text.isNotBlank()) ChatReadableMessage {
            if (live) {
                val liveImageDataStart = remember(message.text) {
                    message.text.indexOf("data:image/", ignoreCase = true)
                }
                if (liveImageDataStart >= 0) {
                    val humanPrefix = remember(message.text, liveImageDataStart) {
                        val markdownStart = message.text.lastIndexOf("![", liveImageDataStart)
                            .takeIf { it >= 0 } ?: liveImageDataStart
                        message.text.substring(0, markdownStart).trim().take(8_000)
                    }
                    if (humanPrefix.isNotBlank()) {
                        // 与流式正文同口径：图片占位之前的正文也用 Markdown，
                        // 否则同一段回复在「有图/无图」两种情况下排版不一致。
                        MarkdownText(
                            markdown = humanPrefix,
                            modifier = Modifier.fillMaxWidth(),
                            contentCacheKey = "assistant-live-img:${message.id}",
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.size(width = 260.dp, height = 180.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                        ) {
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Text(
                                text = stringResource(R.string.chat_receiving_image),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    // 流式期间也用 Markdown（增量渲染）：之前流式走纯 Text、结束后才切 MarkdownText，收尾会整段重排跳动
                    StreamingMarkdownText(
                        text = message.textContent,
                        modifier = Modifier.fillMaxWidth(),
                        contentCacheKey = "assistant-live:${message.id}",
                    )
                }
            } else {
                MarkdownText(
                    markdown = message.text,
                    modifier = Modifier.fillMaxWidth(),
                    // 缓存键只用 message.id：流式期间每帧 text.length 都会变，把长度编进键会让
                    // 缓存形同失效。大文本 LRU 在 MarkdownText 内用内容指纹校验，同一 id 换正文不会串味。
                    contentCacheKey = "assistant:${message.id}",
                )
            }
        }

        // 统计区域占满剩余宽度，操作按钮始终贴右；长统计文本可收缩省略。
        if (!live && message.text.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    message.totalMs?.let {
                        Text(
                            stringResource(R.string.chat_elapsed, formatChatDuration(it)),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    // Provider 报告的本轮 token 用量明细（输入/输出/缓存命中与命中率）。
                    val tokenParts = buildList {
                        message.promptTokens?.let { add("↑${formatTokenCount(it)}") }
                        message.completionTokens?.let { add("↓${formatTokenCount(it)}") }
                        message.cachedTokens?.takeIf { cached -> cached > 0 }?.let { cached ->
                            val prompt = message.promptTokens ?: 0
                            val pct = if (prompt > 0) ((cached.toLong() * 100L) / prompt.toLong()).toInt().coerceIn(1, 100) else null
                            if (pct != null) {
                                add("⚡${formatTokenCount(cached)} ($pct%)")
                            } else {
                                add("⚡${formatTokenCount(cached)}")
                            }
                        }
                    }

                    if (tokenParts.isNotEmpty()) {
                        Text(
                            tokenParts.joinToString(" "),
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                }

                if (showRegenerate) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(24.dp), contentDescription = stringResource(R.string.chat_regenerate)) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }

                IconButton(onClick = onCreateBranch, modifier = Modifier.size(24.dp), contentDescription = stringResource(R.string.chat_branch_from_here)) {
                    RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 复制按钮
                IconButton(onClick = copyAll, modifier = Modifier.size(24.dp), contentDescription = stringResource(R.string.chat_copy)) {
                    RuntimeIcon(
                        RuntimeIconName.Copy,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 消息反馈（自包含组件：koinInject 直取服务，不向列表/Screen 钻孔）
                if (!live) MessageFeedbackAction(message.id)
            }
        }
    }
}

private const val MAX_LIVE_TEXT_CHARS = 64_000
/** Avoid rescanning a multi-megabyte Base64 response whenever its lazy-list item re-enters composition. */
private val generatedImageFlagCache = LruCache<String, Boolean>(64)

private data class TaskStepItem(
    val index: Int,
    val title: String,
    val isCompleted: Boolean,
)

private val taskPlanCache = LruCache<String, List<TaskStepItem>>(200)

private fun extractTaskPlanSteps(text: String): List<TaskStepItem> {
    taskPlanCache.get(text)?.let { return it }
    val lines = text.lines()
    val steps = mutableListOf<TaskStepItem>()
    val checkboxRegex = Regex("""^(\s*[-*]|\s*\d+[\.\)])?\s*\[([ xX])\]\s*(.+)""")
    var idx = 1
    for (line in lines) {
        val match = checkboxRegex.find(line.trim())
        if (match != null) {
            val isChecked = match.groupValues[2].equals("x", ignoreCase = true)
            val title = match.groupValues[3].trim()
            if (title.isNotBlank()) {
                steps.add(TaskStepItem(index = idx++, title = title, isCompleted = isChecked))
            }
        }
    }
    taskPlanCache.put(text, steps)
    return steps
}

@Composable
private fun TaskPlanCard(steps: List<TaskStepItem>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(true) }
    val completedCount = steps.count { it.isCompleted }
    val progress = if (steps.isNotEmpty()) completedCount.toFloat() / steps.size else 0f
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                expanded = !expanded
            },
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Code,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                Text(
                    text = stringResource(R.string.chat_plan),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(Modifier.weight(1f))

                Surface(
                    color = if (completedCount == steps.size) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_plan_completed, completedCount, steps.size),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                        ),
                        color = if (completedCount == steps.size) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                RuntimeIcon(
                    if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 进度条
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            )

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (step.isCompleted) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (step.isCompleted) {
                                    Text(
                                        "✓",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        ),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }

                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontWeight = if (step.isCompleted) FontWeight.Normal else FontWeight.Medium,
                                ),
                                color = if (step.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ThinkingBlock(
    id: String,
    reasoning: String,
    defaultExpanded: Boolean,
    live: Boolean = false,
    durationMs: Long? = null,
    autoTranslate: Boolean = false,
    onNavigateToSettings: (() -> Unit)? = null,
) {
    var expanded by rememberSaveable(id) { mutableStateOf(defaultExpanded) }
    var showTranslation by rememberSaveable(id) { mutableStateOf(false) }
    var translatedText by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var isTranslating by remember { mutableStateOf(false) }
    var showMissingModelDialog by rememberSaveable(id) { mutableStateOf(false) }
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val translationManager = remember {
        GlobalContext.getOrNull()
            ?.getOrNull<TranslationManager>()
    }
    val globalNavigationBus = remember {
        GlobalContext.getOrNull()
            ?.getOrNull<GlobalNavigationBus>()
    }
    val fallbackStatusFlow = remember {
        MutableStateFlow<TranslationModelStatus>(
            TranslationModelStatus.Checking
        )
    }
    val translationModelStatus by (translationManager?.status ?: fallbackStatusFlow)
        .collectAsStateWithLifecycle()
    LaunchedEffect(expanded, autoTranslate) {
        if (expanded && autoTranslate && !live && !showTranslation && translatedText == null) {
            if (translationManager?.isReady() == true) {
                isTranslating = true
                val result = translationManager.translate(reasoning)
                if (result.isSuccess) {
                    translatedText = result.getOrNull()
                    showTranslation = true
                }
                isTranslating = false
            }
        }
    }
    val copyToClipboard = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val textToCopy = if (showTranslation && translatedText != null) translatedText else reasoning
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.chat_reasoning_clipboard), textToCopy))
        Toast.makeText(context, context.getString(R.string.chat_reasoning_copied), Toast.LENGTH_SHORT).show()
    }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        containerColor = Color.Transparent, contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .clickable { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); expanded = !expanded },
        ) {
            Box(
                modifier = Modifier.size(18.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (live) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    RuntimeIcon(
                        RuntimeIconName.Brain,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = when {
                    live -> stringResource(R.string.chat_deep_reasoning)
                    durationMs != null && durationMs > 0 ->
                        stringResource(R.string.chat_reasoning_duration, "%.1f".format(durationMs / 1000.0))
                    else -> stringResource(R.string.chat_reasoning_process)
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (expanded) {
                if (!live) {
                    IconButton(
                        onClick = {
                            val isReady = translationManager?.isReady() == true
                            if (!isReady) {
                                showMissingModelDialog = true
                            } else {
                                if (showTranslation) {
                                    showTranslation = false
                                } else if (translatedText != null) {
                                    showTranslation = true
                                } else {
                                    scope.launch {
                                        isTranslating = true
                                        val result = translationManager.translate(reasoning)
                                        if (result.isSuccess) {
                                            translatedText = result.getOrNull()
                                            showTranslation = true
                                        } else {
                                            Toast.makeText(
                                                context,
                                                result.exceptionOrNull()?.message ?: "离线翻译失败",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                        isTranslating = false
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(18.dp),
                        contentDescription = if (showTranslation) "查看英文原文" else "离线翻译思考",
                    ) {
                        if (isTranslating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            RuntimeIcon(
                                RuntimeIconName.Globe,
                                Modifier.size(18.dp),
                                tint = if (showTranslation) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                IconButton(
                    onClick = copyToClipboard,
                    modifier = Modifier.size(18.dp),
                    contentDescription = stringResource(R.string.chat_copy_reasoning),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Copy,
                        Modifier.size(18.dp),
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RuntimeIcon(
                if (expanded) RuntimeIconName.ChevronDown else RuntimeIconName.ChevronRight,
                Modifier.size(18.dp),
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        expanded = false
                    }
                    .padding(top = 12.dp, bottom = 4.dp),
            ) {
                if (showTranslation && translatedText != null) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(bottom = 2.dp),
                        ) {
                            RuntimeIcon(
                                RuntimeIconName.Globe,
                                Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "中文译文 (ML Kit 离线生成)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        MarkdownText(
                            translatedText.orEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else if (isTranslating) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(vertical = 4.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "正在离线翻译思考内容...",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                } else {
                    if (live) {
                        SelectionContainer {
                            Text(
                                text = reasoning,
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    } else {
                        MarkdownText(
                            reasoning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showMissingModelDialog) {
        val isDownloading = translationModelStatus is TranslationModelStatus.Downloading
        val downloadingStatus = translationModelStatus as? TranslationModelStatus.Downloading
        RuntimeAlertDialog(
            onDismissRequest = { showMissingModelDialog = false },
            title = {
                Text(
                    text = if (isDownloading) "离线翻译模型下载中" else "未下载离线翻译语种包",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            text = {
                Text(
                    text = if (isDownloading && downloadingStatus != null) {
                        "离线翻译语种模型正在后台下载中（${downloadingStatus.stepName}，${downloadingStatus.detailText}）。\n\n下载完成后将自动就绪支持离线翻译，您可前往「智能体设置」查看详细进度。"
                    } else {
                        "本地英语-中文翻译模型（约 60MB）尚未下载。请前往「Agent 设置」完成语种模型下载后方可离线翻译思考流内容。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMissingModelDialog = false
                        if (onNavigateToSettings != null) {
                            onNavigateToSettings()
                        } else {
                            globalNavigationBus?.navigateTo(AppNavigationTarget.AgentSettings)
                        }
                    }
                ) {
                    Text(if (isDownloading) "查看下载进度" else "前往设置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMissingModelDialog = false }) {
                    Text(if (isDownloading) "后台继续" else "取消")
                }
            },
        )
    }
}

/**
 * 技能进化建议卡片（对话后技能沉淀/进化）：
 * 创建新技能 / 更新既有技能 / 忽略，动作完成后由上层隐藏卡片。
 */
@Composable
internal fun SkillSuggestionCard(
    suggestion: SkillSuggestion,
    onCreate: () -> Unit,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isUpdate = suggestion.action == "update"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RuntimeIcon(
                    RuntimeIconName.Sparkles,
                    Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    if (isUpdate) "技能进化建议 · 修复既有技能" else "技能进化建议 · 沉淀为新技能",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                suggestion.skillName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (suggestion.description.isNotBlank()) {
                Text(
                    suggestion.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (suggestion.reason.isNotBlank()) {
                Text(
                    "依据：" + suggestion.reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (isUpdate) {
                    OutlinedButton(
                        onClick = onUpdate,
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text("更新技能", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }
                }
                Button(
                    onClick = onCreate,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    Text(if (isUpdate) "另存为新技能" else "创建技能", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text("忽略", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun CapabilityEventCard(event: CapabilityEvent) {
    val isSkill = event.kind == CapabilityEvent.Kind.SKILL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            RuntimeIcon(
                if (isSkill) RuntimeIconName.BookOpen else RuntimeIconName.Extension,
                Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
            )
        }
        Text(
            stringResource(
                if (isSkill) R.string.chat_capability_skill_fmt else R.string.chat_capability_mcp_fmt,
                event.name,
            ),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MaterialTheme.colorScheme.primary,
        )
        if (event.details.isNotBlank()) {
            Text(
                "· ${event.details}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun ModelSwitchCard(event: ModelSwitchEvent) {
    val toWindow = formatContextWindow(event.toContextTokens)
    val title = if (event.fromLabel.isBlank()) {
        stringResource(R.string.chat_model_switch_to_fmt, event.toLabel)
    } else {
        stringResource(R.string.chat_model_switch_from_to_fmt, event.fromLabel, event.toLabel)
    }
    val subtitle = when {
        event.compacted && event.foldedMessageCount > 0 ->
            stringResource(R.string.chat_model_switch_compacted_fmt, toWindow, event.foldedMessageCount)
        event.compactionPending ->
            stringResource(R.string.chat_model_switch_pending_fmt, toWindow)
        else -> stringResource(R.string.chat_model_switch_window_fmt, toWindow)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.widthIn(max = 260.dp),
            ) {
                RuntimeIcon(
                    RuntimeIconName.Bot,
                    Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
            )
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.fillMaxWidth(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
