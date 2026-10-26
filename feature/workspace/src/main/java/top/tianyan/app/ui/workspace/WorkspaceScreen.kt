package top.tianyan.app.ui.workspace
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import top.tianyan.app.ui.components.fullWidthItem
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.ApkImportSource
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.template.InstalledProjectTemplate
import top.tianyan.app.ui.components.MainDestination
import top.tianyan.app.ui.components.NoticeBanner
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.SectionHeader
import top.tianyan.app.ui.components.SpotlightGuideOverlay
import top.tianyan.app.ui.components.rememberSpotlightAnchor
import top.tianyan.app.ui.components.spotlightAnchor
/**
 * 天衍 · 工坊空间 (Workspace Space)
 * 管理 Linux 隔离工作区、代码工程与文件项目。
 *
 * 本文件仅做装配：Scaffold 骨架 + 各向导/弹窗/卡片组件（见同包 WorkspaceXxx.kt）的编排。
 */
@Composable
internal fun LocalWorkspaceScreen(
    onNavigate: (MainDestination) -> Unit,
    onOpenExplorer: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenToolCenter: () -> Unit = {},
    onOpenWorkshopSettings: () -> Unit = {},
    onOpenWorkflows: (String) -> Unit = {},
    viewModel: WorkspaceViewModel? = previewOrNull { koinViewModel() }, onBack: () -> Unit = { onNavigate(MainDestination.Agent) },
) {
    val projects by viewModel?.projects?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val busy by viewModel?.busy?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val runtimeReady by viewModel?.runtimeReady?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val message by viewModel?.message?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val messageIsError by viewModel?.messageIsError?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val buildProgress by viewModel?.buildProgress?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val activeBuildingProjectName by viewModel?.activeBuildingProjectName?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val isBuildDialogVisible by viewModel?.isBuildDialogVisible?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val installedComponentIds by viewModel?.installedComponentIds?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val keystores by viewModel?.keystores?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val loadingProjects by viewModel?.loadingProjects?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val githubImportProgress by viewModel?.githubImportProgress?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val projectTemplates by viewModel?.projectTemplates?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val templateScriptPreview by viewModel?.templateScriptPreview?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val firstUseGuidesShown by viewModel?.firstUseGuidesShown?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val context = LocalContext.current
    val pluginDebug = rememberWorkspacePluginDebugAction(onOpenToolCenter)

    // 弹窗开关用 rememberSaveable，旋转后不丢失向导进度（S3）
    var showCreate by rememberSaveable { mutableStateOf(false) }
    var showImport by rememberSaveable { mutableStateOf(false) }
    var showTemplateManager by rememberSaveable { mutableStateOf(false) }
    var showTemplateSpec by rememberSaveable { mutableStateOf(false) }
    var actionsExpanded by rememberSaveable { mutableStateOf(false) }

    // 向导状态屏幕级持有：字段全部 rememberSaveable，与拆分前语义一致
    val createWizard = rememberCreateProjectWizardState()
    val importWizard = rememberImportProjectWizardState()
    // 复杂对象（工程/模板/来源选择）旋转后需重新选择，保留 remember
    var deleteTarget by remember { mutableStateOf<WorkspaceProject?>(null) }
    var pendingExportProject by remember { mutableStateOf<WorkspaceProject?>(null) }
    var buildConfigTarget by remember { mutableStateOf<WorkspaceProject?>(null) }
    var pendingExportTemplateId by remember { mutableStateOf<String?>(null) }
    var deleteTemplateTarget by remember { mutableStateOf<InstalledProjectTemplate?>(null) }

    // APK 逆向模板：系统文件管理器选择 .apk
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val displayName = queryDisplayName(context, uri) ?: "target.apk"
            createWizard.apkSource = ApkImportSource.FromFileUri(uri.toString(), displayName)
        }
    }
    val archivePicker = rememberWorkspaceArchivePicker { source ->
        importWizard.archiveSource = source
        if (importWizard.importProjectName.isBlank()) {
            importWizard.importProjectName = source.fileName.substringBeforeLast('.').filter {
                it.isLetterOrDigit() || it == '.' || it == '_' || it == '-'
            }
        }
    }
    val templateImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel?.importProjectTemplate(uri.toString())
        }
    }
    val templateExportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val templateId = pendingExportTemplateId
        pendingExportTemplateId = null
        if (uri != null && templateId != null) viewModel?.exportProjectTemplate(templateId, uri.toString())
    }
    val exportDirectoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val project = pendingExportProject
        pendingExportProject = null
        if (uri != null && project != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel?.exportProject(project, uri.toString())
        }
    }

    // 首次进入引导：高亮右上角「更多」菜单（内含插件中心与工坊设置入口）
    val moreAnchor = rememberSpotlightAnchor()
    // 引导遮罩与 Scaffold 放在同一 Box 下（同层兄弟节点），保证聚光灯坐标与按钮 boundsInRoot 同一参照系
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                RuntimeTopBar(
                    title = stringResource(R.string.workspace_title), onBack = onBack,
                    statusText = stringResource(R.string.workspace_active_projects, projects.size),
                ) {
                    Box {
                        IconButton(
                            onClick = { actionsExpanded = true },
                            enabled = !busy,
                            modifier = Modifier.spotlightAnchor(moreAnchor),
                            contentDescription = stringResource(R.string.workspace_cd_more),
                        ) {
                            RuntimeIcon(RuntimeIconName.More, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_create)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Plus, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showCreate = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_import)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showImport = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_templates)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Package, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; showTemplateManager = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_plugins)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Package, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenToolCenter() })
                            DropdownMenuItem(text = { Text("工作流") }, leadingIcon = { RuntimeIcon(RuntimeIconName.Hub, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenWorkflows("") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.workspace_menu_settings)) }, leadingIcon = { RuntimeIcon(RuntimeIconName.Settings, Modifier.size(18.dp)) }, onClick = { actionsExpanded = false; onOpenWorkshopSettings() })
                        }
                    }
                }
            },
        ) { padding ->
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(340.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)),
                contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 6.dp, bottom = 24.dp)),
                verticalArrangement = Arrangement.spacedBy(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                fullWidthItem {
                    message?.let { notice ->
                        NoticeBanner(
                            text = notice,
                            isError = messageIsError,
                        )
                    }

                    // 后台构建常驻状态栏 (Banner)
                    WorkspaceBuildStatusBanner(
                        buildProgress = buildProgress,
                        activeBuildingProjectName = activeBuildingProjectName,
                        isBuildDialogVisible = isBuildDialogVisible,
                        onShowDialog = { viewModel?.showBuildDialog() },
                        onLaunchInstaller = { arg0 -> viewModel?.launchInstaller(arg0) },
                        onDismiss = { viewModel?.dismissBuildProgress() },
                    )
                }

                fullWidthItem {
                    // 宿主外部存储快速访问入口
                    RuntimeCard(
                        modifier = Modifier.fillMaxWidth(),
                        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                        onClick = { onOpenExplorer("sdcard") },
                        contentPadding = PaddingValues(14.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                RuntimeIcon(
                                    RuntimeIconName.Folder,
                                    Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.workspace_shared_storage),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = stringResource(R.string.workspace_shared_storage_description),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            RuntimeIcon(
                                RuntimeIconName.ChevronRight,
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                fullWidthItem {
                    SectionHeader(
                        title = stringResource(R.string.workspace_projects_section),
                        subtitle = stringResource(R.string.workspace_projects_description),
                    )
                }

                if (loadingProjects) {
                    fullWidthItem {
                        repeat(3) { index ->
                            ProjectCardSkeleton(
                                modifier = Modifier.padding(top = if (index == 0) 16.dp else 0.dp),
                            )
                        }
                    }
                } else if (projects.isEmpty()) {
                    fullWidthItem {
                        WorkspaceEmptyState(
                            enabled = !busy,
                            onCreate = { showCreate = true },
                            onImport = { showImport = true },
                        )
                    }
                } else {
                    items(projects, key = { it.name }) { project ->
                        ProjectCard(
                            project = project,
                            busy = busy || pluginDebug.busy,
                            isBuilding = (activeBuildingProjectName == project.name),
                            onOpenExplorer = { onOpenExplorer(project.name) },
                            onOpenTerminal = { onOpenTerminal(project.name) },
                            onOpenAgent = { onNavigate(MainDestination.Agent) },
                            onOpenWorkflows = { onOpenWorkflows(project.name) },
                            onRunProject = {
                                if (project.projectType == ProjectType.TIANYAN_PLUGIN) pluginDebug.start(project)
                                else buildConfigTarget = project
                            },
                            onShowBuildLog = { viewModel?.showBuildDialog() },
                            onExport = {
                                pendingExportProject = project
                                exportDirectoryPicker.launch(null)
                            },
                            onDelete = { deleteTarget = project },
                        )
                    }
                }

                fullWidthItem { Spacer(Modifier.height(16.dp)) }
            }
        }

        // 首次进入引导遮罩
        if ("workspace_more_menu" !in firstUseGuidesShown) {
            SpotlightGuideOverlay(
                anchor = moreAnchor,
                title = stringResource(R.string.workspace_guide_more_title),
                message = stringResource(R.string.workspace_guide_more_message),
                onDismiss = { viewModel?.markFirstUseGuideShown("workspace_more_menu") },
            )
        }
    } // Box

    // 运行/构建进度与实时日志弹窗 (支持后台运行与随时最小化)
    if (isBuildDialogVisible && buildProgress != null) {
        BuildProgressDialog(
            progress = buildProgress!!,
            onOpenToolCenter = onOpenToolCenter,
            onHideDialog = { viewModel?.hideBuildDialog() },
            onDismissProgress = { viewModel?.dismissBuildProgress() },
            onCancelBuild = { viewModel?.cancelBuild() },
            onLaunchInstaller = { arg0 -> viewModel?.launchInstaller(arg0) },
        )
    }

    if (showTemplateManager) {
        TemplateManagerDialog(
            templates = projectTemplates,
            busy = busy,
            onDismiss = { showTemplateManager = false },
            onImport = { templateImportPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
            onExport = { template ->
                pendingExportTemplateId = template.manifest.id
                templateExportPicker.launch("${template.manifest.id}.zip")
            },
            onDelete = {
                showTemplateManager = false
                deleteTemplateTarget = it
            },
            onShowSpec = {
                showTemplateManager = false
                showTemplateSpec = true
            },
        )
    }

    if (showTemplateSpec) {
        ProjectTemplateSpecDialog(onDismiss = { showTemplateSpec = false })
    }

    templateScriptPreview?.let { preview ->
        RuntimeAlertDialog(
            onDismissRequest = { viewModel?.dismissTemplateScripts() },
            title = { Text(stringResource(R.string.workspace_template_scripts_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    preview,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { viewModel?.dismissTemplateScripts() }) { Text(stringResource(R.string.workspace_action_close)) } },
        )
    }

    deleteTemplateTarget?.let { template ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTemplateTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_template_title)) },
            text = { Text(stringResource(R.string.workspace_delete_template_message, template.manifest.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel?.deleteProjectTemplate(template.manifest.id)
                    deleteTemplateTarget = null
                }) { Text(stringResource(R.string.workspace_confirm_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTemplateTarget = null }) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    if (showCreate && viewModel != null) {
        CreateProjectDialog(
            state = createWizard,
            viewModel = viewModel,
            projectTemplates = projectTemplates,
            busy = busy,
            runtimeReady = runtimeReady,
            installedComponentIds = installedComponentIds,
            apkPicker = apkPicker,
            onDismiss = { showCreate = false },
            onOpenToolCenter = onOpenToolCenter,
        )
    }

    if (showImport && viewModel != null) {
        ImportProjectDialog(
            state = importWizard,
            viewModel = viewModel,
            busy = busy,
            githubImportProgress = githubImportProgress,
            archivePicker = archivePicker,
            onDismiss = { showImport = false },
        )
    }

    deleteTarget?.let { project ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.workspace_delete_project_title, project.name), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    if (project.ownsDirectory) stringResource(R.string.workspace_delete_owned_project, project.linuxPath)
                    else stringResource(R.string.workspace_unlink_project),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleteTarget = null; viewModel?.delete(project.name) },
                    enabled = !busy,
                ) {
                    if (busy) {
                        RuntimeCircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.workspace_confirm_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }, enabled = !busy) { Text(stringResource(R.string.workspace_cancel)) } },
        )
    }

    // 构建类型选择：Debug 直接构建；Release 需选择已登记签名，没有签名则引导去创建
    buildConfigTarget?.let { target ->
        BuildTypePickerDialog(
            project = target,
            keystores = keystores,
            onDismiss = { buildConfigTarget = null },
            onConfirm = { type, keystore ->
                buildConfigTarget = null
                viewModel?.runProject(target, type, keystore)
            },
            onManageSigning = {
                buildConfigTarget = null
                onOpenWorkshopSettings()
            },
        )
    }
}
