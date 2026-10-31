package top.tianyan.app.ui.bot

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.feature.bot.R

@Composable
internal fun BotModelSummary(bot: Bot, profiles: List<AiModelEntity>?) {
    val profile = profiles?.firstOrNull { it.id == bot.modelId }
    val variants = profile?.model?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    val variant = bot.modelVariant ?: variants.firstOrNull()
    val invalid = bot.modelId != null && profiles != null &&
        (profile == null || variant == null || variants.none { it.equals(variant, ignoreCase = true) })
    val label = when {
        bot.modelId == null -> stringResource(R.string.fbot_model_default)
        profiles == null -> stringResource(R.string.fbot_model_loading)
        profile == null -> stringResource(R.string.fbot_model_invalid)
        invalid -> "${profile.name} · ${stringResource(R.string.fbot_model_unavailable)}"
        else -> "${profile.name} · ${profile.provider} · $variant"
    }
    Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}
