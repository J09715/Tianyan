package top.tianyan.app.ui.bot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun BotAvatarEditor(
    seed: String,
    defaultSeed: String,
    colorHex: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onLoading: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var designing by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            loading = true
            onLoading(true)
            failed = false
            try {
                onChange(withContext(Dispatchers.IO) { BotAvatarImage.readFrom(context, uri) })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                loading = false
                onLoading(false)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotAvatar(seed, colorHex, 64.dp)
            Column {
                RuntimeTextButton(enabled = enabled && !loading, onClick = { designing = true }) {
                    Text(stringResource(R.string.fbot_pixel_edit))
                }
                RuntimeTextButton(enabled = enabled && !loading,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(stringResource(if (loading) R.string.fbot_avatar_loading else R.string.fbot_avatar_choose))
                }
                RuntimeTextButton(enabled = enabled && !loading && seed != defaultSeed,
                    onClick = { failed = false; onChange(defaultSeed) }) {
                    Text(stringResource(R.string.fbot_avatar_reset))
                }
            }
        }
        if (failed) Text(stringResource(R.string.fbot_avatar_failed), color = MaterialTheme.colorScheme.error)
    }
    if (designing) PixelAvatarDesigner(seed, defaultSeed, colorHex,
        onDismiss = { designing = false }, onSave = { onChange(it); designing = false })
}
