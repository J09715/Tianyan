package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.core.model.RedTeamConsoleModel
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeTextButton

/**
 * 智能体提示词页签（上游 `prompts` / `savePrompt`）。
 *
 * 角色列表 + 正文编辑器。改过的角色在列表里标「已改」——上游没有这个标记，
 * 但保存后没有「恢复默认」的线索时，用户根本分不清哪条是自己动过的。
 */
@Composable
internal fun RedTeamPromptsTab(
    state: RedTeamConsoleState,
    onSelectRole: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Text(
                "角色提示词 · ${state.roles.size} 个角色",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                state.roles.forEach { role ->
                    val selected = role.id == state.roleId
                    RuntimeTextButton(
                        onClick = { onSelectRole(role.id) },
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (selected) {
                                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Column {
                            Text(
                                role.title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                            Text(
                                if (role.overridden) "已改" else "内置",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (role.overridden) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (state.roles.isEmpty()) {
                Text(
                    "还没有读到角色提示词，点顶栏刷新重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val role = state.selectedRole
        if (role != null) {
            RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            role.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(role.id)
                                role.updatedAt?.let { append(" · 更新 ${formatEpochTime(it)}") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    state.roleMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                        )
                    }
                    RuntimeTextButton(onClick = onReset, enabled = !state.roleSaving) {
                        Text("恢复内置", style = MaterialTheme.typography.labelMedium)
                    }
                    RuntimeTextButton(onClick = onSave, enabled = state.roleDirty && !state.roleSaving) {
                        Text(
                            if (state.roleSaving) "保存中…" else "保存",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                OutlinedTextField(
                    value = state.roleDraft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    placeholder = { Text("该角色的系统提示词（Markdown）", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

/**
 * 技能库页签（上游 `skillCatalog` / `saveSkill` / `deleteSkill`）。
 *
 * 列表在左、编辑器在右是桌面布局；移动端改成上下两段，避免横向滚动里再套一个滚动区。
 * 内置技能只读——改了就回不到出厂文案，而面板上没有「恢复默认」的入口。
 */
@Composable
internal fun RedTeamSkillsTab(
    state: RedTeamConsoleState,
    onSelectSkill: (String) -> Unit,
    onNewSkill: () -> Unit,
    onDraftChange: ((top.tianyan.app.harness.redteam.RedTeamSkillStore.Skill) -> top.tianyan.app.harness.redteam.RedTeamSkillStore.Skill) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "技能库 · ${state.skills.size} 个技能",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                RuntimeTextButton(onClick = onNewSkill) {
                    Text("+ 新建技能", style = MaterialTheme.typography.labelMedium)
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.skills.isEmpty()) {
                    Text(
                        "技能库为空：新建一个，或把带 SKILL.md 的目录放进技能扫描根目录后同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.skills.forEach { skill ->
                    val selected = skill.id == state.skillDraft?.id && skill.id.isNotBlank()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                            )
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                skill.name,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                skill.description.ifBlank { "（无描述）" },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (!skill.enabled) {
                            Text("停用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (skill.builtin) {
                            Text("内置", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeTextButton(onClick = { onSelectSkill(skill.id) }) {
                            Text("编辑", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        val draft = state.skillDraft
        if (draft == null) {
            RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
                Text(
                    "左侧选择一个技能，或新建",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        if (draft.id.isBlank()) "新建技能" else draft.name.ifBlank { "技能库" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    state.skillMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                    RuntimeTextButton(onClick = onDelete, enabled = !state.skillSaving && state.skillEditable) {
                        Text("删除", style = MaterialTheme.typography.labelMedium)
                    }
                    RuntimeTextButton(onClick = onSave, enabled = !state.skillSaving && state.skillEditable) {
                        Text(
                            if (state.skillSaving) "保存中…" else "保存",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                if (!state.skillEditable) {
                    Text(
                        "内置技能只读：改了就回不到出厂文案，需要调整请新建一个同名自定义技能。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = { value -> onDraftChange { it.copy(name = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("技能名称（英文短名）", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = state.skillEditable,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draft.role.orEmpty(),
                        onValueChange = { value -> onDraftChange { it.copy(role = value.ifBlank { null }) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("角色（recon/assess/exploit/internal，留空表示不限）", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = state.skillEditable,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        top.tianyan.app.ui.components.RuntimeSwitch(
                            checked = draft.enabled,
                            onCheckedChange = { value -> onDraftChange { it.copy(enabled = value) } },
                            enabled = state.skillEditable,
                        )
                        Text("启用", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedTextField(
                        value = draft.description,
                        onValueChange = { value -> onDraftChange { it.copy(description = value) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("一句话描述", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = state.skillEditable,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draft.whenToUse.orEmpty(),
                        onValueChange = { value -> onDraftChange { it.copy(whenToUse = value.ifBlank { null }) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("何时使用（whenToUse）", fontSize = 12.sp) },
                        singleLine = true,
                        enabled = state.skillEditable,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = draft.body,
                        onValueChange = { value -> onDraftChange { it.copy(body = value) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder = { Text("技能正文（Markdown）", fontSize = 12.sp) },
                        enabled = state.skillEditable,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        draft.path ?: "保存后由技能库统一管理，智能体通过原生 skill 工具调用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
