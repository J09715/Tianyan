package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.AiModelProfileExport
import top.tianyan.app.harness.ModelContextWindows
import top.tianyan.app.ui.components.IconTile
import top.tianyan.app.ui.components.ProviderBadge
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeCheckbox
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.RuntimeTopBar
import java.util.Locale
/**
 * 模型档案管理全屏独立页面
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelProfilesScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    viewModel: SettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val models by viewModel?.models?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showImportDialog by remember { mutableStateOf(false) }
    var showExportAllDialog by remember { mutableStateOf(false) }
    var singleExportModel by remember { mutableStateOf<AiModelEntity?>(null) }
    var modelPendingDelete by remember { mutableStateOf<AiModelEntity?>(null) }
    var importing by remember { mutableStateOf(false) }

    val scrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RuntimeTopBar(
                title = "模型档案",
                onBack = onBack,
                scrollBehavior = scrollBehavior,
                actions = {
                    // 📥 导入按钮
                    RuntimeIconButton(
                        onClick = { showImportDialog = true },
                        contentDescription = "导入模型配置",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.Download,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    // 📤 导出全部按钮
                    RuntimeIconButton(
                        onClick = {
                            if (models.isEmpty()) {
                                Toast.makeText(context, "暂无模型档案可导出", Toast.LENGTH_SHORT).show()
                            } else {
                                showExportAllDialog = true
                            }
                        },
                        contentDescription = "导出全部模型配置",
                    ) {
                        RuntimeIcon(
                            name = RuntimeIconName.OpenInNew,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        ModelProfilesContent(
            modifier = top.tianyan.app.ui.components.runtimeScrollViewport(padding), bottomPadding = padding.calculateBottomPadding(),
            models = models,
            onCreate = onCreate,
            onEdit = { model -> onEdit(model.id) },
            onActivate = { arg0 -> viewModel?.setActiveModel(arg0) },
            onDelete = { model -> modelPendingDelete = model },
            onExportSingle = { model -> singleExportModel = model },
        )
    }

    // 导入弹窗
    if (showImportDialog) {
        ModelImportDialog(
            onDismiss = { showImportDialog = false },
            importing = importing,
            models = models,
            parseProfiles = { raw -> viewModel?.parseProfilesFromJson(raw) ?: Result.failure(IllegalStateException()) },
            onImportJson = { jsonStr, mode ->
                if (importing) return@ModelImportDialog
                importing = true
                coroutineScope.launch {
                    val result = viewModel?.importProfilesFromJson(jsonStr, mode)
                    importing = false
                    result?.fold(
                        onSuccess = { count ->
                            Toast.makeText(context, "成功导入 $count 个模型档案", Toast.LENGTH_SHORT).show()
                            showImportDialog = false
                        },
                        onFailure = { _ ->
                            Toast.makeText(context, "导入失败：JSON 无法解析或配置无效，请检查后重试", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            },
        )
    }

    // 批量导出弹窗
    if (showExportAllDialog) {
        ModelExportDialog(
            title = "批量导出模型档案",
            subtitle = "准备导出 ${models.size} 个模型档案配置",
            onDismiss = { showExportAllDialog = false },
            onGenerateJson = { includeKeys ->
                viewModel?.exportAllProfilesJson(includeKeys).orEmpty()
            },
        )
    }

    // 单项导出弹窗
    singleExportModel?.let { targetModel ->
        ModelExportDialog(
            title = "导出模型档案配置",
            subtitle = "模型: ${targetModel.name} (${targetModel.provider})",
            defaultFileName = "tianyan_model_${targetModel.name.replace(" ", "_")}.json",
            onDismiss = { singleExportModel = null },
            onGenerateJson = { includeKeys ->
                viewModel?.exportSingleProfileJson(targetModel.id, includeKeys) ?: ""
            },
        )
    }

    // 删除确认弹窗
    modelPendingDelete?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = { modelPendingDelete = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeIcon(RuntimeIconName.Alert, Modifier.size(20.dp), MaterialTheme.colorScheme.error)
                    Text("删除模型档案")
                }
            },
            text = { Text("确定要删除模型档案「${target.name}」吗？此操作将同时清理该模型保存的 API Key。") },
            confirmButton = {
                RuntimeTextButton(
                    onClick = {
                        viewModel?.deleteModel(target.id)
                        modelPendingDelete = null
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { modelPendingDelete = null }) {
                    Text("取消")
                }
            },
        )
    }
}
