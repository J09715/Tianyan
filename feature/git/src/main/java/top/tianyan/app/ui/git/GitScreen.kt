package top.tianyan.app.ui.git
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.git.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton
import android.widget.Toast
/**
 * 分支管理（参考 MGit）：
 * 分支列表/切换/新建/删除 + 多色提交记录树（哈希/作者/时间）+ HTTPS Token 推送拉取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(
    projectName: String,
    onBack: () -> Unit,
    viewModel: GitViewModel? = previewOrNull { koinViewModel() },
) {
    val state by viewModel?.uiState?.collectAsStateWithLifecycle() ?: previewStateOf(GitUiState())
    val context = LocalContext.current

    LaunchedEffect(projectName) { viewModel?.bind(projectName) }

    LaunchedEffect(state.notice) {
        state.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel?.clearNotice()
        }
    }

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateBranch by remember { mutableStateOf(false) }
    var showCredentials by remember { mutableStateOf(false) }
    var showCreateTag by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<GitBranchInfo?>(null) }
    var deleteTagTarget by remember { mutableStateOf<String?>(null) }
    var discardTarget by remember { mutableStateOf<GitManager.GitFileChange?>(null) }
    var pushPreview by remember { mutableStateOf<List<Pair<String, String>>?>(null) }

    // 从其他页面/后台返回时自动刷新（agent 可能改了工作区）。
    // 轻量版：跳过提交树 RevWalk 全量遍历，避免返回时 IO/GC 压力拖累转场动画。
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel?.refreshLight()
        onPauseOrDispose { }
    }

    // 与 TerminalScreen/CodeEditorScreen 对齐：不透明背景，避免转场动画期间下层页面透出
    // （无背景时转场中下层 ChatScreen 仍全量渲染并可见，既视觉怪异又掉帧）
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RuntimeIconButton(onClick = onBack) {
                RuntimeIcon(RuntimeIconName.Back, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fgit_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = projectName,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RuntimeIconButton(onClick = { viewModel?.refresh() }) {
                RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RuntimeIconButton(onClick = { showCredentials = true }) {
                RuntimeIcon(RuntimeIconName.Key, Modifier.size(19.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        when {
            state.loading && state.overview == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RuntimeCircularProgressIndicator(Modifier.size(26.dp))
                    Text(
                        stringResource(R.string.fgit_loading),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            !state.isRepository -> NotARepoContent()

            else -> {
                val overview = state.overview
                if (overview != null) {
                    RepoHeaderCard(
                        overview = overview,
                        busy = state.busy,
                        operation = state.operation,
                        progress = state.progress,
                        onPull = { viewModel?.pull() },
                        onPush = { viewModel?.loadPushPreview { preview -> pushPreview = preview } },
                    )
                }

                // 浅克隆提示：--depth 1 导入的仓库只有最近 1 个提交，需补全历史才能看到完整提交树
                if (state.isShallow) {
                    ShallowRepoCard(
                        busy = state.busy,
                        operation = state.operation,
                        progress = state.progress,
                        onUnshallow = { viewModel?.unshallow() },
                    )
                }

                state.error?.let { errorText ->
                    RuntimeCard(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentPadding = PaddingValues(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(
                            errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }

                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    // 页签文案统一两字 + 数量角标，防止窄屏下换行（"提交记录"曾折成两行）
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                if (state.overview == null || state.overview!!.localBranches.isEmpty()) {
                                    stringResource(R.string.fgit_tab_branches)
                                } else {
                                    stringResource(R.string.fgit_tab_branches_count, state.overview!!.localBranches.size)
                                },
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Text(
                                stringResource(R.string.fgit_tab_commits),
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = {
                            Text(
                                if (state.changes.isEmpty()) {
                                    stringResource(R.string.fgit_tab_changes)
                                } else {
                                    stringResource(R.string.fgit_tab_changes_count, state.changes.size)
                                },
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = {
                            Text(
                                if (state.tags.isEmpty()) {
                                    stringResource(R.string.fgit_tab_tags)
                                } else {
                                    stringResource(R.string.fgit_tab_tags_count, state.tags.size)
                                },
                                maxLines = 1,
                                softWrap = false,
                            )
                        },
                    )
                }

                when (selectedTab) {
                    0 -> BranchesTab(
                        state = state,
                        onCheckout = { arg0 -> viewModel?.checkout(arg0) },
                        onDelete = { deleteTarget = it },
                        onCreate = { showCreateBranch = true },
                    )
                    1 -> CommitsTab(
                        state = state,
                        onToggleDetail = { arg0 -> viewModel?.loadCommitDetail(arg0) },
                    )
                    2 -> ChangesTab(
                        state = state,
                        onToggleStage = { arg0 -> viewModel?.stageFile(arg0) },
                        onStageAll = { viewModel?.stageAll() },
                        onUnstageAll = { viewModel?.unstageAll() },
                        onDiscard = { discardTarget = it },
                        onCommit = { arg0 -> viewModel?.commit(arg0) },
                        onCommitAndPush = { arg0 -> viewModel?.commitAndPush(arg0) },
                        onGenerateMessage = { viewModel?.generateCommitMessage() },
                        onMessageChange = { arg0 -> viewModel?.updateCommitMessageDraft(arg0) },
                    )
                    else -> TagsTab(
                        state = state,
                        onCreate = { showCreateTag = true },
                        onDelete = { deleteTagTarget = it },
                    )
                }
            }
        }
        }
    }

    if (showCreateBranch) {
        CreateBranchDialog(
            currentBranch = state.overview?.currentBranch.orEmpty(),
            onDismiss = { showCreateBranch = false },
            onCreate = { name ->
                showCreateBranch = false
                viewModel?.createBranch(name)
            },
        )
    }

    if (showCredentials) {
        CredentialsDialog(
            remoteUrl = state.overview?.remoteUrl.orEmpty(),
            savedHosts = state.credentialHosts,
            onDismiss = { showCredentials = false },
            onSave = { host, user, token ->
                viewModel?.saveCredentials(host, user, token) { showCredentials = false }
            },
        )
    }

    deleteTarget?.let { branch ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.fgit_delete_branch_title)) },
            text = { Text(stringResource(R.string.fgit_delete_branch_confirm, branch.shortName)) },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    viewModel?.deleteBranch(branch)
                    deleteTarget = null
                }) { Text(stringResource(R.string.fgit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.fgit_cancel)) }
            },
        )
    }

    deleteTagTarget?.let { tagName ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTagTarget = null },
            title = { Text(stringResource(R.string.fgit_delete_tag_title)) },
            text = { Text(stringResource(R.string.fgit_delete_tag_confirm, tagName)) },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    viewModel?.deleteTag(tagName)
                    deleteTagTarget = null
                }) { Text(stringResource(R.string.fgit_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { deleteTagTarget = null }) { Text(stringResource(R.string.fgit_cancel)) }
            },
        )
    }

    discardTarget?.let { change ->
        RuntimeAlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text(stringResource(R.string.fgit_discard_title)) },
            text = { Text(stringResource(R.string.fgit_discard_confirm, change.path)) },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    viewModel?.discardFile(change)
                    discardTarget = null
                }) { Text(stringResource(R.string.fgit_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { discardTarget = null }) { Text(stringResource(R.string.fgit_cancel)) }
            },
        )
    }

    pushPreview?.let { preview ->
        RuntimeAlertDialog(
            onDismissRequest = { pushPreview = null },
            title = { Text(stringResource(R.string.fgit_push_preview_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (preview.isEmpty()) {
                        Text(stringResource(R.string.fgit_push_preview_empty), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            stringResource(R.string.fgit_push_preview_count, preview.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.verticalScroll(rememberScrollState()),
                        ) {
                            preview.forEach { (hash, subject) ->
                                Text(
                                    text = "$hash  $subject",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                RuntimeTextButton(
                    onClick = {
                        pushPreview = null
                        viewModel?.push()
                    },
                    enabled = preview.isNotEmpty(),
                ) { Text(stringResource(R.string.fgit_push_confirm)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { pushPreview = null }) { Text(stringResource(R.string.fgit_cancel)) }
            },
        )
    }

    if (showCreateTag) {
        CreateTagDialog(
            onDismiss = { showCreateTag = false },
            onCreate = { name, message ->
                showCreateTag = false
                viewModel?.createTag(name, message)
            },
        )
    }
}

// ----------------------------------------------------------------------
// 仓库语义颜色辅助函数（深浅主题自适应，符合 MD3 对比度规范）
// ----------------------------------------------------------------------

@Composable
private fun gitSuccessColor(): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
}

@Composable
private fun gitInfoColor(): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDark) Color(0xFF90CAF9) else Color(0xFF1976D2)
}

@Composable
private fun gitWarningColor(): Color {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (isDark) Color(0xFFFFB74D) else Color(0xFFE65100)
}

@Composable
private fun gitAiColor(): Color = MaterialTheme.colorScheme.tertiary

// ----------------------------------------------------------------------
// 仓库概览卡片：分支 + 领先落后 + 远程地址 + 推送/拉取
// ----------------------------------------------------------------------

@Composable
private fun RepoHeaderCard(
    overview: GitOverview,
    busy: Boolean,
    operation: GitOperation?,
    progress: GitProgress?,
    onPull: () -> Unit,
    onPush: () -> Unit,
) {
    RuntimeCard(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentPadding = PaddingValues(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                RuntimeIcon(
                    name = RuntimeIconName.GitBranch,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = overview.currentBranch,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (overview.isDetached) {
                    MiniBadge(stringResource(R.string.fgit_detached), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                if (overview.aheadCount > 0) {
                    MiniBadge("↑${overview.aheadCount}", gitSuccessColor())
                }
                if (overview.behindCount > 0) {
                    MiniBadge("↓${overview.behindCount}", gitInfoColor())
                }
                if (overview.uncommittedChanges > 0 || overview.untrackedFiles > 0) {
                    MiniBadge(
                        stringResource(
                            R.string.fgit_dirty_count,
                            overview.uncommittedChanges + overview.untrackedFiles,
                        ),
                        gitWarningColor(),
                    )
                }
            }

            if (overview.remoteUrl.isNotBlank()) {
                Text(
                    text = overview.remoteUrl,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = stringResource(R.string.fgit_no_remote),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (busy && (operation == GitOperation.PUSH || operation == GitOperation.PULL)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    progress?.let { p ->
                        Text(
                            text = p.title + if (p.total > 0) "  ${p.completed}/${p.total}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (p.total > 0) {
                            RuntimeLinearProgressIndicator(
                                progress = { (p.completed.toFloat() / p.total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                            )
                        } else {
                            RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                        }
                    } ?: RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionChip(
                    icon = RuntimeIconName.Download,
                    label = stringResource(R.string.fgit_pull),
                    enabled = !busy && overview.remoteUrl.isNotBlank(),
                    tint = gitInfoColor(),
                    onClick = onPull,
                    modifier = Modifier.weight(1f),
                )
                ActionChip(
                    icon = RuntimeIconName.ArrowUp,
                    label = stringResource(R.string.fgit_push),
                    enabled = !busy && overview.remoteUrl.isNotBlank(),
                    tint = gitSuccessColor(),
                    onClick = onPush,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ActionChip(
    icon: RuntimeIconName,
    label: String,
    enabled: Boolean,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (enabled) tint.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            RuntimeIcon(icon, Modifier.size(15.dp), if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ----------------------------------------------------------------------
// 浅克隆提示卡：--depth 1 导入的仓库只有 1 个提交，补全历史后才能看到完整树
// ----------------------------------------------------------------------

@Composable
private fun ShallowRepoCard(
    busy: Boolean,
    operation: GitOperation?,
    progress: GitProgress?,
    onUnshallow: () -> Unit,
) {
    val inProgress = busy && operation == GitOperation.UNSHALLOW
    val warningColor = gitWarningColor()
    RuntimeCard(
        containerColor = warningColor.copy(alpha = 0.10f),
        borderColor = warningColor.copy(alpha = 0.4f),
        contentPadding = PaddingValues(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RuntimeIcon(RuntimeIconName.Info, Modifier.size(16.dp), warningColor)
                Text(
                    text = stringResource(R.string.fgit_shallow_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = warningColor,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onUnshallow,
                    enabled = !busy,
                    color = if (busy) MaterialTheme.colorScheme.surfaceContainerHigh else warningColor.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.minimumInteractiveComponentSize(),
                ) {
                    Text(
                        text = stringResource(
                            if (inProgress) R.string.fgit_unshallow_running else R.string.fgit_unshallow,
                        ),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else warningColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.fgit_shallow_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (inProgress) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    progress?.let { p ->
                        Text(
                            text = p.title,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = warningColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 分支页签
// ----------------------------------------------------------------------

@Composable
private fun BranchesTab(
    state: GitUiState,
    onCheckout: (GitBranchInfo) -> Unit,
    onDelete: (GitBranchInfo) -> Unit,
    onCreate: () -> Unit,
) {
    val overview = state.overview ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fgit_local_branches, overview.localBranches.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onCreate,
                    enabled = !state.busy,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(13.dp), MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.fgit_new_branch),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (overview.localBranches.isEmpty()) {
            item { EmptyHint(stringResource(R.string.fgit_no_branches)) }
        } else {
            items(overview.localBranches, key = { it.fullName }) { branch ->
                BranchRow(
                    branch = branch,
                    busy = state.busy,
                    isCheckoutLoading = state.operation == GitOperation.CHECKOUT,
                    onCheckout = { onCheckout(branch) },
                    onDelete = { onDelete(branch) },
                )
            }
        }

        if (overview.remoteBranches.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.fgit_remote_branches, overview.remoteBranches.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                )
            }
            items(overview.remoteBranches, key = { it.fullName }) { branch ->
                BranchRow(
                    branch = branch,
                    busy = state.busy,
                    isCheckoutLoading = false,
                    onCheckout = { onCheckout(branch) },
                    onDelete = null,
                )
            }
        }
    }
}

@Composable
private fun BranchRow(
    branch: GitBranchInfo,
    busy: Boolean,
    isCheckoutLoading: Boolean,
    onCheckout: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val tint = if (branch.isCurrent) {
        MaterialTheme.colorScheme.primary
    } else if (branch.isRemote) {
        gitInfoColor()
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    RuntimeCard(
        containerColor = if (branch.isCurrent) tint.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (branch.isCurrent) tint.copy(alpha = 0.55f) else Color.Transparent,
        contentPadding = PaddingValues(10.dp),
        onClick = if (!busy && !branch.isCurrent) onCheckout else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeIcon(
                if (branch.isRemote) RuntimeIconName.Globe else RuntimeIconName.GitBranch,
                Modifier.size(17.dp),
                tint,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        branch.shortName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (branch.isCurrent) MiniBadge(stringResource(R.string.fgit_current), tint)
                }
                Text(
                    text = branch.commitId.take(7),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCheckoutLoading && !branch.isCurrent) {
                RuntimeCircularProgressIndicator(Modifier.size(16.dp))
            } else if (onDelete != null && !branch.isCurrent && !busy) {
                RuntimeIconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 提交记录页签
// ----------------------------------------------------------------------

@Composable
private fun CommitsTab(
    state: GitUiState,
    onToggleDetail: (String) -> Unit,
) {
    if (state.commitsLoading && state.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            RuntimeCircularProgressIndicator(Modifier.size(24.dp))
        }
        return
    }
    if (state.commits.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyHint(stringResource(R.string.fgit_no_commits))
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 32.dp),
    ) {
        items(state.commits, key = { it.hash }) { row ->
            var expanded by rememberSaveable(row.hash) { mutableStateOf(false) }
            Column {
                // 点击行加载并展开提交详情（完整信息 + diff）
                RuntimeCard(
                    containerColor = Color.Transparent,
                    contentPadding = PaddingValues(0.dp),
                    onClick = {
                        expanded = !expanded
                        if (expanded) onToggleDetail(row.hash)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    CommitGraphRow(row)
                }
                androidx.compose.animation.AnimatedVisibility(visible = expanded) {
                    val detail = state.commitDetails[row.hash]
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 4.dp, bottom = 6.dp),
                    ) {
                        Text(
                            text = detail ?: stringResource(R.string.fgit_detail_loading),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 16.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(10.dp)
                                .horizontalScroll(rememberScrollState()),
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 标签页签
// ----------------------------------------------------------------------

@Composable
private fun TagsTab(
    state: GitUiState,
    onCreate: () -> Unit,
    onDelete: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.fgit_tags_title, state.tags.size),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = onCreate,
                    enabled = !state.busy,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Plus, Modifier.size(13.dp), MaterialTheme.colorScheme.primary)
                        Text(
                            stringResource(R.string.fgit_new_tag),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
        if (state.tags.isEmpty()) {
            item { EmptyHint(stringResource(R.string.fgit_no_tags)) }
        } else {
            items(state.tags, key = { it.name }) { tag ->
                RuntimeCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentPadding = PaddingValues(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.GitCommit, Modifier.size(17.dp), gitSuccessColor())
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                tag.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                tag.commitId.take(7) + (tag.message?.let { " · ${it.lineSequence().firstOrNull().orEmpty().take(40)}" } ?: ""),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RuntimeIconButton(onClick = { onDelete(tag.name) }, modifier = Modifier.size(30.dp)) {
                            RuntimeIcon(RuntimeIconName.Trash, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------------
// 改动页签：选文件暂存 → AI/手写 message → 提交
// ----------------------------------------------------------------------

@Composable
private fun ChangesTab(
    state: GitUiState,
    onToggleStage: (GitManager.GitFileChange) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onDiscard: (GitManager.GitFileChange) -> Unit,
    onCommit: (String) -> Unit,
    onCommitAndPush: (String) -> Unit,
    onGenerateMessage: () -> Unit,
    onMessageChange: (String) -> Unit,
) {
    // message 草稿来自 ViewModel：返回聊天页后 AI 生成的结果不丢
    val message = state.commitMessageDraft
    val stagedCount = state.changes.count { it.staged }
    val canCommit = !state.busy && stagedCount > 0 && message.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (state.changes.isEmpty()) {
                item {
                    EmptyHint(stringResource(R.string.fgit_no_changes))
                }
            } else {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            stringResource(R.string.fgit_staged_count, stagedCount, state.changes.size),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        if (stagedCount < state.changes.size) {
                            Surface(
                                onClick = onStageAll,
                                enabled = !state.busy,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.fgit_stage_all),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                        if (stagedCount > 0) {
                            Surface(
                                onClick = onUnstageAll,
                                enabled = !state.busy,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.fgit_unstage_all),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
                items(state.changes, key = { it.path }) { change ->
                    ChangeRow(
                        change = change,
                        enabled = !state.busy,
                        onToggle = { onToggleStage(change) },
                        onDiscard = { onDiscard(change) },
                    )
                }
            }
        }

        // 底部提交栏：message 输入 + AI 生成 + 提交按钮
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = message,
                        onValueChange = onMessageChange,
                        placeholder = { Text(stringResource(R.string.fgit_message_placeholder), style = MaterialTheme.typography.bodySmall) },
                        textStyle = MaterialTheme.typography.bodySmall,
                        minLines = 1,
                        maxLines = 4,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.operation == GitOperation.AI_MESSAGE) {
                        RuntimeCircularProgressIndicator(Modifier.size(22.dp))
                    } else {
                        val aiColor = gitAiColor()
                        Surface(
                            onClick = onGenerateMessage,
                            enabled = !state.busy && state.changes.isNotEmpty(),
                            color = aiColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                RuntimeIcon(RuntimeIconName.Sparkles, Modifier.size(15.dp), aiColor)
                                Text(
                                    stringResource(R.string.fgit_ai_message),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = aiColor,
                                )
                            }
                        }
                    }
                }
                val commitColor = gitSuccessColor()
                val pushColor = gitInfoColor()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { onCommit(message) },
                        enabled = canCommit,
                        shape = RoundedCornerShape(10.dp),
                        color = if (canCommit) commitColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize(),
                    ) {
                        Text(
                            text = if (state.operation == GitOperation.COMMIT) {
                                stringResource(R.string.fgit_committing)
                            } else {
                                stringResource(
                                    if (stagedCount > 0) R.string.fgit_commit_n else R.string.fgit_commit_no_staged,
                                    stagedCount,
                                )
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (canCommit) commitColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                    // 提交并推送：commit 成功后立即 push（省一次点击）
                    Surface(
                        onClick = { onCommitAndPush(message) },
                        enabled = canCommit,
                        shape = RoundedCornerShape(10.dp),
                        color = if (canCommit) pushColor.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.ArrowUp, Modifier.size(15.dp), if (canCommit) pushColor else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                stringResource(R.string.fgit_commit_push),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (canCommit) pushColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(
    change: GitManager.GitFileChange,
    enabled: Boolean,
    onToggle: () -> Unit,
    onDiscard: () -> Unit,
) {
    val (typeLabel, typeTint) = when (change.changeType) {
        GitManager.ChangeType.ADDED -> "新增" to gitSuccessColor()
        GitManager.ChangeType.MODIFIED -> "修改" to gitInfoColor()
        GitManager.ChangeType.DELETED -> "删除" to MaterialTheme.colorScheme.error
        GitManager.ChangeType.UNTRACKED -> "未跟踪" to gitWarningColor()
        GitManager.ChangeType.CONFLICT -> "冲突" to MaterialTheme.colorScheme.error
    }
    RuntimeCard(
        containerColor = if (change.staged) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceContainerLow,
        borderColor = if (change.staged) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.Transparent,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
        onClick = if (enabled) onToggle else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 勾选框：已暂存 ✓，未暂存空框
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(
                        if (change.staged) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(4.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (change.staged) {
                    RuntimeIcon(RuntimeIconName.Check, Modifier.size(12.dp), MaterialTheme.colorScheme.onPrimary)
                } else {
                    Box(
                        Modifier
                            .size(17.dp)
                            .background(Color.Transparent, RoundedCornerShape(4.dp))
                            .border(1.2.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
                    )
                }
            }
            MiniBadge(typeLabel, typeTint)
            Text(
                text = change.path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 丢弃：未跟踪=删除文件，已跟踪=还原到 HEAD（危险操作，外层有确认对话框）
            if (enabled) {
                RuntimeIconButton(onClick = onDiscard, modifier = Modifier.size(30.dp)) {
                    RuntimeIcon(RuntimeIconName.Reverse, Modifier.size(15.dp), MaterialTheme.colorScheme.error)
                }
            }
            if (change.staged) {
                Text(
                    stringResource(R.string.fgit_staged),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

// ----------------------------------------------------------------------
// 对话框
// ----------------------------------------------------------------------

@Composable
private fun CreateBranchDialog(
    currentBranch: String,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fgit_new_branch)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (currentBranch.isBlank()) {
                        stringResource(R.string.fgit_create_branch_hint)
                    } else {
                        stringResource(R.string.fgit_create_branch_from, currentBranch)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().take(64) },
                    label = { Text(stringResource(R.string.fgit_branch_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeTextButton(
                onClick = { if (name.isNotBlank()) onCreate(name) },
            ) { Text(stringResource(R.string.fgit_create)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fgit_cancel)) }
        },
    )
}

@Composable
private fun CreateTagDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, message: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fgit_new_tag)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.fgit_create_tag_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim().take(64) },
                    label = { Text(stringResource(R.string.fgit_tag_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(500) },
                    label = { Text(stringResource(R.string.fgit_tag_message)) },
                    placeholder = { Text(stringResource(R.string.fgit_tag_message_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RuntimeTextButton(onClick = { if (name.isNotBlank()) onCreate(name, message) }) {
                Text(stringResource(R.string.fgit_create))
            }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fgit_cancel)) }
        },
    )
}

@Composable
private fun CredentialsDialog(
    remoteUrl: String,
    savedHosts: List<String>,
    onDismiss: () -> Unit,
    onSave: (host: String, username: String, token: String) -> Unit,
) {
    val defaultHost = remember(remoteUrl) {
        if (remoteUrl.isBlank()) "" else GitCredentialsStore.extractHost(remoteUrl)
    }
    var host by remember(defaultHost) { mutableStateOf(defaultHost) }
    var username by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fgit_credentials_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.fgit_credentials_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (remoteUrl.isNotBlank()) {
                    Text(
                        stringResource(R.string.fgit_credentials_remote, remoteUrl),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim().take(100) },
                    label = { Text(stringResource(R.string.fgit_host)) },
                    placeholder = { Text("github.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(100) },
                    label = { Text(stringResource(R.string.fgit_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.take(500) },
                    label = { Text(stringResource(R.string.fgit_token)) },
                    placeholder = { Text(stringResource(R.string.fgit_token_placeholder)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (savedHosts.isNotEmpty()) {
                    Text(
                        stringResource(R.string.fgit_saved_hosts, savedHosts.joinToString("、")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            RuntimeTextButton(
                onClick = { if (host.isNotBlank() && username.isNotBlank() && token.isNotBlank()) onSave(host, username, token) },
            ) { Text(stringResource(R.string.fgit_save)) }
        },
        dismissButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.fgit_cancel)) }
        },
    )
}

@Composable
private fun MiniBadge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}
