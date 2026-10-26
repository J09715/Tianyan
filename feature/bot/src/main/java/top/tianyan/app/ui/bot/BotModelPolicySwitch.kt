package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeSwitch

@Composable
internal fun BotModelPolicySwitch(allowed: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.fbot_model_self_selection), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.fbot_model_self_selection_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RuntimeSwitch(checked = allowed, onCheckedChange = onChange, enabled = enabled)
    }
}
