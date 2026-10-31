package top.tianyan.app.ui.settings

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.settings.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTextButton

@Composable
internal fun AvatarCropDialog(bitmap: Bitmap, busy: Boolean, failed: Boolean,
    onDismiss: () -> Unit, onConfirm: (AvatarCrop) -> Unit) {
    var x by rememberSaveable(bitmap) { mutableFloatStateOf(0.5f) }
    var y by rememberSaveable(bitmap) { mutableFloatStateOf(0.5f) }
    var zoom by rememberSaveable(bitmap) { mutableFloatStateOf(1f) }
    val crop = AvatarCrop(x, y, zoom)
    val currentCrop by rememberUpdatedState(crop)
    val currentBusy by rememberUpdatedState(busy)
    fun update(next: AvatarCrop) { x = next.x; y = next.y; zoom = next.zoom }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val outline = MaterialTheme.colorScheme.outline
    val zoomLabel = stringResource(R.string.settings_avatar_crop_zoom)
    RuntimeAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.settings_avatar_crop_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.settings_avatar_crop_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Canvas(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f)
                    .pointerInput(bitmap) {
                        detectTransformGestures { _, pan, scale, _ ->
                            if (!currentBusy) update(currentCrop.transform(bitmap.width, bitmap.height, size.width, pan.x, pan.y, scale))
                        }
                    }) {
                    val bounds = crop.bounds(bitmap.width, bitmap.height)
                    drawImage(image, srcOffset = IntOffset(bounds.left, bounds.top), srcSize = IntSize(bounds.side, bounds.side),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    drawRect(outline, style = Stroke(1.dp.toPx()))
                }
                Text(zoomLabel, style = MaterialTheme.typography.labelMedium)
                Slider(value = zoom, onValueChange = { next ->
                    update(crop.transform(bitmap.width, bitmap.height, 1, 0f, 0f, next / zoom))
                }, valueRange = 1f..4f, enabled = !busy,
                    modifier = Modifier.semantics { contentDescription = zoomLabel })
                if (failed) Text(stringResource(R.string.settings_chat_identity_error), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = { onConfirm(crop) }, enabled = !busy) {
                Text(stringResource(R.string.settings_avatar_crop_confirm))
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.settings_avatar_crop_cancel))
            }
        },
    )
}
