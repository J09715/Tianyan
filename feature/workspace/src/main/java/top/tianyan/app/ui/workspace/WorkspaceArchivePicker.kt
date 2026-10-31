package top.tianyan.app.ui.workspace

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import top.tianyan.app.runtime.ProjectArchiveSource

@Composable
internal fun rememberWorkspaceArchivePicker(onPicked: (ProjectArchiveSource) -> Unit) =
    LocalContext.current.let { context ->
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                onPicked(ProjectArchiveSource(uri.toString(), queryDisplayName(context, uri) ?: "project.zip"))
            }
        }
    }
