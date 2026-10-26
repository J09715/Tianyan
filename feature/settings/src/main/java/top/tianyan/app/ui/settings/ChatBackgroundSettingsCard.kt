package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.ChatBackgroundStyle
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.*

@Composable
internal fun ChatBackgroundSettingsCard(
    viewModel: ChatBackgroundSettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val saved by viewModel?.style?.collectAsStateWithLifecycle() ?: previewStateOf(ChatBackgroundStyle())
    val busy by viewModel?.busy?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val failed by viewModel?.failed?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    var visibility by remember { mutableFloatStateOf(saved.visibility) }
    var blur by remember { mutableFloatStateOf(saved.blurDp) }
    LaunchedEffect(saved.visibility) { visibility = saved.visibility }
    LaunchedEffect(saved.blurDp) { blur = saved.blurDp }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel?.chooseImage(uri)
    }
    RuntimeCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.settings_chat_background), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.settings_chat_background_description),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatBackgroundPreview(saved.copy(visibility = visibility, blurDp = blur))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeOutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    enabled = !busy,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(if (busy) R.string.settings_chat_background_loading
                        else R.string.settings_chat_background_choose), maxLines = 1)
                }
                RuntimeOutlinedButton(onClick = { viewModel?.removeImage() },
                    enabled = !busy && saved.imageName != null,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.settings_chat_background_remove), maxLines = 1)
                }
            }
            if (failed) Text(stringResource(R.string.settings_chat_background_error),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            val visibilityLabel = stringResource(R.string.settings_chat_background_visibility, (visibility * 100).roundToInt())
            Text(visibilityLabel, style = MaterialTheme.typography.titleSmall)
            RuntimeSlider(value = visibility, onValueChange = { visibility = it }, valueRange = 0f..1f,
                onValueChangeFinished = { viewModel?.setVisibility(visibility) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = visibilityLabel })
            val blurLabel = stringResource(R.string.settings_chat_background_blur, blur.roundToInt())
            Text(blurLabel, style = MaterialTheme.typography.titleSmall)
            RuntimeSlider(value = blur, onValueChange = { blur = it.roundToInt().toFloat() }, valueRange = 0f..24f, steps = 23,
                onValueChangeFinished = { viewModel?.setBlur(blur) },
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = blurLabel })
        }
    }
}

@Composable
private fun ChatBackgroundPreview(style: ChatBackgroundStyle) {
    val label = stringResource(R.string.settings_chat_background_preview)
    Box(Modifier.fillMaxWidth().heightIn(min = 210.dp).clip(MaterialTheme.shapes.large)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        RuntimeChatBackground(style, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Surface(Modifier.align(Alignment.CenterHorizontally), shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.94f)) {
                Text(stringResource(R.string.settings_chat_background_preview_title), Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(12.dp))
            PreviewBubble(stringResource(R.string.settings_chat_background_preview_user), true)
            Spacer(Modifier.height(10.dp))
            PreviewBubble(stringResource(R.string.settings_chat_background_preview_assistant), false)
        }
    }
}

@Composable
private fun ColumnScope.PreviewBubble(text: String, user: Boolean) {
    Surface(modifier = Modifier.align(if (user) Alignment.End else Alignment.Start).fillMaxWidth(if (user) 0.8f else 0.9f),
        shape = RoundedCornerShape(16.dp),
        color = (if (user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh).copy(alpha = 0.94f),
        contentColor = if (user) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
