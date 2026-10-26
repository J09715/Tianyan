package top.tianyan.app.ui.bot

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.McpServerConfig
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeSwitch
import top.tianyan.app.ui.components.StatusBadge

/**
 * Bot 人格设置面板（从 BotDetailScreen 抽出以控制文件尺寸）。
 * 含能力库（Bot 级 MCP 白名单，DS Bot Capabilities 语义）：null = 团队共享库全量可用。
 */
@Composable
internal fun BotPersonaPanel(
    bot: Bot,
    busy: Boolean,
    profiles: List<AiModelEntity>,
    mcpServers: List<McpServerConfig>,
    onSave: (
        name: String,
        role: String,
        persona: String,
        modelId: String,
        modelVariant: String?,
        isMainBot: Boolean,
        avatarSeed: String,
        allowModelSelection: Boolean,
        capabilityServerNames: List<String>?,
    ) -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable(bot.id) { mutableStateOf(bot.name) }
    var role by androidx.compose.runtime.saveable.rememberSaveable(bot.id) { mutableStateOf(bot.role) }
    var persona by androidx.compose.runtime.saveable.rememberSaveable(bot.id) { mutableStateOf(bot.systemPrompt) }
    var modelId by remember { mutableStateOf(bot.modelId.orEmpty()) }
    var modelVariant by remember { mutableStateOf(bot.modelVariant) }
    var allowModelSelection by remember { mutableStateOf(bot.allowModelSelection) }
    var isMain by remember { mutableStateOf(bot.isMainBot) }
    var avatarSeed by remember { mutableStateOf(bot.avatarSeed) }
    var avatarLoading by remember { mutableStateOf(false) }
    var capabilities by remember(bot.id) { mutableStateOf(bot.capabilityServerNames) }
    LaunchedEffect(bot.modelId, bot.modelVariant, bot.allowModelSelection, bot.capabilityServerNames) {
        modelId = bot.modelId.orEmpty()
        modelVariant = bot.modelVariant
        allowModelSelection = bot.allowModelSelection
        capabilities = bot.capabilityServerNames
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        bot.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (bot.isMainBot) StatusBadge(stringResource(R.string.fbot_main_bot))
                }
                Text(
                    profiles.firstOrNull { it.id == bot.modelId }?.let { "${it.name} / ${bot.modelVariant ?: it.model.substringBefore(',')}" }
                        ?: bot.modelId ?: stringResource(R.string.fbot_model_default),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        BotAvatarEditor(avatarSeed, name.ifBlank { bot.name }, bot.colorHex, !busy,
            onChange = { avatarSeed = it }, onLoading = { avatarLoading = it })
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text(stringResource(R.string.fbot_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = role,
            onValueChange = { role = it.take(40) },
            label = { Text(stringResource(R.string.fbot_role)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = persona,
            onValueChange = { persona = it },
            label = { Text(stringResource(R.string.fbot_persona)) },
            placeholder = { Text(stringResource(R.string.fbot_persona_placeholder)) },
            minLines = 6,
            maxLines = 14,
            modifier = Modifier.fillMaxWidth(),
        )
        BotModelPicker(profiles, modelId.takeIf(String::isNotBlank), modelVariant, !busy) { id, concrete ->
            modelId = id.orEmpty()
            modelVariant = concrete
        }
        BotModelPolicySwitch(allowModelSelection, !busy) { allowModelSelection = it }
        BotCapabilityChips(mcpServers, capabilities, !busy) { capabilities = it }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.fbot_main_switch), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.fbot_main_bot),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RuntimeSwitch(checked = isMain, onCheckedChange = { isMain = it }, enabled = !busy)
        }
        RuntimeButton(
            onClick = { onSave(name, role, persona, modelId, modelVariant, isMain, avatarSeed, allowModelSelection, capabilities) },
            enabled = !busy && !avatarLoading && name.isNotBlank() && role.isNotBlank() && persona.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.fbot_save)) }
    }
}

/**
 * 能力白名单切换（纯函数，供测试）：
 * - 不限制（null）下首次点选 → 仅该服务；
 * - 已选中集合内切换选中/取消；
 * - 取消到空 → 回退 null（不限制），避免空集合把 MCP 全拒锁死。
 */
internal fun toggleCapabilitySelection(selected: List<String>?, serverId: String): List<String>? {
    val lower = serverId.lowercase()
    val current = selected ?: return listOf(serverId)
    val next = current.filterNot { it.lowercase() == lower }
    return if (next.size == current.size) current + serverId else next.ifEmpty { null }
}

/** 能力库：null（不限制）或具体服务集合；全部取消勾选回退为不限制，避免误锁死。 */
@Composable
private fun BotCapabilityChips(
    servers: List<McpServerConfig>,
    selected: List<String>?,
    enabled: Boolean,
    onChange: (List<String>?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "能力库（MCP 白名单）",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "不勾选任何服务 = 团队共享库全量可用；勾选后仅所选服务可被该 Bot 挂载与调用，下一条消息生效。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RuntimeFilterChip(
                selected = selected == null,
                onClick = { onChange(null) },
                enabled = enabled,
                label = { Text("不限制") },
            )
            servers.filter { it.isEnabled }.forEach { server ->
                RuntimeFilterChip(
                    selected = selected != null && server.id.lowercase() in selected.map { it.lowercase() },
                    onClick = { onChange(toggleCapabilitySelection(selected, server.id)) },
                    enabled = enabled,
                    label = { Text(server.name) },
                )
            }
        }
    }
}
