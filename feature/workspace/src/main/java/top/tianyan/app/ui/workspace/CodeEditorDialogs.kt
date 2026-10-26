package top.tianyan.app.ui.workspace

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/** 顶栏「放弃修改」二次确认：撤销全部未保存编辑属于破坏性操作。 */
@Composable
internal fun DiscardEditsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_discard_edits_title)) },
        text = { Text(stringResource(R.string.workspace_discard_edits_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.workspace_discard_edits_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_keep_editing)) }
        },
    )
}

/** 保存冲突：磁盘版本已被外部（如 AI 工具）修改，让用户选择覆盖或重新加载。 */
@Composable
internal fun SaveConflictDialog(
    isSaving: Boolean,
    isLoading: Boolean,
    onOverwrite: () -> Unit,
    onReload: () -> Unit,
    onDismiss: () -> Unit,
) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_save_conflict_title)) },
        text = { Text(stringResource(R.string.workspace_save_conflict_message)) },
        confirmButton = {
            TextButton(onClick = onOverwrite, enabled = !isSaving) {
                Text(stringResource(R.string.workspace_save_conflict_overwrite), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onReload, enabled = !isSaving && !isLoading) {
                Text(stringResource(R.string.workspace_save_conflict_reload))
            }
        },
    )
}