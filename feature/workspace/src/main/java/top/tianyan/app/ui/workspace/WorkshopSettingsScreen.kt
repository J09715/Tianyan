package top.tianyan.app.ui.workspace
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import top.tianyan.app.ui.components.RuntimeFilledTextField
import top.tianyan.app.ui.components.RuntimeSettingsScaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.SectionHeader
import top.tianyan.app.core.database.BuildScriptEntity
import top.tianyan.app.runtime.ProjectType
@Composable
fun WorkshopSettingsScreen(onBack: () -> Unit, onOpenEnvironment: () -> Unit, onOpenSigning: () -> Unit = {}, onEditScript: (WorkshopScriptType) -> Unit = {}, viewModel: WorkshopSettingsViewModel? = previewOrNull { koinViewModel() }) {
    val draft by viewModel?.draft?.collectAsStateWithLifecycle() ?: previewStateOf(WorkshopEnvironmentDraft())
    val scripts by viewModel?.managedScripts?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val projects by viewModel?.projects?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val bindings by viewModel?.projectBindings?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = scripts.firstOrNull { it.id == editingId }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleteScriptTarget by remember { mutableStateOf<BuildScriptEntity?>(null) }
    RuntimeSettingsScaffold(stringResource(R.string.workshop_settings_title), onBack) {
            item { SectionHeader(stringResource(R.string.workshop_settings_env_section), stringResource(R.string.workshop_settings_env_section_desc)) }
            item {
                SettingEntry(
                    RuntimeIconName.Android,
                    stringResource(R.string.workshop_settings_env_entry),
                    stringResource(
                        R.string.workshop_settings_env_entry_desc,
                        draft.androidSdkPath, draft.ndkPath, draft.gradlePath, draft.cmakePath,
                    ),
                    onOpenEnvironment,
                )
            }
            item { SectionHeader(stringResource(R.string.workshop_settings_signing_section), stringResource(R.string.workshop_settings_signing_section_desc)) }
            item { SettingEntry(RuntimeIconName.Key, stringResource(R.string.workshop_settings_signing_entry), stringResource(R.string.workshop_settings_signing_entry_desc), onOpenSigning) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { SectionHeader(stringResource(R.string.workshop_settings_scripts_section), stringResource(R.string.workshop_settings_scripts_section_desc)) }
                RuntimeButton(onClick = { creating = true }) { Text(stringResource(R.string.workshop_settings_new_script)) }
            } }
            scripts.forEach { script -> item(key = script.id) {
                ManagedScriptCard(
                    script = script,
                    onEdit = { editingId = script.id },
                    onClone = { viewModel?.cloneScript(script) },
                    onDelete = { deleteScriptTarget = script },
                )
            } }
            item { SectionHeader(stringResource(R.string.workshop_binding_section), stringResource(R.string.workshop_binding_section_desc)) }
            projects.filter { it.projectType == ProjectType.ANDROID || it.projectType == ProjectType.FLUTTER }.forEach { project ->
                item(key = "binding-${project.name}") {
                    ProjectScriptBindingRow(
                        projectName = project.name,
                        projectType = project.projectType,
                        scripts = scripts.filter { it.projectType == project.projectType.name && !it.isBuiltin },
                        selectedId = bindings.firstOrNull { it.projectName == project.name }?.scriptId?.takeIf { id ->
                            scripts.any { it.id == id && !it.isBuiltin }
                        },
                        onSelect = { viewModel?.bindProject(project.name, it) },
                    )
                }
            }
    }
    if (creating) ManagedScriptEditorDialog(null, onDismiss = { creating = false }) { name, description, type, content ->
        val error = viewModel?.saveManagedScript(null, name, description, type, content)
        if (error == null) creating = false
        error
    }
    editing?.let { script -> ManagedScriptEditorDialog(script, onDismiss = { editingId = null }) { name, description, type, content ->
        val error = viewModel?.saveManagedScript(script.id, name, description, type, content)
        if (error == null) editingId = null
        error
    } }
    deleteScriptTarget?.let { script ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteScriptTarget = null },
            title = { Text(stringResource(R.string.workshop_script_delete_title, script.name)) },
            text = { Text(stringResource(R.string.workshop_script_delete_message)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    viewModel?.deleteManagedScript(script.id)
                    deleteScriptTarget = null
                }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text(stringResource(R.string.workspace_confirm_delete)) }
            },
            dismissButton = {
                RuntimeOutlinedButton(onClick = { deleteScriptTarget = null }) { Text(stringResource(R.string.workspace_cancel)) }
            },
        )
    }
}

@Composable
private fun ManagedScriptCard(script: BuildScriptEntity, onEdit: () -> Unit, onClone: () -> Unit, onDelete: () -> Unit) {
    var actionsExpanded by remember { mutableStateOf(false) }
    RuntimeCard(onClick = onEdit, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeIcon(if (script.projectType == ProjectType.FLUTTER.name) RuntimeIconName.Flutter else RuntimeIconName.Android, Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(script.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${script.projectType} · ${if (script.isBuiltin) stringResource(R.string.workshop_script_builtin) else stringResource(R.string.workshop_script_user)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // 与文件浏览器一致的溢出菜单模式（替代裸文字 clickable，保证触摸热区）
                Box {
                    RuntimeIconButton(
                        onClick = { actionsExpanded = true },
                        contentDescription = stringResource(R.string.workspace_cd_more),
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = actionsExpanded, onDismissRequest = { actionsExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workshop_script_edit)) },
                            onClick = { actionsExpanded = false; onEdit() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.workshop_script_clone)) },
                            onClick = { actionsExpanded = false; onClone() },
                        )
                        if (!script.isBuiltin) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.workspace_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = { actionsExpanded = false; onDelete() },
                            )
                        }
                    }
                }
            }
            if (script.description.isNotBlank()) Text(script.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectScriptBindingRow(projectName: String, projectType: ProjectType, scripts: List<BuildScriptEntity>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = scripts.firstOrNull { it.id == selectedId }
    RuntimeCard(onClick = { expanded = true }, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(projectName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${projectType.displayName} · ${selected?.name ?: stringResource(R.string.workshop_binding_standard)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(20.dp))
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.workshop_binding_standard)) }, onClick = { expanded = false; onSelect(null) })
                scripts.forEach { script -> DropdownMenuItem(text = { Text(script.name) }, onClick = { expanded = false; onSelect(script.id) }) }
            }
        }
    }
}

@Composable
private fun ManagedScriptEditorDialog(script: BuildScriptEntity?, onDismiss: () -> Unit, onSave: (String, String, ProjectType, String) -> String?) {
    val defaultAndroidTemplate = "#!/bin/sh\n# 天衍标准 Android 构建脚本入口\nset -eu\nPROJECT_DIR=\"\${1:-.}\"\nTASK=\"\${2:-assembleDebug}\"\nexec /bin/sh /opt/tianyan/scripts/tianyan-build.sh android \"\$PROJECT_DIR\" \"\$TASK\"\n"
    val defaultFlutterTemplate = "#!/bin/sh\n# 天衍标准 Flutter 构建脚本入口\nset -eu\nPROJECT_DIR=\"\${1:-.}\"\nTARGET=\"\${2:-apk --debug --target-platform android-arm64}\"\nexec /bin/sh /opt/tianyan/scripts/tianyan-build.sh flutter \"\$PROJECT_DIR\" \$TARGET\n"
    var name by androidx.compose.runtime.saveable.rememberSaveable(script?.id) { mutableStateOf(script?.name.orEmpty()) }
    var description by androidx.compose.runtime.saveable.rememberSaveable(script?.id) { mutableStateOf(script?.description.orEmpty()) }
    var type by androidx.compose.runtime.saveable.rememberSaveable(script?.id) { mutableStateOf(runCatching { ProjectType.valueOf(script?.projectType ?: ProjectType.ANDROID.name) }.getOrDefault(ProjectType.ANDROID)) }
    var content by androidx.compose.runtime.saveable.rememberSaveable(script?.id) { mutableStateOf(script?.content ?: if (type == ProjectType.FLUTTER) defaultFlutterTemplate else defaultAndroidTemplate) }
    var typeExpanded by remember { mutableStateOf(false) }
    var saveError by androidx.compose.runtime.saveable.rememberSaveable(script?.id) { mutableStateOf<String?>(null) }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (script == null) R.string.workshop_script_editor_new else R.string.workshop_script_editor_edit), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RuntimeFilledTextField(name, { name = it }, label = { Text(stringResource(R.string.workshop_script_field_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                RuntimeFilledTextField(description, { description = it }, label = { Text(stringResource(R.string.workshop_script_field_desc)) }, modifier = Modifier.fillMaxWidth())
                RuntimeOutlinedButton(onClick = { typeExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.workshop_script_type, type.displayName)) }
                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    listOf(ProjectType.ANDROID, ProjectType.FLUTTER).forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.displayName) },
                            onClick = {
                                if (script == null && (content == defaultAndroidTemplate || content == defaultFlutterTemplate || content.isBlank())) {
                                    content = if (candidate == ProjectType.FLUTTER) defaultFlutterTemplate else defaultAndroidTemplate
                                }
                                type = candidate
                                typeExpanded = false
                            },
                        )
                    }
                }
                CodeEditorPanel(content, { content = it }, "build.sh", Modifier.fillMaxWidth().height(260.dp))
                Text(stringResource(R.string.workshop_script_api_note, "\$1", "\$2"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saveError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { RuntimeButton(onClick = { saveError = onSave(name, description, type, content) }, enabled = name.isNotBlank() && content.isNotBlank()) { Text(stringResource(R.string.workshop_action_save)) } },
        dismissButton = { RuntimeOutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.workspace_cancel)) } },
    )
}

@Composable
fun WorkshopEnvironmentSettingsScreen(onBack: () -> Unit, viewModel: WorkshopSettingsViewModel? = previewOrNull { koinViewModel() }) {
    val draft by viewModel?.draft?.collectAsStateWithLifecycle() ?: previewStateOf(WorkshopEnvironmentDraft())
    val detected by viewModel?.detectedToolchains?.collectAsStateWithLifecycle() ?: previewStateOf(DetectedToolchains())

    RuntimeSettingsScaffold(stringResource(R.string.workshop_env_title), onBack) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        SectionHeader(stringResource(R.string.workshop_env_paths_title), stringResource(R.string.workshop_env_paths_desc))
                    }
                    RuntimeOutlinedButton(
                        onClick = { viewModel?.rescanToolchains() },
                        enabled = !detected.isScanning,
                    ) {
                        Text(if (detected.isScanning) stringResource(R.string.workshop_env_rescanning) else stringResource(R.string.workshop_env_rescan))
                    }
                }
            }
            item {
                RuntimeCard(shape = MaterialTheme.shapes.extraLarge) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ToolchainPathDropdownField("Gradle", draft.gradlePath, detected.gradleOptions) { viewModel?.update(draft.copy(gradlePath = it)) }
                        ToolchainPathDropdownField("Java / JDK", draft.javaPath, detected.javaOptions) { viewModel?.update(draft.copy(javaPath = it)) }
                        ToolchainPathDropdownField("Android NDK", draft.ndkPath, detected.ndkOptions) { viewModel?.update(draft.copy(ndkPath = it)) }
                        ToolchainPathDropdownField("AAPT2", draft.aapt2Path, detected.aapt2Options) { viewModel?.update(draft.copy(aapt2Path = it)) }
                        PathField("Android SDK", draft.androidSdkPath) { viewModel?.update(draft.copy(androidSdkPath = it)) }
                        PathField("Flutter SDK", draft.flutterSdkPath) { viewModel?.update(draft.copy(flutterSdkPath = it)) }
                        PathField("CMake", draft.cmakePath) { viewModel?.update(draft.copy(cmakePath = it)) }
                        PathField("Ninja", draft.ninjaPath) { viewModel?.update(draft.copy(ninjaPath = it)) }
                        PathField("Gradle 缓存", draft.gradleUserHome) { viewModel?.update(draft.copy(gradleUserHome = it)) }
                        PathField("Flutter Pub 缓存", draft.pubCache) { viewModel?.update(draft.copy(pubCache = it)) }
                        PathField("Android 工具目录 / ADB", draft.toolDir) { viewModel?.update(draft.copy(toolDir = it)) }
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RuntimeOutlinedButton(onClick = { viewModel?.resetEnvironment() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_action_reset)) }
                    RuntimeButton(onClick = { viewModel?.saveEnvironment() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_action_save)) }
                }
            }
    }
}

@Composable
private fun ToolchainPathDropdownField(
    label: String,
    value: String,
    options: List<ToolchainOption>,
    onValueChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (options.isNotEmpty()) {
                Surface(
                    onClick = { expanded = true },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                    Text(
                        stringResource(R.string.workshop_env_switch_version),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                        RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(14.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }

        RuntimeFilledTextField(
            value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = {
                if (options.isNotEmpty()) RuntimeIconButton(onClick = { expanded = true }, contentDescription = label) {
                    RuntimeIcon(RuntimeIconName.ChevronDown, Modifier.size(20.dp))
                }
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                val isSelected = option.path == value
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                )
                                if (option.isDetected) {
                                    Surface(
                                        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    ) {
                                        Text(
                                            stringResource(R.string.workshop_env_detected),
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                            Text(
                                option.path,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    },
                    onClick = {
                        onValueChange(option.path)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
fun WorkshopScriptEditorScreen(type: WorkshopScriptType, onBack: () -> Unit, viewModel: WorkshopSettingsViewModel? = previewOrNull { koinViewModel() }) {
    val draft by viewModel?.draft?.collectAsStateWithLifecycle() ?: previewStateOf(WorkshopEnvironmentDraft())
    val customScripts by viewModel?.customScripts?.collectAsStateWithLifecycle() ?: previewStateOf(emptySet())
    val initialContent = remember(type, draft) { viewModel?.scriptContent(type).orEmpty() }
    var content by remember(type, draft) { mutableStateOf(initialContent) }
    val isDirty = content != initialContent
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val isCustom = type in customScripts
    // 未保存修改时拦截返回键，避免静默丢失编辑
    BackHandler(enabled = isDirty && !showUnsavedDialog) { showUnsavedDialog = true }
    Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { RuntimeTopBar(type.title, onBack, if (isCustom) stringResource(R.string.workshop_script_screen_custom) else stringResource(R.string.workshop_script_screen_default)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScriptPathPanel(if (isCustom) type.customPath else type.defaultPath)
            CodeEditorPanel(value = content, onValueChange = { content = it }, fileName = type.defaultPath.substringAfterLast('/'), modifier = Modifier.fillMaxWidth().weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RuntimeOutlinedButton(onClick = { viewModel?.resetScript(type, onBack) }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_script_reset_default)) }
                RuntimeButton(onClick = { viewModel?.saveScript(type, content, onBack) }, enabled = isDirty, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.workshop_script_save)) }
            }
        }
    }
    if (showUnsavedDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { Text(stringResource(R.string.workshop_script_dirty_title)) },
            text = { Text(stringResource(R.string.workshop_script_dirty_message)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    showUnsavedDialog = false
                    viewModel?.saveScript(type, content, onBack)
                }) { Text(stringResource(R.string.workshop_script_save_and_exit)) }
            },
            dismissButton = {
                RuntimeOutlinedButton(onClick = {
                    showUnsavedDialog = false
                    onBack()
                }) { Text(stringResource(R.string.workshop_script_discard), color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable private fun SettingEntry(icon: RuntimeIconName, title: String, subtitle: String, onClick: () -> Unit) {
    RuntimeCard(onClick = onClick, shape = MaterialTheme.shapes.extraLarge, containerColor = MaterialTheme.colorScheme.surfaceContainer) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.largeIncreased, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) { RuntimeIcon(icon, Modifier.padding(9.dp), MaterialTheme.colorScheme.onSecondaryContainer) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    } }
}

@Composable private fun ScriptPathPanel(path: String) { RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(stringResource(R.string.workshop_script_path_title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)) } } }

@Composable
private fun PathField(label: String, value: String, onValueChange: (String) -> Unit) {
    RuntimeFilledTextField(
        value = value, onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

@Composable
private fun CodeEditorPanel(value: String, onValueChange: (String) -> Unit, fileName: String, modifier: Modifier = Modifier) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    Column(modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest, shape).border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f), shape)) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeIcon(RuntimeIconName.Code, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
            Text(fileName, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}
