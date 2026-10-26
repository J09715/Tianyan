package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.model.QuickPhrase
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeFloatingActionButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSwitch as Switch
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.components.RuntimeTopBar
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun QuickPhrasesScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val phrases by viewModel?.quickPhrases?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    // QuickPhrase 非 Parcelable：仅保存 id，旋转后从列表按 id 恢复编辑对象
    var editingPhraseId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val editingPhrase = editingPhraseId?.let { id -> phrases.firstOrNull { it.id == id } }
    var isCreating by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var showResetDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var selectedFilter by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>("ALL") }
    // 待确认删除的短语 id（破坏性操作二次确认）
    var pendingDeletePhraseId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    val pendingDeletePhrase = pendingDeletePhraseId?.let { id -> phrases.firstOrNull { it.id == id } }

    val filterOptions = listOf(
        "ALL" to "全部 (${phrases.size})",
        "GENERAL" to "通用",
        "ANDROID" to "Android",
        "FLUTTER" to "Flutter",
        "REVERSE" to "逆向分析",
    )

    val filteredPhrases = remember(phrases, selectedFilter) {
        when (selectedFilter) {
            "ALL" -> phrases
            "GENERAL" -> phrases.filter { it.targetProjectType == null }
            "ANDROID" -> phrases.filter { it.targetProjectType == "ANDROID" }
            "FLUTTER" -> phrases.filter { it.targetProjectType == "FLUTTER" }
            "REVERSE" -> phrases.filter { it.targetProjectType == "REVERSE" }
            else -> phrases
        }
    }

    if (showResetDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("重置快捷短语预设", fontWeight = FontWeight.Bold) },
            text = { Text("确定要恢复出厂默认的快捷短语与指令吗？这将清除你自定义添加的短语。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.resetQuickPhrasesToDefault()
                        showResetDialog = false
                    },
                ) {
                    Text("确认重置", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    if (isCreating || editingPhrase != null) {
        QuickPhraseEditorDialog(
            phrase = editingPhrase,
            onDismiss = {
                isCreating = false
                editingPhraseId = null
            },
            onSave = { id, title, content, description, iconName, targetProjectType, isEnabled ->
                viewModel?.saveQuickPhrase(
                    id = id,
                    title = title,
                    content = content,
                    description = description,
                    iconName = iconName,
                    targetProjectType = targetProjectType,
                    isEnabled = isEnabled,
                )
                isCreating = false
                editingPhraseId = null
            },
        )
    }

    pendingDeletePhrase?.let { phrase ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeletePhraseId = null },
            title = { Text("删除快捷短语", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除「${phrase.title}」吗？删除后不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel?.deleteQuickPhrase(phrase.id)
                        pendingDeletePhraseId = null
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletePhraseId = null }) {
                    Text("取消")
                }
            },
        )
    }

    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RuntimeTopBar(
                title = "快捷短语与常用指令",
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    IconButton(
                        onClick = { showResetDialog = true },
                        contentDescription = "重置快捷短语预设",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Refresh,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(
                        onClick = { isCreating = true },
                        contentDescription = "新增快捷短语",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Plus,
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            RuntimeFloatingActionButton(
                onClick = { isCreating = true },
            ) {
                RuntimeIcon(RuntimeIconName.Plus, Modifier.size(24.dp))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding)),
        ) {
            // 分类筛选胶囊
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                filterOptions.forEach { (key, label) ->
                    val isSelected = selectedFilter == key
                    RuntimeFilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = key },
                        label = {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                ),
                            )
                        },
                    )
                }
            }

            if (filteredPhrases.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Chat,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                        Text(
                            text = "暂无匹配的快捷短语",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { isCreating = true }) {
                            Text("添加首条快捷短语")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = top.tianyan.app.ui.components.runtimeScrollContentPadding(padding, top.tianyan.app.ui.components.runtimeContentPadding(horizontal = 16.dp, top = 16.dp, bottom = 96.dp)),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(filteredPhrases, key = { it.id }) { phrase ->
                        QuickPhraseCard(
                            phrase = phrase,
                            onToggle = { enabled -> viewModel?.toggleQuickPhrase(phrase.id, enabled) },
                            onEdit = { editingPhraseId = phrase.id },
                            onDelete = { pendingDeletePhraseId = phrase.id },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickPhraseEditorDialog(
    phrase: QuickPhrase?,
    onDismiss: () -> Unit,
    onSave: (
        id: String?,
        title: String,
        content: String,
        description: String,
        iconName: String,
        targetProjectType: String?,
        isEnabled: Boolean,
    ) -> Unit,
) {
    var title by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.title.orEmpty()) }
    var description by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.description.orEmpty()) }
    var content by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.content.orEmpty()) }
    var iconName by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.iconName ?: "Play") }
    var targetProjectType by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.targetProjectType) }
    var isEnabled by androidx.compose.runtime.saveable.rememberSaveable(phrase?.id) { mutableStateOf(phrase?.isEnabled ?: true) }
    var projectTypeDropdownExpanded by remember { mutableStateOf(false) }

    val iconOptions = listOf(
        "Play" to RuntimeIconName.Play,
        "Check" to RuntimeIconName.Check,
        "Alert" to RuntimeIconName.Alert,
        "Code" to RuntimeIconName.Code,
        "Plus" to RuntimeIconName.Plus,
        "Package" to RuntimeIconName.Package,
        "Search" to RuntimeIconName.Search,
        "Brain" to RuntimeIconName.Brain,
        "Bot" to RuntimeIconName.Bot,
        "Chat" to RuntimeIconName.Chat,
        "Refresh" to RuntimeIconName.Refresh,
        "Terminal" to RuntimeIconName.Terminal,
        "Tool" to RuntimeIconName.Wrench,
    )

    val projectTypeOptions = listOf(
        null to "通用（所有会话可见）",
        "ANDROID" to "Android 工程专属",
        "FLUTTER" to "Flutter 工程专属",
        "REVERSE" to "逆向分析专属",
    )

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (phrase == null) "新增快捷短语" else "编辑快捷短语",
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("短语标题") },
                    placeholder = { Text("例如：运行代码 / 代码审查") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("简短说明（可选）") },
                    placeholder = { Text("例如：执行当前工作区的单元测试") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("提示词 / 命令模板") },
                    placeholder = { Text("输入要自动填入或触发的指令/Prompt...") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 目标项目类型下拉选择
                ExposedDropdownMenuBox(
                    expanded = projectTypeDropdownExpanded,
                    onExpandedChange = { projectTypeDropdownExpanded = !projectTypeDropdownExpanded },
                ) {
                    OutlinedTextField(
                        value = projectTypeOptions.firstOrNull { it.first == targetProjectType }?.second ?: "通用",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("适用工程类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(projectTypeDropdownExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = projectTypeDropdownExpanded,
                        onDismissRequest = { projectTypeDropdownExpanded = false },
                    ) {
                        projectTypeOptions.forEach { (typeKey, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    targetProjectType = typeKey
                                    projectTypeDropdownExpanded = false
                                },
                            )
                        }
                    }
                }

                // 图标选择器
                Text("选择图标：", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    iconOptions.forEach { (name, icon) ->
                        val isSelected = iconName == name
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .minimumInteractiveComponentSize()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                                )
                                .clickable { iconName = name },
                            contentAlignment = Alignment.Center,
                        ) {
                            RuntimeIcon(
                                name = icon,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isNotBlank() && content.isNotBlank()) {
                        onSave(
                            phrase?.id,
                            title,
                            content,
                            description,
                            iconName,
                            targetProjectType,
                            isEnabled,
                        )
                    }
                },
                enabled = title.isNotBlank() && content.isNotBlank(),
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
