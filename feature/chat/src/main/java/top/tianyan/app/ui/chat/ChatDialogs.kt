package top.tianyan.app.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import top.tianyan.app.ui.components.RuntimeAlertDialog
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import top.tianyan.app.feature.chat.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.ui.components.ProviderBadge

/** 会话 / 模型 / 新建会话对话框。 */
@Composable
internal fun EditAndResendDialog(
    originalText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember(originalText) { mutableStateOf(originalText) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_edit_resend), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.chat_user_request_clipboard)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.chat_resend_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.chat_send), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun SessionsDialog(
    sessions: List<HarnessSessionEntity>,
    currentSessionId: String,
    sessionRunStates: Map<String, SessionRunState>,
    onDismiss: () -> Unit,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
) {
    // 重命名 / 删除目标只保存 id，旋转后可从 sessions 恢复
    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.List, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.chat_session_manager), fontWeight = FontWeight.Bold)
                }
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        stringResource(R.string.chat_session_count, sessions.size),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sessions.size == 1) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Alert, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                stringResource(R.string.chat_last_session_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions.size, key = { sessions[it].id }) { index ->
                        val session = sessions[index]
                        val isCurrent = session.id == currentSessionId
                        val runState = sessionRunStates[session.id] ?: SessionRunState.IDLE

                        val (dotColor, stateLabel) = when (runState) {
                            SessionRunState.RUNNING -> Color(0xFFF59E0B) to stringResource(R.string.chat_state_running)
                            SessionRunState.WAITING_APPROVAL -> Color(0xFF8B5CF6) to stringResource(R.string.chat_state_approval)
                            SessionRunState.FAILED -> Color(0xFFEF4444) to stringResource(R.string.chat_state_failed)
                            SessionRunState.COMPLETED -> Color(0xFF10B981) to stringResource(R.string.chat_state_completed)
                            SessionRunState.IDLE -> Color(0xFF10B981) to stringResource(R.string.chat_state_ready)
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isCurrent) MaterialTheme.colorScheme.surfaceContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow,
                            border = androidx.compose.foundation.BorderStroke(
                                if (isCurrent) 1.5.dp else 1.dp,
                                if (isCurrent) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSwitch(session.id) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                // 状态指示圆点（绿色完成/橙色进行中/红色失败）
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(dotColor),
                                )

                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            session.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (isCurrent) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.chat_current),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                )
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Surface(
                                            color = dotColor.copy(alpha = 0.12f),
                                            shape = RoundedCornerShape(4.dp),
                                        ) {
                                            Text(
                                                stateLabel,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                ),
                                                color = dotColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            )
                                        }

                                        if (session.workspace.isNotBlank()) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                shape = RoundedCornerShape(4.dp),
                                            ) {
                                                Text(
                                                    session.workspace,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        } else {
                                            Text(
                                                stringResource(R.string.chat_isolated_sandbox),
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                IconButton(
                                    onClick = { renameTargetId = session.id },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                    contentDescription = stringResource(R.string.chat_rename_session_button),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Settings, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(
                                    onClick = { deleteTargetId = session.id },
                                    modifier = Modifier.minimumInteractiveComponentSize(),
                                    contentDescription = stringResource(R.string.chat_delete_session),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onNew, shape = RoundedCornerShape(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(16.dp), MaterialTheme.colorScheme.onPrimary)
                    Text(stringResource(R.string.chat_new_session))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )

    val renameTarget = renameTargetId?.let { id -> sessions.firstOrNull { it.id == id } }
    renameTarget?.let { target ->
        RenameSessionDialog(
            currentTitle = target.title,
            onDismiss = { renameTargetId = null },
            onRename = { title ->
                onRename(target.id, title)
                renameTargetId = null
            },
        )
    }

    // 删除会话二次确认（运行中会话也提示，避免误删不可恢复）
    deleteTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_session_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.chat_delete_session_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTargetId = null
                        onDelete(targetId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    ),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTargetId = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
internal fun RenameSessionDialog(
    currentTitle: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var title by remember(currentTitle) { mutableStateOf(currentTitle) }
    val titleBlank = title.isBlank()
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_session), fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.chat_title_label)) },
                singleLine = true,
                isError = titleBlank,
                supportingText = {
                    if (titleBlank) {
                        Text(
                            stringResource(R.string.chat_title_required),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(title) },
                enabled = !titleBlank,
            ) { Text(stringResource(R.string.chat_save), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) } },
    )
}

@Composable
internal fun ModelDialog(
    models: List<AiModelEntity>,
    selectedProfileId: String?,
    selectedModelVariant: String?,
    providerModelIds: List<String>,
    discoveringProviderModels: Boolean,
    providerModelDiscoveryError: String?,
    modelPickerProfileId: String?,
    onDismiss: () -> Unit,
    onSelectProfile: (String) -> Unit,
    onSelectSubModel: (profileId: String, subModel: String) -> Unit,
    onOpenModelPicker: (String) -> Unit,
    onCloseModelPicker: () -> Unit,
    onRefreshModels: (String) -> Unit,
    onSwitchModel: (profileId: String, modelId: String) -> Unit,
    onAdd: (String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showAdd by rememberSaveable { mutableStateOf(false) }
    // 删除模型档案为破坏性操作，先记录目标 id 并经确认对话框
    var deleteModelTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    if (showAdd) {
        AddModelDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, provider, model, baseUrl ->
                onAdd(name, provider, model, baseUrl)
                showAdd = false
            },
        )
    }
    val pickingProfile = modelPickerProfileId?.let { id -> models.firstOrNull { it.id == id } }
    if (pickingProfile != null) {
        ProviderModelPickerDialog(
            profile = pickingProfile,
            selectedModelVariant = selectedModelVariant.takeIf { pickingProfile.id == selectedProfileId },
            modelIds = providerModelIds,
            discovering = discoveringProviderModels,
            error = providerModelDiscoveryError,
            onDismiss = onCloseModelPicker,
            onRefresh = { onRefreshModels(pickingProfile.id) },
            onSelect = { modelId -> onSwitchModel(pickingProfile.id, modelId) },
        )
        return
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_select_model), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.chat_select_provider_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.isEmpty()) {
                    Text(
                        stringResource(R.string.chat_no_models),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(models, key = { it.id }) { model ->
                    val subModels = model.model.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(model.model) }
                    val isProfileSelected = model.id == selectedProfileId
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.small)
                            .background(
                                if (isProfileSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .border(
                                1.dp,
                                if (isProfileSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelectProfile(model.id) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ProviderBadge(
                                providerIdOrName = model.provider,
                                size = 26.dp,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    model.name.ifBlank { model.provider },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    model.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (isProfileSelected) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(6.dp),
                                ) {
                                    Text(
                                        stringResource(R.string.chat_current),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = { deleteModelTargetId = model.id },
                                modifier = Modifier.minimumInteractiveComponentSize(),
                                contentDescription = stringResource(R.string.chat_delete_model_profile),
                            ) {
                                RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                            }
                        }

                        if (subModels.size > 1) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                subModels.forEach { subModel ->
                                    val isSubActive = isProfileSelected && subModel == selectedModelVariant
                                    Surface(
                                        color = if (isSubActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerHighest,
                                        shape = RoundedCornerShape(8.dp),
                                        border = if (isSubActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                                        modifier = Modifier.clickable {
                                            onSelectSubModel(model.id, subModel)
                                            onDismiss()
                                        },
                                    ) {
                                        Text(
                                            subModel,
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isSubActive) FontWeight.Bold else FontWeight.Normal,
                                            ),
                                            color = if (isSubActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (subModels.size <= 1) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        model.model.ifBlank { stringResource(R.string.chat_model_not_set) },
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                            Surface(
                                onClick = { onOpenModelPicker(model.id) },
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Tune, Modifier.size(14.dp), MaterialTheme.colorScheme.onSecondaryContainer)
                                    Text(
                                        stringResource(R.string.chat_switch_model),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                    }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { showAdd = true }) { Text(stringResource(R.string.chat_add_model), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_close)) }
        },
    )

    // 删除模型档案二次确认
    deleteModelTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteModelTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_model_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    stringResource(R.string.chat_delete_model_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteModelTargetId = null
                        onDelete(targetId)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        disabledContainerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.3f),
                    ),
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteModelTargetId = null }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}

@Composable
internal fun ProviderModelPickerDialog(
    profile: AiModelEntity,
    selectedModelVariant: String?,
    modelIds: List<String>,
    discovering: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    var manualId by remember(profile.id, profile.model, selectedModelVariant) {
        mutableStateOf(selectedModelVariant ?: profile.model.substringBefore(',').trim())
    }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.chat_switch_model_title), fontWeight = FontWeight.Bold)
                Text(
                    profile.name.ifBlank { profile.provider },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.chat_switch_model_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    discovering -> {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.chat_model_discovering),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    error != null && modelIds.isEmpty() -> {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    modelIds.isNotEmpty() -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(modelIds, key = { it }) { modelId ->
                                val selected = modelId == selectedModelVariant
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceContainerLow,
                                        )
                                        .clickable { onSelect(modelId) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        modelId,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (selected) {
                                        RuntimeIcon(RuntimeIconName.Check, Modifier.size(16.dp), MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
                if (!error.isNullOrBlank() && modelIds.isNotEmpty()) {
                    Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = manualId,
                    onValueChange = { manualId = it },
                    label = { Text(stringResource(R.string.chat_model_id)) },
                    placeholder = { Text("gpt-4o / deepseek-chat") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefresh, enabled = !discovering) {
                    Text(stringResource(R.string.chat_refresh_models), color = MaterialTheme.colorScheme.primary)
                }
                TextButton(
                    onClick = { onSelect(manualId) },
                    enabled = manualId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.chat_apply_model), color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun AddModelDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val defaultProvider = stringResource(R.string.chat_custom_provider)
    var provider by rememberSaveable { mutableStateOf(defaultProvider) }
    var model by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    // baseUrl 基础格式校验：必须以 http:// 或 https:// 开头（留空走供应商默认端点）
    val baseUrlInvalid = baseUrl.isNotBlank() && !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://")
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_add_model), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.chat_optional_name)) }, singleLine = true)
                OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text(stringResource(R.string.chat_provider_label)) }, singleLine = true)
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.chat_optional_base_url)) },
                    placeholder = { Text("https://api.openai.com/v1") },
                    isError = baseUrlInvalid,
                    supportingText = {
                        if (baseUrlInvalid) {
                            Text(
                                stringResource(R.string.chat_base_url_invalid),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                    singleLine = true,
                )
                OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text(stringResource(R.string.chat_model_id)) }, placeholder = { Text("deepseek-chat / gpt-4o") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, provider, model, baseUrl) },
                enabled = model.isNotBlank() && !baseUrlInvalid,
            ) { Text(stringResource(R.string.chat_confirm_add), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

@Composable
internal fun NewSessionDialog(
    workspaces: List<WorkspaceProject>,
    onDismiss: () -> Unit,
    onCreate: (title: String, workspace: String, projectType: ProjectType, agentId: String?) -> Unit,
    agents: List<top.tianyan.app.core.model.AgentSubagent> = emptyList(),
    engines: List<top.tianyan.app.harness.acp.AgentEngineProfile> = emptyList(),
) {
    val defaultTitle = stringResource(R.string.chat_new_session)
    // 表单状态旋转后保留；枚举以 name 字符串保存
    var title by rememberSaveable { mutableStateOf(defaultTitle) }
    var selected by rememberSaveable { mutableStateOf("") }
    var selectedTypeName by rememberSaveable { mutableStateOf(ProjectType.GENERAL.name) }
    val selectedType = ProjectType.valueOf(selectedTypeName)
    // 用户是否亲手点过工程类型。点过之后不再被工作区自动识别覆盖 ——
    // 否则「先选红队演练、再关联一个 Android 工程」会把类型悄悄换回 Android，
    // 建出来的会话与用户看到的不一致。
    var typeExplicit by rememberSaveable { mutableStateOf(false) }
    // 智能体绑定：空串表示沿用默认天衍本体；记住的是 agent id，不是索引，
    // 这样列表在对话框打开期间变化（设置页增删）也不会错绑。
    var selectedAgentId by rememberSaveable { mutableStateOf("") }
    var agentMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val quickTags = listOf(
        defaultTitle,
        stringResource(R.string.chat_quick_bug),
        stringResource(R.string.chat_quick_feature),
        stringResource(R.string.chat_quick_environment),
        stringResource(R.string.chat_quick_refactor),
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(18.dp), tint = LinearAccent)
                    Text(
                        stringResource(R.string.chat_new_agent_session),
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    "会话建立后工程类型与智能体固定，用于确定工具集与提示词分层",
                    style = MaterialTheme.typography.labelSmall,
                    color = LinearMuted,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 470.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                LinearFieldLabel(stringResource(R.string.chat_session_title))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text(stringResource(R.string.chat_session_name_hint)) },
                    singleLine = true,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 快捷预设标题标签：Linear 的 Pill —— 透明底 + 细边框，选中才上色。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    quickTags.forEach { tag ->
                        val active = title == tag
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = if (active) LinearAccent.copy(alpha = 0.16f) else Color.Transparent,
                            border = BorderStroke(1.dp, if (active) LinearAccent.copy(alpha = 0.55f) else LinearBorder),
                            modifier = Modifier.clickable { title = tag },
                        ) {
                            Text(
                                tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) LinearAccent else LinearSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                HorizontalDivider(color = LinearBorder)

                LinearFieldLabel(stringResource(R.string.chat_link_workspace))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    WorkspaceOption(stringResource(R.string.chat_no_workspace), "/root", selected == "", onSelect = {
                        selected = ""
                        if (!typeExplicit) selectedTypeName = ProjectType.GENERAL.name
                    })
                    workspaces.forEach { ws ->
                        WorkspaceOption(ws.name, ws.linuxPath, selected == ws.linuxPath) {
                            selected = ws.linuxPath
                            if (!typeExplicit) selectedTypeName = ws.projectType.name
                        }
                    }
                }

                HorizontalDivider(color = LinearBorder)

                // 工程类型即模式：红队演练是列表里的一项，而不是另挂一个开关。
                // 一个会话只能有一种类型，界面上不可能选出「红队 + 通用」这种矛盾组合。
                val selectedProject = workspaces.firstOrNull { it.linuxPath == selected }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LinearFieldLabel(stringResource(R.string.chat_agent_project_type))
                    Text(
                        if (selectedProject == null || selectedProject.projectType == ProjectType.GENERAL) {
                            stringResource(R.string.chat_project_type_manual)
                        } else {
                            stringResource(R.string.chat_project_type_detected)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = LinearMuted,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProjectTypeOptions.chunked(2).forEach { rowTypes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            rowTypes.forEach { option ->
                                val active = selectedType == option.type
                                Surface(
                                    color = if (active) option.accent.copy(alpha = 0.14f) else LinearSurface,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, if (active) option.accent.copy(alpha = 0.6f) else LinearBorder),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            selectedTypeName = option.type.name
                                            typeExplicit = true
                                        },
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        RuntimeIcon(
                                            option.icon,
                                            Modifier.size(18.dp),
                                            tint = if (active) option.accent else LinearMuted,
                                        )
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                option.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Medium,
                                                color = if (active) MaterialTheme.colorScheme.onSurface else LinearSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                option.hint,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = LinearMuted,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (active) {
                                            RuntimeIcon(RuntimeIconName.Check, Modifier.size(14.dp), tint = option.accent)
                                        }
                                    }
                                }
                            }
                            repeat(2 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (selectedType == ProjectType.RED_TEAM) {
                    Surface(
                        color = LinearDanger.copy(alpha = 0.10f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, LinearDanger.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            RuntimeIcon(RuntimeIconName.Shield, Modifier.size(16.dp), tint = LinearDanger)
                            Text(
                                "红队演练会话：建立后需在会话内绑定目标与授权范围，所有探测动作受该范围约束；" +
                                    "目标与事实只属于本会话，其他会话不可见。",
                                style = MaterialTheme.typography.labelSmall,
                                color = LinearSecondary,
                            )
                        }
                    }
                }

                HorizontalDivider(color = LinearBorder)

                AgentPicker(
                    agents = agents,
                    engines = engines,
                    selectedAgentId = selectedAgentId,
                    expanded = agentMenuExpanded,
                    onExpandedChange = { agentMenuExpanded = it },
                    onSelect = { id ->
                        selectedAgentId = id
                        agentMenuExpanded = false
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        title.ifBlank { defaultTitle },
                        selected,
                        selectedType,
                        selectedAgentId.takeIf { it.isNotBlank() },
                    )
                },
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(stringResource(R.string.chat_create_session))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_cancel)) }
        },
    )
}

// ── Linear 设计令牌 ───────────────────────────────────────────────────────────
// 深色原生：面板 #0f1011、抬升表面 #191a1b、半透明细边框，唯一彩色是靛紫强调。
// 这里不引 MaterialTheme 的容器色，因为对话框自带玻璃背板，语义色叠上去会发灰。
private val LinearAccent = Color(0xFF7170FF)
private val LinearDanger = Color(0xFFB3261E)
private val LinearBorder = Color(0x14FFFFFF)
private val LinearSurface = Color(0x0AFFFFFF)
private val LinearSecondary = Color(0xFFD0D6E0)
private val LinearMuted = Color(0xFF8A8F98)

@Composable
private fun LinearFieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = LinearSecondary,
    )
}

private data class ProjectTypeOption(
    val type: ProjectType,
    val title: String,
    val hint: String,
    val icon: RuntimeIconName,
    val accent: Color,
)

/** 工程类型清单：红队演练与 Android / Flutter / 逆向并列，是同一层的选择。 */
private val ProjectTypeOptions = listOf(
    ProjectTypeOption(ProjectType.ANDROID, "Android", "Gradle 构建与安装", RuntimeIconName.Android, Color(0xFF2E7D32)),
    ProjectTypeOption(ProjectType.FLUTTER, "Flutter", "Flutter 构建与安装", RuntimeIconName.Flutter, Color(0xFF0288D1)),
    ProjectTypeOption(ProjectType.REVERSE, "APK 逆向", "jadx / apktool 分析", RuntimeIconName.Reverse, Color(0xFF6A1B9A)),
    ProjectTypeOption(ProjectType.RED_TEAM, "红队演练", "绑定目标与作战状态", RuntimeIconName.Shield, Color(0xFFB3261E)),
    ProjectTypeOption(ProjectType.GENERAL, "通用", "自定义命令与脚本", RuntimeIconName.Code, Color(0xFF7170FF)),
)

/**
 * 新建会话的智能体选择器。
 *
 * 两类绑定，共用同一个 agentId：
 * - **引擎**（Claude Code / Codex / DSH 等）：真实进程，自带工具循环，天衍退回宿主；
 * - **人格**（Agency 目录）：只改天衍本体的系统提示词，执行仍由天衍自己做。
 * 分组展示是为了不让用户把「换个说法」和「换套引擎」当成同一件事。
 *
 * 交互取舍：用下拉而非横向 chip 列表——启用项可达数十个，横向 chip 会挤成
 * 一条无法定位的长条。菜单用带权重的 LazyColumn 并给整列留出滚动余量，
 * 否则项目一多菜单会顶出对话框边缘，用户看到的就是「只能看到一半字体」。
 *
 * 被禁用的智能体仍然列出但置灰：用户能看见「我有这个角色，只是关了」，
 * 而不是以为它消失了。
 */
@Composable
private fun AgentPicker(
    agents: List<top.tianyan.app.core.model.AgentSubagent>,
    engines: List<top.tianyan.app.harness.acp.AgentEngineProfile>,
    selectedAgentId: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (String) -> Unit,
) {
    val enabled = remember(agents) { agents.filter { it.isEnabled } }
    val selectedEngine = remember(engines, selectedAgentId) { engines.firstOrNull { it.id == selectedAgentId } }
    val selected = remember(enabled, selectedAgentId) { enabled.firstOrNull { it.id == selectedAgentId } }
    val ordered = remember(enabled) {
        enabled.sortedWith(
            compareBy(
                { top.tianyan.app.core.model.AgentDepartments.find(it.departmentId).sortOrder },
                { it.sortOrder },
                { it.name },
            ),
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "智能体",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "（会话身份，建立后固定）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(true) },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Bot,
                        Modifier.size(18.dp),
                        tint = if (selected != null || selectedEngine != null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    Column(Modifier.weight(1f)) {
                        Text(
                            selectedEngine?.name ?: selected?.name ?: "默认（天衍本体）",
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = when {
                            selectedEngine != null -> "引擎 · ${selectedEngine.description}"
                            selected != null ->
                                "人格 · ${top.tianyan.app.core.model.AgentDepartments.find(selected.departmentId).localizedName} · ${selected.description}"
                            else -> "不绑定智能体，使用天衍默认提示词与执行循环"
                        }
                        Text(
                            sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        stringResource(R.string.chat_select),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier.heightIn(max = 420.dp),
            ) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("默认（天衍本体）", fontWeight = FontWeight.SemiBold)
                            Text(
                                "不绑定智能体，使用天衍默认提示词",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = { onSelect("") },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (engines.isNotEmpty()) {
                    PickerSectionLabel("引擎（真实进程，接管执行）")
                    engines.forEach { engine ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(
                                            engine.name,
                                            fontWeight = if (engine.id == selectedAgentId) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "引擎",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                    Text(
                                        engine.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            onClick = { onSelect(engine.id) },
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                if (ordered.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "暂无已启用的人格智能体（到设置 → 智能体目录开启）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = { onExpandedChange(false) },
                    )
                }
                if (ordered.isNotEmpty()) {
                    PickerSectionLabel("人格（只改提示词，执行仍是天衍本体）")
                }
                ordered.forEach { agent ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        agent.name,
                                        fontWeight = if (agent.id == selectedAgentId) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        top.tianyan.app.core.model.AgentDepartments.find(agent.departmentId).localizedName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    agent.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        onClick = { onSelect(agent.id) },
                    )
                }
            }
        }
    }
}

/**
 * 下拉菜单里的分组小标题。
 *
 * 用非交互行而非 DropdownMenuItem：分组标题不该可点击（点它没有语义），
 * 也不该抢走菜单的键盘焦点。
 */
@Composable
private fun PickerSectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 2.dp),
    )
}

@Composable
internal fun WorkspaceOption(name: String, path: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .background(
                if (selected) LinearAccent.copy(alpha = 0.10f) else LinearSurface,
                RoundedCornerShape(8.dp),
            )
            .border(
                1.dp,
                if (selected) LinearAccent.copy(alpha = 0.55f) else LinearBorder,
                RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(12.dp).clip(CircleShape).background(
                if (selected) LinearAccent else LinearMuted,
            ),
        )
        Column {
            Text(
                name.ifBlank { path },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else LinearSecondary,
            )
            if (path.isNotBlank()) {
                Text(path, style = MaterialTheme.typography.labelSmall, color = LinearMuted)
            }
        }
    }
}

