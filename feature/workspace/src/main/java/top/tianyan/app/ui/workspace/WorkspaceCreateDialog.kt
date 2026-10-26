package top.tianyan.app.ui.workspace

import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.ApkImportSource
import top.tianyan.app.runtime.ProjectTemplate
import top.tianyan.app.template.InstalledProjectTemplate
import top.tianyan.app.template.ProjectTemplateInputType
import top.tianyan.app.template.TemplateProjectType
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCheckbox as Checkbox
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton

/** 创建项目向导步骤：平台选择 → 空项目详情 / 模板选择 → 模板详情。 */
internal enum class CreateProjectStep { PROJECT_TYPE, EMPTY_DETAILS, TEMPLATE, DETAILS }

/**
 * 创建项目向导状态（屏幕级持有，rememberSaveable 保证旋转后进度不丢）。
 * 复杂对象（selectedTemplate / apkSource）按原语义只保留 remember，旋转后需重新选择。
 */
@Stable
internal class CreateProjectWizardState(
    step: MutableState<CreateProjectStep>,
    selectedTemplate: MutableState<ProjectTemplate>,
    selectedProjectType: MutableState<TemplateProjectType?>,
    selectedTemplateId: MutableState<String?>,
    projectName: MutableState<String>,
    packageName: MutableState<String>,
    templateVariableValues: MutableState<Map<String, String>>,
    trustTemplateScripts: MutableState<Boolean>,
    apkSource: MutableState<ApkImportSource?>,
    showAppPicker: MutableState<Boolean>,
    exportApkToDownload: MutableState<Boolean>,
    createInProgress: MutableState<Boolean>,
) {
    var step by step
    var selectedTemplate by selectedTemplate
    var selectedProjectType by selectedProjectType
    var selectedTemplateId by selectedTemplateId
    var projectName by projectName
    var packageName by packageName
    var templateVariableValues by templateVariableValues
    var trustTemplateScripts by trustTemplateScripts
    var apkSource by apkSource
    var showAppPicker by showAppPicker
    var exportApkToDownload by exportApkToDownload
    var createInProgress by createInProgress

    /** 关闭或创建完成后复位向导（selectedTemplate 恒为 EMPTY，无需复位）。 */
    fun reset() {
        step = CreateProjectStep.PROJECT_TYPE
        projectName = ""
        packageName = ""
        templateVariableValues = emptyMap()
        apkSource = null
        exportApkToDownload = false
        selectedProjectType = null
        selectedTemplateId = null
        trustTemplateScripts = false
        createInProgress = false
    }
}

@Composable
internal fun rememberCreateProjectWizardState(): CreateProjectWizardState {
    // TemplateProjectType? 不能直接进 Bundle，用 name 字符串自定义 Saver（valueOf 容错未知值）
    val nullableProjectTypeSaver = Saver<TemplateProjectType?, String>(
        save = { it?.name },
        restore = { name -> runCatching { TemplateProjectType.valueOf(name) }.getOrNull() },
    )
    // Map 不能直接进 Bundle，用 mapSaver 保存（键值均为 String）
    val templateVariableValuesSaver = mapSaver(
        save = { map -> map },
        restore = { saved -> saved.mapValues { it.value.toString() } },
    )
    return CreateProjectWizardState(
        step = rememberSaveable { mutableStateOf(CreateProjectStep.PROJECT_TYPE) },
        selectedTemplate = remember { mutableStateOf(ProjectTemplate.EMPTY) },
        selectedProjectType = rememberSaveable(stateSaver = nullableProjectTypeSaver) { mutableStateOf<TemplateProjectType?>(null) },
        selectedTemplateId = rememberSaveable { mutableStateOf<String?>(null) },
        projectName = rememberSaveable { mutableStateOf("") },
        packageName = rememberSaveable { mutableStateOf("") },
        templateVariableValues = rememberSaveable(stateSaver = templateVariableValuesSaver) { mutableStateOf<Map<String, String>>(emptyMap()) },
        trustTemplateScripts = rememberSaveable { mutableStateOf(false) },
        apkSource = remember { mutableStateOf<ApkImportSource?>(null) },
        showAppPicker = rememberSaveable { mutableStateOf(false) },
        exportApkToDownload = rememberSaveable { mutableStateOf(false) },
        createInProgress = rememberSaveable { mutableStateOf(false) },
    )
}

/**
 * 创建项目向导弹窗：四步流程（平台 → 模板 → 详情），
 * 创建进行中保持弹窗展示进度，完成后统一关闭并复位（模板实例化可能耗时 60 秒）。
 */
@Composable
internal fun CreateProjectDialog(
    state: CreateProjectWizardState,
    viewModel: WorkspaceViewModel,
    projectTemplates: List<InstalledProjectTemplate>,
    busy: Boolean,
    runtimeReady: Boolean,
    installedComponentIds: Set<String>,
    apkPicker: ActivityResultLauncher<Array<String>>,
    onDismiss: () -> Unit,
    onOpenToolCenter: () -> Unit,
) {
    // 创建进行中：保持弹窗展示进度，完成后统一关闭并复位
    LaunchedEffect(state.createInProgress, busy) {
        if (state.createInProgress && !busy) {
            state.reset()
            onDismiss()
        }
    }
    RuntimeAlertDialog(
        onDismissRequest = {
            if (!state.createInProgress) {
                state.reset()
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.workspace_new_project), fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.step) {
                    CreateProjectStep.PROJECT_TYPE -> {
                        Text("选择项目类型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("先选择要创建的项目平台", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val projectTypes = projectTemplates.map { it.manifest.projectType }.distinct()
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // 空项目：跳过平台与模板选择，直接输入名称创建空文件夹
                            RuntimeCard(shape = MaterialTheme.shapes.extraLarge,
                                onClick = {
                                    state.selectedProjectType = null
                                    state.selectedTemplateId = null
                                    state.selectedTemplate = ProjectTemplate.EMPTY
                                    state.templateVariableValues = emptyMap()
                                    state.trustTemplateScripts = false
                                    state.packageName = ""
                                    state.step = CreateProjectStep.EMPTY_DETAILS
                                },
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Folder, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("空项目", fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "只创建一个空文件夹，随时手动添加内容",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            projectTypes.chunked(2).forEach { rowTypes ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowTypes.forEach { type ->
                                        RuntimeCard(shape = MaterialTheme.shapes.extraLarge,
                                            onClick = {
                                                state.selectedProjectType = type
                                                state.selectedTemplateId = null
                                            },
                                            modifier = Modifier.weight(1f),
                                            containerColor = if (state.selectedProjectType == type) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainer
                                            },
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                                RuntimeIcon(
                                                    when (type) {
                                                        TemplateProjectType.ANDROID -> RuntimeIconName.Android
                                                        TemplateProjectType.FLUTTER -> RuntimeIconName.Flutter
                                                        TemplateProjectType.GENERAL, TemplateProjectType.TIANYAN_PLUGIN -> RuntimeIconName.Code
                                                    },
                                                    Modifier.size(36.dp),
                                                    MaterialTheme.colorScheme.primary,
                                                )
                                                Spacer(Modifier.height(8.dp))
                                                Text(if (type == TemplateProjectType.TIANYAN_PLUGIN) stringResource(R.string.workspace_project_tianyan_plugin) else type.name.lowercase().replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                                            }
                                        }
                                    }
                                    repeat(2 - rowTypes.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    CreateProjectStep.EMPTY_DETAILS -> {
                        Text("新建空项目", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("输入名称即创建一个空文件夹", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RuntimeFilledTextField(
                            value = state.projectName,
                            onValueChange = { state.projectName = it },
                            label = { Text(stringResource(R.string.workspace_project_name)) },
                            placeholder = { Text("my-sandbox") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val emptyPath = state.projectName.trim()
                        if (emptyPath.isNotBlank()) Text(
                            stringResource(R.string.workspace_sandbox_path, "/workspace", emptyPath),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    CreateProjectStep.TEMPLATE -> {
                        Text("选择模板", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("选择一个具体模板后继续", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val filteredTemplates = projectTemplates.filter { it.manifest.projectType == state.selectedProjectType }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            filteredTemplates.groupBy { it.manifest.category }.forEach { (category, templates) ->
                                if (filteredTemplates.map { it.manifest.category.id }.distinct().size > 1) {
                                    Text(category.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                }
                                templates.chunked(2).forEach { rowTemplates ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowTemplates.forEach { template ->
                                            val selected = state.selectedTemplateId == template.manifest.id
                                            RuntimeCard(shape = MaterialTheme.shapes.extraLarge,
                                                onClick = {
                                                    state.selectedTemplateId = template.manifest.id
                                                    state.trustTemplateScripts = false
                                                    // 标准模板一律通过 manifest id 创建，避免 UI 依赖内置模板枚举。
                                                    state.selectedTemplate = ProjectTemplate.EMPTY
                                                    state.templateVariableValues = template.manifest.variables
                                                        .filter { it.prompt }
                                                        .associate { variable ->
                                                            variable.name to when {
                                                                variable.defaultValue.isNotBlank() -> variable.defaultValue
                                                                variable.inputType == ProjectTemplateInputType.BOOLEAN -> "false"
                                                                variable.inputType == ProjectTemplateInputType.SELECT && variable.required ->
                                                                    variable.options.firstOrNull()?.value.orEmpty()
                                                                else -> ""
                                                            }
                                                        }
                                                },
                                                modifier = Modifier.weight(1f),
                                                containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                                            ) {
                                                Surface(
                                                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                                                    shape = RoundedCornerShape(12.dp),
                                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                                ) {
                                                    Box(contentAlignment = Alignment.Center) {
                                                        val previewFile = template.previewFile
                                                        if (previewFile != null) {
                                                            TemplatePreviewImage(previewFile, Modifier.fillMaxSize())
                                                        } else {
                                                            RuntimeIcon(
                                                                when (template.manifest.projectType) {
                                                                    TemplateProjectType.ANDROID -> RuntimeIconName.Android
                                                                    TemplateProjectType.FLUTTER -> RuntimeIconName.Flutter
                                                                    TemplateProjectType.GENERAL, TemplateProjectType.TIANYAN_PLUGIN -> RuntimeIconName.Code
                                                                },
                                                                Modifier.size(42.dp),
                                                                MaterialTheme.colorScheme.primary,
                                                            )
                                                        }
                                                    }
                                                }
                                                Spacer(Modifier.height(8.dp))
                                                Text(
                                                    template.manifest.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(horizontal = 4.dp).padding(bottom = 4.dp),
                                                )
                                            }
                                        }
                                        repeat(2 - rowTemplates.size) {
                                            Spacer(Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    CreateProjectStep.DETAILS -> {
                        val selectedManifest = projectTemplates.firstOrNull {
                            it.manifest.id == state.selectedTemplateId
                        }?.manifest
                        val projectNameVariable = selectedManifest?.variables?.firstOrNull {
                            it.name == "projectName"
                        }
                        val projectNameError = projectNameVariable?.let { variable ->
                            templateVariableError(variable, state.projectName)?.let {
                                variable.description.ifBlank { it }
                            }
                        }

                        RuntimeFilledTextField(
                            value = state.projectName,
                            onValueChange = {
                                state.projectName = it
                                if (state.packageName.isBlank() || state.packageName.startsWith("com.example.")) {
                                    state.packageName = "com.example.${it.lowercase().filter { c -> c.isLetterOrDigit() }}"
                                }
                            },
                            label = { Text(stringResource(R.string.workspace_project_name)) },
                            placeholder = { Text("MyApplication / demo-app") },
                            supportingText = projectNameError?.let { error -> { Text(error) } },
                            isError = projectNameError != null,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        // ============ APK 逆向模板：选择安装包来源 ============
                        if (state.selectedTemplate == ProjectTemplate.APK_REVERSE) {
                            Text(
                                stringResource(R.string.workspace_choose_apk_source),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = { state.showAppPicker = true },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Package, Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(stringResource(R.string.workspace_extract_installed), style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        apkPicker.launch(
                                            arrayOf(
                                                "application/vnd.android.package-archive",
                                                "application/octet-stream",
                                            ),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    RuntimeIcon(RuntimeIconName.Folder, Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text(stringResource(R.string.workspace_choose_apk_file), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Text(
                                stringResource(R.string.workspace_apk_source_description),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // 逆向工具链就绪状态：避免"建好工程却发现 jadx/apktool 缺失"
                            val reverseToolReady = "android-re" in installedComponentIds && runtimeReady
                            val statusSurfaceColor = when {
                                reverseToolReady -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
                                runtimeReady -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                                else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f)
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = statusSurfaceColor,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    RuntimeIcon(
                                        when {
                                            reverseToolReady -> RuntimeIconName.Check
                                            runtimeReady -> RuntimeIconName.Alert
                                            else -> RuntimeIconName.Info
                                        },
                                        Modifier.size(17.dp),
                                        tint = when {
                                            reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer
                                            runtimeReady -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = when {
                                                reverseToolReady -> stringResource(R.string.workspace_reverse_ready)
                                                runtimeReady -> stringResource(R.string.workspace_reverse_missing)
                                                else -> stringResource(R.string.workspace_runtime_uninitialized)
                                            },
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = when {
                                                reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer
                                                runtimeReady -> MaterialTheme.colorScheme.onErrorContainer
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                        Text(
                                            text = when {
                                                reverseToolReady -> stringResource(R.string.workspace_reverse_output_ready)
                                                runtimeReady -> stringResource(R.string.workspace_reverse_component_needed)
                                                else -> stringResource(R.string.workspace_apk_creation_available)
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = when {
                                                reverseToolReady -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                                                runtimeReady -> MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                            },
                                        )
                                    }
                                    if (!reverseToolReady && runtimeReady) {
                                        TextButton(onClick = {
                                            onDismiss()
                                            onOpenToolCenter()
                                        }) {
                                            Text(stringResource(R.string.workspace_install_components), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                            state.apkSource?.let { source ->
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
                                        RuntimeIcon(RuntimeIconName.Reverse, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = source.displayName,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = when (source) {
                                                    is ApkImportSource.FromInstalledApp -> stringResource(R.string.workspace_installed_app_source)
                                                    is ApkImportSource.FromFileUri -> stringResource(R.string.workspace_apk_file_source)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        RuntimeIconButton(
                                            onClick = { state.apkSource = null },
                                            modifier = Modifier
                                                .size(30.dp)
                                                .minimumInteractiveComponentSize(),
                                            contentDescription = stringResource(R.string.workspace_cd_close),
                                        ) {
                                            RuntimeIcon(RuntimeIconName.Close, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            // 同时导出 APK 到宿主公共下载目录（供 MT 管理器等宿主侧工具直接打开）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { state.exportApkToDownload = !state.exportApkToDownload }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Checkbox(
                                    checked = state.exportApkToDownload,
                                    onCheckedChange = { state.exportApkToDownload = it },
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        stringResource(R.string.workspace_export_apk),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        stringResource(R.string.workspace_export_apk_description),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }

                        val path = state.projectName.trim()
                        if (path.isNotBlank()) Text(
                            stringResource(R.string.workspace_sandbox_path, "/workspace", path),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (selectedManifest != null) {
                            TemplateVariableFields(
                                variables = selectedManifest.variables,
                                values = state.templateVariableValues + ("packageName" to state.packageName),
                                onValueChange = { name, value ->
                                    if (name == "packageName") state.packageName = value
                                    else state.templateVariableValues = state.templateVariableValues + (name to value)
                                },
                            )
                            val hasTemplateScripts = selectedManifest.hooks.beforeCreate.isNotBlank() ||
                                selectedManifest.hooks.afterCreate.isNotBlank()
                            if (hasTemplateScripts) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .clickable { state.trustTemplateScripts = !state.trustTemplateScripts }
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Checkbox(checked = state.trustTemplateScripts, onCheckedChange = { state.trustTemplateScripts = it })
                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(stringResource(R.string.workspace_trust_scripts_title), fontWeight = FontWeight.SemiBold)
                                            Text(
                                                if (runtimeReady) {
                                                    stringResource(R.string.workspace_trust_scripts_hint_runtime)
                                                } else {
                                                    stringResource(R.string.workspace_trust_scripts_hint_not_ready)
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            )
                                            TextButton(onClick = {
                                                state.selectedTemplateId?.let(viewModel::showTemplateScripts)
                                            }) { Text(stringResource(R.string.workspace_view_scripts)) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    when (state.step) {
                        CreateProjectStep.PROJECT_TYPE -> state.step = CreateProjectStep.TEMPLATE
                        CreateProjectStep.EMPTY_DETAILS -> {
                            state.createInProgress = true
                            viewModel.create(
                                name = state.projectName,
                                template = ProjectTemplate.EMPTY,
                            )
                        }
                        CreateProjectStep.TEMPLATE -> state.step = CreateProjectStep.DETAILS
                        CreateProjectStep.DETAILS -> {
                            state.createInProgress = true
                            viewModel.create(
                                name = state.projectName,
                                template = state.selectedTemplate,
                                packageName = state.packageName,
                                apkSource = state.apkSource,
                                exportApkToDownload = state.exportApkToDownload,
                                templateVariables = state.templateVariableValues + ("packageName" to state.packageName),
                                templateId = state.selectedTemplateId.orEmpty(),
                                trustTemplateScripts = state.trustTemplateScripts,
                            )
                        }
                    }
                },
                enabled = !state.createInProgress && when (state.step) {
                    CreateProjectStep.PROJECT_TYPE -> state.selectedProjectType != null
                    CreateProjectStep.EMPTY_DETAILS -> state.projectName.isNotBlank() && !busy
                    CreateProjectStep.TEMPLATE -> state.selectedTemplateId != null
                    CreateProjectStep.DETAILS -> state.projectName.isNotBlank() && !busy &&
                        run {
                            val variables = projectTemplates.firstOrNull { it.manifest.id == state.selectedTemplateId }
                                ?.manifest?.variables.orEmpty().filter { it.prompt }
                            val values = state.templateVariableValues + ("packageName" to state.packageName)
                            val manifest = projectTemplates.firstOrNull { it.manifest.id == state.selectedTemplateId }?.manifest
                            val hasScripts = manifest != null &&
                                (manifest.hooks.beforeCreate.isNotBlank() || manifest.hooks.afterCreate.isNotBlank())
                            val scriptsReady = !hasScripts || (state.trustTemplateScripts && runtimeReady)
                            val projectNameRule = manifest?.variables?.firstOrNull { it.name == "projectName" }
                            val projectNameValid = projectNameRule == null ||
                                templateVariableError(projectNameRule, state.projectName) == null
                            scriptsReady && projectNameValid && variables.none {
                                templateVariableError(it, values[it.name] ?: it.defaultValue) != null
                            }
                        }
                },
            ) {
                if (state.createInProgress) {
                    RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.workspace_creating), color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        if (state.step == CreateProjectStep.DETAILS || state.step == CreateProjectStep.EMPTY_DETAILS) {
                            stringResource(R.string.workspace_create)
                        } else {
                            stringResource(R.string.workspace_action_next)
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    when (state.step) {
                        CreateProjectStep.PROJECT_TYPE -> {
                            state.reset()
                            onDismiss()
                        }
                        CreateProjectStep.EMPTY_DETAILS -> state.step = CreateProjectStep.PROJECT_TYPE
                        CreateProjectStep.TEMPLATE -> state.step = CreateProjectStep.PROJECT_TYPE
                        CreateProjectStep.DETAILS -> state.step = CreateProjectStep.TEMPLATE
                    }
                },
                enabled = !state.createInProgress,
            ) {
                Text(if (state.step == CreateProjectStep.PROJECT_TYPE) stringResource(R.string.workspace_cancel) else stringResource(R.string.workspace_action_previous))
            }
        },
    )

    // APK 逆向模板：已安装应用选择弹窗
    if (state.showAppPicker) {
        val context = LocalContext.current
        AppPickerDialog(
            onDismiss = { state.showAppPicker = false },
            onSelect = { app ->
                state.apkSource = ApkImportSource.FromInstalledApp(app.packageName, app.appLabel(context))
                state.showAppPicker = false
            },
        )
    }
}
