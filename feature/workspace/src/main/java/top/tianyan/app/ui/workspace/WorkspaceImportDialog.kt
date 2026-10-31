package top.tianyan.app.ui.workspace

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import top.tianyan.app.ui.components.RuntimeFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.GitTransport
import top.tianyan.app.runtime.ProjectArchiveSource
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/** 导入来源：本地压缩包 / GitHub。 */
internal enum class ProjectImportMode { LOCAL, GITHUB }

/**
 * 导入项目向导状态（屏幕级持有，rememberSaveable 保证旋转后进度不丢）。
 * archiveSource 为复杂对象，按原语义只保留 remember，旋转后需重新选择。
 */
@Stable
internal class ImportProjectWizardState(
    importMode: MutableState<ProjectImportMode>,
    importProjectName: MutableState<String>,
    importDirectoryPath: MutableState<String>,
    importDirectoryMenuExpanded: MutableState<Boolean>,
    importProjectType: MutableState<ProjectType>,
    archiveSource: MutableState<ProjectArchiveSource?>,
    importGitUrl: MutableState<String>,
    gitTransport: MutableState<GitTransport>,
    importInProgress: MutableState<Boolean>,
) {
    var importMode by importMode
    var importProjectName by importProjectName
    var importDirectoryPath by importDirectoryPath
    var importDirectoryMenuExpanded by importDirectoryMenuExpanded
    var importProjectType by importProjectType
    var archiveSource by archiveSource
    var importGitUrl by importGitUrl
    var gitTransport by gitTransport
    var importInProgress by importInProgress

    /** 关闭或导入完成后复位向导。 */
    fun reset() {
        importMode = ProjectImportMode.LOCAL
        importProjectName = ""
        importDirectoryPath = ""
        importProjectType = ProjectType.ANDROID
        archiveSource = null
        importGitUrl = ""
        gitTransport = GitTransport.HTTP
        importInProgress = false
    }
}
@Composable
internal fun rememberImportProjectWizardState(): ImportProjectWizardState =
    ImportProjectWizardState(
        importMode = rememberSaveable { mutableStateOf(ProjectImportMode.LOCAL) },
        importProjectName = rememberSaveable { mutableStateOf("") },
        importDirectoryPath = rememberSaveable { mutableStateOf("") },
        importDirectoryMenuExpanded = rememberSaveable { mutableStateOf(false) },
        importProjectType = rememberSaveable { mutableStateOf(ProjectType.ANDROID) },
        archiveSource = remember { mutableStateOf<ProjectArchiveSource?>(null) },
        importGitUrl = rememberSaveable { mutableStateOf("") },
        gitTransport = rememberSaveable { mutableStateOf(GitTransport.HTTP) },
        importInProgress = rememberSaveable { mutableStateOf(false) },
    )

/**
 * 导入项目向导弹窗：本地压缩包 / GitHub 两种来源。
 * GitHub 拉取期间保持弹窗打开展示克隆进度（可能耗时数分钟），完成后统一关闭并复位。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
internal fun ImportProjectDialog(
    state: ImportProjectWizardState,
    viewModel: WorkspaceViewModel,
    busy: Boolean,
    githubImportProgress: WorkspaceViewModel.GithubImportProgress?,
    archivePicker: ActivityResultLauncher<Array<String>>,
    onDismiss: () -> Unit,
) {
    fun resetImportDialog() {
        state.reset()
        onDismiss()
    }
    // GitHub 拉取进行中：保持弹窗展示克隆进度，完成后统一关闭并复位（克隆可能耗时数分钟）
    LaunchedEffect(state.importInProgress, busy) {
        if (state.importInProgress && !busy) resetImportDialog()
    }
    // GitHub HTTP(S) 导入地址前置校验：必须是 http:// 或 https:// 开头
    val gitUrlError = if (
        state.importMode == ProjectImportMode.GITHUB &&
        state.gitTransport == GitTransport.HTTP &&
        state.importGitUrl.isNotBlank() &&
        !state.importGitUrl.trim().startsWith("http://") &&
        !state.importGitUrl.trim().startsWith("https://")
    ) stringResource(R.string.workspace_git_url_invalid) else null
    RuntimeAlertDialog(
        onDismissRequest = { if (!state.importInProgress) resetImportDialog() },
        title = { Text(stringResource(R.string.workspace_import_project), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // GitHub 拉取进度：git 克隆可能耗时数分钟，实时展示最新进度与百分比
                if (state.importInProgress) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RuntimeCircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    stringResource(R.string.workspace_git_clone_in_progress),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                githubImportProgress?.percent?.let { percent ->
                                    Text(
                                        "$percent%",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            val clonePercent = githubImportProgress?.percent
                            LinearProgressIndicator(
                                progress = clonePercent?.let { percent -> { percent / 100f } },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(4.dp)),
                            )
                            githubImportProgress?.let { progress ->
                                Text(
                                    progress.text,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.workspace_import_source),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(shape = androidx.compose.foundation.shape.CircleShape,
                        selected = state.importMode == ProjectImportMode.LOCAL,
                        onClick = { state.importMode = ProjectImportMode.LOCAL },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(16.dp)) },
                        label = { Text(stringResource(R.string.workspace_local_archive)) },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(shape = androidx.compose.foundation.shape.CircleShape,
                        selected = state.importMode == ProjectImportMode.GITHUB,
                        onClick = { state.importMode = ProjectImportMode.GITHUB },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Github, Modifier.size(16.dp)) },
                        label = { Text("GitHub") },
                        modifier = Modifier.weight(1f),
                    )
                }

                if (state.importMode == ProjectImportMode.LOCAL) {
                    OutlinedButton(
                        onClick = {
                            archivePicker.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RuntimeIcon(RuntimeIconName.FolderOpen, Modifier.size(17.dp))
                        Spacer(Modifier.size(7.dp))
                        Text(stringResource(R.string.workspace_choose_project_archive))
                    }
                    state.archiveSource?.let { source ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Compress, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                Text(
                                    source.fileName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                RuntimeIconButton(
                                    onClick = { state.archiveSource = null },
                                    modifier = Modifier
                                        .size(30.dp)
                                        .minimumInteractiveComponentSize(),
                                    contentDescription = stringResource(R.string.workspace_cd_close),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp))
                                }
                            }
                        }
                    }
                } else {
                    Text(
                        stringResource(R.string.workspace_git_transport),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(shape = androidx.compose.foundation.shape.CircleShape,
                            selected = state.gitTransport == GitTransport.HTTP,
                            onClick = { state.gitTransport = GitTransport.HTTP },
                            label = { Text("HTTP(S)") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(shape = androidx.compose.foundation.shape.CircleShape,
                            selected = state.gitTransport == GitTransport.SSH,
                            onClick = { state.gitTransport = GitTransport.SSH },
                            label = { Text("SSH") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    RuntimeFilledTextField(
                        value = state.importGitUrl,
                        onValueChange = { state.importGitUrl = it },
                        label = { Text(stringResource(R.string.workspace_git_url)) },
                        placeholder = {
                            Text(
                                if (state.gitTransport == GitTransport.HTTP) "https://github.com/user/project.git"
                                else "git@github.com:user/project.git",
                            )
                        },
                        supportingText = {
                            Text(gitUrlError ?: stringResource(R.string.workspace_git_import_description))
                        },
                        isError = gitUrlError != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    stringResource(R.string.workspace_project_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    projectImportTypeOptions().forEach { (type, pair) ->
                        FilterChip(shape = androidx.compose.foundation.shape.CircleShape,
                            selected = state.importProjectType == type,
                            onClick = { state.importProjectType = type },
                            leadingIcon = { RuntimeIcon(pair.second, Modifier.size(16.dp)) },
                            label = { Text(pair.first, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }

                RuntimeFilledTextField(
                    value = state.importProjectName,
                    onValueChange = { state.importProjectName = it },
                    label = { Text(stringResource(R.string.workspace_project_name)) },
                    placeholder = { Text("my-imported-project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                val importPathName = state.importProjectName.trim().ifBlank { "my-project" }
                val commonDirectories = listOf(
                    "" to stringResource(R.string.workspace_location_project, importPathName),
                    "projects/$importPathName" to stringResource(R.string.workspace_location_projects, importPathName),
                    "repos/$importPathName" to stringResource(R.string.workspace_location_repos, importPathName),
                    "work/$importPathName" to stringResource(R.string.workspace_location_work, importPathName),
                )
                ExposedDropdownMenuBox(
                    expanded = state.importDirectoryMenuExpanded,
                    onExpandedChange = { state.importDirectoryMenuExpanded = it },
                ) {
                    RuntimeFilledTextField(
                        value = state.importDirectoryPath,
                        onValueChange = {
                            state.importDirectoryPath = it
                            state.importDirectoryMenuExpanded = true
                        },
                        label = { Text(stringResource(R.string.workspace_linked_directory)) },
                        placeholder = { Text(stringResource(R.string.workspace_directory_default)) },
                        supportingText = { Text(stringResource(R.string.workspace_import_directory_hint)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(state.importDirectoryMenuExpanded) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().menuAnchor(
                            ExposedDropdownMenuAnchorType.PrimaryEditable,
                            true,
                        ),
                    )
                    ExposedDropdownMenu(
                        expanded = state.importDirectoryMenuExpanded,
                        onDismissRequest = { state.importDirectoryMenuExpanded = false },
                    ) {
                        commonDirectories.forEach { (path, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    state.importDirectoryPath = path
                                    state.importDirectoryMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                val targetPath = state.importDirectoryPath.trim().ifBlank { state.importProjectName.trim() }
                if (targetPath.isNotBlank()) {
                    Text(
                        stringResource(R.string.workspace_sandbox_path, "/workspace", targetPath.trimStart('/')),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (state.importMode == ProjectImportMode.LOCAL) {
                        state.archiveSource?.let {
                            viewModel.importLocalProject(state.importProjectName, state.importDirectoryPath, state.importProjectType, it)
                        }
                        resetImportDialog()
                    } else {
                        // 拉取期间保持弹窗打开展示 git 克隆进度，完成后由 LaunchedEffect 统一关闭
                        state.importInProgress = true
                        viewModel.importGithubProject(
                            state.importProjectName,
                            state.importDirectoryPath,
                            state.importProjectType,
                            state.importGitUrl,
                            state.gitTransport,
                        )
                    }
                },
                enabled = state.importProjectName.isNotBlank() && !busy && gitUrlError == null &&
                    ((state.importMode == ProjectImportMode.LOCAL && state.archiveSource != null) ||
                        (state.importMode == ProjectImportMode.GITHUB && state.importGitUrl.isNotBlank())),
            ) { Text(stringResource(R.string.workspace_import), color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            TextButton(onClick = { resetImportDialog() }, enabled = !state.importInProgress) { Text(stringResource(R.string.workspace_cancel)) }
        },
    )
}
