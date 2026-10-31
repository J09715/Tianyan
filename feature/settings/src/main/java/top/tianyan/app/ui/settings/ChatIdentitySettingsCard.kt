package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.ChatIdentityStyle
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*

@Composable
internal fun ChatIdentitySettingsCard(viewModel: ChatIdentitySettingsViewModel? = previewOrNull { koinViewModel() }) {
    val saved by viewModel?.style?.collectAsStateWithLifecycle() ?: previewStateOf(ChatIdentityStyle())
    val busy by viewModel?.busy?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val failed by viewModel?.failed?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val cropBitmap by viewModel?.cropBitmap?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    var name by rememberSaveable { mutableStateOf(saved.name) }
    LaunchedEffect(saved.name) { name = saved.name }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel?.chooseImage(it) }
    }
    cropBitmap?.let { bitmap ->
        AvatarCropDialog(bitmap, busy, failed, onDismiss = { viewModel?.cancelCrop() },
            onConfirm = { viewModel?.confirmCrop(it) })
    }
    PersonalizationSection(stringResource(R.string.settings_chat_identity_title)) {
        RuntimeCard(contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeChatIdentityAvatar(40.dp, saved)
                    Text(saved.name, style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.settings_chat_identity_description), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RuntimeFilledTextField(value = name, onValueChange = { value ->
                    if (value.codePointCount(0, value.length) <= ChatIdentityStyle.MAX_NAME_LENGTH) name = value
                }, label = { Text(stringResource(R.string.settings_chat_identity_name)) }, singleLine = true,
                    enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeOutlinedButton(onClick = { viewModel?.setName(name) },
                        enabled = !busy && name.isNotBlank() && ChatIdentityStyle(name).normalized().name != saved.name) {
                        Text(stringResource(R.string.settings_chat_identity_save))
                    }
                    RuntimeOutlinedButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, enabled = !busy) { Text(stringResource(R.string.settings_chat_identity_avatar)) }
                }
                RuntimeTextButton(onClick = { viewModel?.reset() }, enabled = !busy && saved != ChatIdentityStyle()) {
                    Text(stringResource(R.string.settings_chat_identity_reset))
                }
                if (failed) Text(stringResource(R.string.settings_chat_identity_error), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
