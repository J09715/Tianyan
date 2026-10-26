package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import top.tianyan.app.ui.components.RuntimeCard
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.harness.HarnessMessage
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.theme.semanticColors
import top.tianyan.app.ui.components.RuntimeIconName
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.BasicTextField
import top.tianyan.app.harness.QueuedPrompt

/**
 * 输入区：斜杠/@ 弹窗、排队指令、附件预览、推理强度滑块、工具状态胶囊与一体化输入胶囊。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
internal fun ChatComposer(
    listState: LazyListState,
    running: Boolean,
    initializing: Boolean,
    status: String?,
    messages: List<HarnessMessage>,
    toolResults: Map<String, ToolResult>,
    workspace: String,
    input: String,
    onInputChanged: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    sendMode: ComposerSendMode,
    matchingCommands: List<SlashCommandItem>,
    onApplyCommand: (SlashCommandItem) -> Unit,
    matchingMentions: List<MentionItem>,
    onApplyMention: (MentionItem) -> Unit,
    attachments: List<ChatAttachment>,
    attachmentsProcessing: Boolean,
    onAttachmentsPicked: (List<Uri>, Boolean) -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    queuedPrompts: List<QueuedPrompt>,
    onEditQueuedPrompt: (QueuedPrompt) -> Unit,
    onRemoveQueuedPrompt: (QueuedPrompt) -> Unit,
    onConvertToSteer: (QueuedPrompt) -> Unit,
    knownMentionNames: List<String>,
    attachedMentions: List<MentionItem>,
    onRemoveMention: (MentionItem) -> Unit,
    pinnedCapabilities: List<MentionItem>,
    onUnpinMention: (String) -> Unit,
    onOpenSkillsMcp: () -> Unit,
    onOpenModels: () -> Unit,
    activeModel: AiModelEntity?,
    approvalMode: ApprovalMode, onOpenApprovalModes: () -> Unit,
    onUpdateReasoning: (String?, String?) -> Unit = { _, _ -> },
    contextUsage: ContextUsage,
) {

    val focusRequester = remember { FocusRequester() }
    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(input, TextRange(input.length)))
    }

    LaunchedEffect(input) {
        if (textFieldValue.text != input) {
            textFieldValue = TextFieldValue(
                text = input,
                selection = TextRange(input.length),
            )
            if (input.isNotEmpty()) {
                runCatching { focusRequester.requestFocus() }
            }
        }
    }

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        onAttachmentsPicked(uris.toList(), true)
    }

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        onAttachmentsPicked(uris.toList(), false)
    }

    val coroutineScope = rememberCoroutineScope()

    val doSend = {
        // 初始化（会话尚未恢复）期间发送目标未定，禁止误发
        if (!initializing && (input.isNotBlank() || attachments.isNotEmpty())) {
            onSend()
            coroutineScope.launch {
                delay(60)
                listState.safeScrollToLastItem(animated = true)
            }
        }
    }

    // 斜杠指令快捷提示弹窗
    if (matchingCommands.isNotEmpty()) {
        SlashCommandPopup(
            commands = matchingCommands,
            onSelect = onApplyCommand,
        )
    }

    // @ 艾特精准挂载快捷弹窗 (Skills & MCP)
    if (matchingMentions.isNotEmpty()) {
        MentionPopup(
            mentions = matchingMentions,
            onSelect = onApplyMention,
        )
    }

    QueuedPromptStack(
        prompts = queuedPrompts,
        onEdit = onEditQueuedPrompt,
        onRemove = onRemoveQueuedPrompt,
        onConvertToSteer = onConvertToSteer,
    )

    // 待发送附件预览栏
    AttachmentPreviewRow(
        attachments = attachments,
        onRemove = onRemoveAttachment,
    )

    // 附件处理中（复制/压缩/编码）加载指示
    if (attachmentsProcessing) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Text(
                stringResource(R.string.chat_attachment_processing),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    RuntimeCard(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge, contentPadding = PaddingValues(0.dp),
        containerColor = top.tianyan.app.ui.theme.chatBackdropSurface(MaterialTheme.colorScheme.surfaceContainer),
        borderColor = if (running) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else Color.Transparent,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 显式挂载的能力才展示标签；常驻能力从 + 菜单管理。
            val allDisplayItems = remember(pinnedCapabilities, attachedMentions) {
                attachedMentions.distinctBy { it.id }
            }
            if (allDisplayItems.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(allDisplayItems.size, key = { allDisplayItems[it].id }) { index ->
                        val item = allDisplayItems[index]
                        val isSkill = item.type == MentionType.SKILL
                        val isAttached = attachedMentions.any { it.id == item.id }
                        val isPinned = pinnedCapabilities.any { it.id == item.id }
                        val tagBg = if (isSkill) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.tertiaryContainer
                        val tagColor = if (isSkill) MaterialTheme.colorScheme.onPrimaryContainer
                                       else MaterialTheme.colorScheme.onTertiaryContainer

                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = tagBg,
                            modifier = Modifier.clickable {
                                if (isAttached) {
                                    onRemoveMention(item)
                                } else if (isPinned) {
                                    onUnpinMention(item.id)
                                }
                            },
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(
                                    item.icon,
                                    Modifier.size(18.dp),
                                    tint = tagColor,
                                )
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = tagColor,
                                )
                                if (isPinned) {
                                    Surface(
                                        color = tagColor.copy(alpha = 0.18f),
                                        shape = MaterialTheme.shapes.extraSmall,
                                    ) {
                                        Text(
                                            stringResource(R.string.chat_pinned_badge),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = tagColor,
                                        )
                                    }
                                }
                                RuntimeIcon(
                                    RuntimeIconName.Close,
                                    Modifier.size(18.dp),
                                    tint = tagColor,
                                )
                            }
                        }
                    }

                    item {
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.clickable { onOpenSkillsMcp() },
                        ) {
                            Row(
                                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Plus,
                                    Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    stringResource(R.string.chat_attach_button),
                                    style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // 🌟 上排主体：弹性文本输入框
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (input.isEmpty()) {
                    Text(
                        text = stringResource(if (running) R.string.chat_input_running else R.string.chat_message_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        val updatedValue = if (newValue.text.length < textFieldValue.text.length) {
                            val oldText = textFieldValue.text
                            val deletedPos = newValue.selection.start
                            val baseRegex = buildMentionRegex(knownMentionNames)
                            val regex = Regex("""(${baseRegex.pattern})\s*""")
                            var handled: TextFieldValue? = null
                            for (match in regex.findAll(oldText)) {
                                val range = match.range
                                if (deletedPos in range.first until (range.last + 1)) {
                                    val before = oldText.substring(0, range.first)
                                    val after = oldText.substring((range.last + 1).coerceAtMost(oldText.length))
                                    val resultText = before + after
                                    val newCursor = range.first.coerceAtMost(resultText.length)
                                    handled = TextFieldValue(resultText, TextRange(newCursor))
                                    break
                                }
                            }
                            handled ?: newValue
                        } else {
                            newValue
                        }
                        textFieldValue = updatedValue
                        if (updatedValue.text != input) {
                            onInputChanged(updatedValue.text)
                        }
                    },
                    visualTransformation = run {
                        val mentionColor = MaterialTheme.colorScheme.primary
                        val mentionBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        remember(knownMentionNames, mentionColor, mentionBg) {
                            MentionVisualTransformation(knownMentionNames, mentionColor, mentionBg)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                )
            }

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp),
                itemVerticalAlignment = Alignment.CenterVertically, verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ComposerQuickActions(
                    approvalMode = approvalMode, onOpenApprovalModes = onOpenApprovalModes,
                    activeModel = activeModel,
                    onOpenModels = onOpenModels,
                    onPickImage = { imagePickerLauncher.launch("image/*") },
                    onPickFile = { filePickerLauncher.launch("*/*") },
                )
                // 右侧发送 / 停止控制区。
                val canSend = !initializing && (input.isNotBlank() || attachments.isNotEmpty())
                if (running) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ContextUsageRing(contextUsage)
                        if (canSend) {
                            val sendTint = when (sendMode) {
                                ComposerSendMode.STEER -> semanticColors().agent
                                ComposerSendMode.NEXT_RUN -> MaterialTheme.colorScheme.secondary
                            }
                            ComposerSendButton(onClick = doSend, enabled = true, tint = sendTint)
                        }

                        run {
                            val stopDesc = stringResource(R.string.chat_stop_generation)
                            Surface(
                                onClick = onStop,
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .size(48.dp)
                                    .semantics { contentDescription = stopDesc },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    RuntimeIcon(
                                        name = RuntimeIconName.Stop,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ContextUsageRing(contextUsage)
                        ComposerSendButton(onClick = doSend, enabled = canSend, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
