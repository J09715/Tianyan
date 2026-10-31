package top.tianyan.app.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

/**
 * Composer 的附件弹窗承载层（从 ChatComposer 抽出以控其行数）：
 * 持有相册/文件选择 launcher，渲染 "+" 附件弹窗与草图绘制弹窗。
 */
@Composable
internal fun ComposerSheets(
    showAttach: Boolean,
    showSketch: Boolean,
    canCompress: Boolean,
    onDismissAttach: () -> Unit,
    onDismissSketch: () -> Unit,
    onOpenSketch: () -> Unit,
    onAttachmentsPicked: (List<Uri>, Boolean) -> Unit,
    onOpenSkillsMcp: () -> Unit,
    onCompactContext: () -> Unit,
) {
    val context = LocalContext.current
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
    ) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) onAttachmentsPicked(listOf(uri), true)
        pendingCameraUri = null
    }
    val onCamera = {
        val file = File(context.cacheDir, "camera/cam_${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris -> onAttachmentsPicked(uris.toList(), true) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents(),
    ) { uris -> onAttachmentsPicked(uris.toList(), false) }

    if (showAttach) {
        ChatAttachSheet(
            canCompress = canCompress,
            onCamera = { onDismissAttach(); onCamera() },
            onPickImage = { onDismissAttach(); imagePickerLauncher.launch("image/*") },
            onSketch = { onDismissAttach(); onOpenSketch() },
            onPickFile = { onDismissAttach(); filePickerLauncher.launch("*/*") },
            onOpenSkillsMcp = { onDismissAttach(); onOpenSkillsMcp() },
            onCompact = { onDismissAttach(); onCompactContext() },
            onDismiss = onDismissAttach,
        )
    }

    if (showSketch) {
        ChatSketchSheet(
            onConfirm = { uri ->
                onDismissSketch()
                onAttachmentsPicked(listOf(uri), true)
            },
            onDismiss = onDismissSketch,
        )
    }
}
