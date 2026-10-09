@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package top.tianyan.app.ui.chat

import androidx.activity.compose.BackHandler
// 星轨动效依赖：HEAD 呼吸光环的无限动画（rememberInfiniteTransition + 800ms Reverse）
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
// 变更摘要比例条整条圆角裁剪用：不裁的话两端的段会露出直角
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import top.tianyan.app.feature.chat.R
// 星轨 Orbital 共享组件：与红队指挥室共用同一套轨道页签/呼吸点/等宽数字/交错入场
import top.tianyan.app.ui.components.OrbitalTabRow
import top.tianyan.app.ui.components.OrbitalTabSpec
import top.tianyan.app.ui.components.PulseDot
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.GitTokens
import top.tianyan.app.ui.components.gitCardSurface
import top.tianyan.app.ui.components.gitSubtleBorder
import top.tianyan.app.ui.components.gitSubtleText
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.staggeredEntrance
import top.tianyan.app.ui.components.tabular
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Git 页面（全屏）：RuntimeTopBar 顶栏 + 状态/分支/历史 三 Tab。
 * 状态由 [ChatViewModel.gitPanelState] 提供，绑定当前会话（不跨会话共享）。
 */
@Composable
fun GitPanel(
    state: GitPanelState,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onFileDiff: (String) -> Unit = {},
    onClearDiff: () -> Unit = {},
    onStage: (String) -> Unit = {},
    onUnstage: (String) -> Unit = {},
    onStageAll: () -> Unit = {},
    onUnstageAll: () -> Unit = {},
    onCommit: (String) -> Unit = {},
    onPull: () -> Unit = {},
    onPush: () -> Unit = {},
    onCheckout: (String) -> Unit = {},
    onCreateBranch: (String) -> Unit = {},
    onDeleteBranch: (String) -> Unit = {},
    onRenameBranch: (String, String) -> Unit = { _, _ -> },
    onDeleteRemoteBranch: (String) -> Unit = {},
    onInitRepo: () -> Unit = {},
    onClone: (String) -> Unit = {},
    onConfigIdentity: (String, String) -> Unit = { _, _ -> },
    onRevert: (String) -> Unit = {},
    onRevertAll: () -> Unit = {},
    onDeleteUntracked: (String) -> Unit = {},
    onCreateTag: (String) -> Unit = {},
    onDeleteTag: (String) -> Unit = {},
    onCommitDetail: (String) -> Unit = {},
    onClearCommitDetail: () -> Unit = {},
    credentials: List<top.tianyan.app.core.datastore.GitCredential> = emptyList(),
    matchedCredentialId: String? = null,
    onAddCredential: (name: String, host: String, username: String, token: String) -> Unit = { _, _, _, _ -> },
    onDeleteCredential: (String) -> Unit = {},
    onProbeCredential: (String) -> Unit = {},
    onPullNow: () -> Unit = {},
    onDismissPullDirty: () -> Unit = {},
    onConfirmCheckoutDirty: (String) -> Unit = {},
    onDismissCheckoutConfirm: () -> Unit = {},
    onStash: () -> Unit = {},
    onStashPop: () -> Unit = {},
    aiCommit: top.tianyan.app.ui.chat.GitAiCommitState = top.tianyan.app.ui.chat.GitAiCommitState.Idle,
    onAiGenerate: () -> Unit = {},
    credentialHealth: Map<String, top.tianyan.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerifyCredential: (String) -> Unit = {},
    onVerifyAllCredentials: () -> Unit = {},
    repoListState: top.tianyan.app.ui.chat.GitRepoListState = top.tianyan.app.ui.chat.GitRepoListState.Idle,
    onFetchRepos: (String) -> Unit = {},
    onClearRepoList: () -> Unit = {},
    progress: top.tianyan.app.ui.chat.GitProgress? = null,
    onCancelProgress: () -> Unit = {},
    recentCloneUrls: List<String> = emptyList(),
    onLoadMoreCommits: () -> Unit = {},
    onUnshallow: () -> Unit = {},
    onCommitFileDiff: (String, String) -> Unit = { _, _ -> },
    gitOp: GitOpMessage = GitOpMessage.Idle,
    onSwitchWorkspace: (String) -> Unit = {},
    onRetryCloneClean: (String, String) -> Unit = { _, _ -> },
    onUndoRename: (String, String) -> Unit = { _, _ -> },
    onConsumeGitOp: () -> Unit = {},
) {
    if (state.diffPath != null) {
        GitDiffView(state, onBack = onClearDiff)
        return
    }
    BackHandler(onBack = onDismiss)
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    /** AiCode 布局：凭据/署名从第 4 个 tab 改为顶栏 🔑 图标进入的子页面。 */
    var showCredentialsPage by rememberSaveable { mutableStateOf(false) }
    var showCredentialDialog by rememberSaveable { mutableStateOf(false) }
    var credentialName by rememberSaveable { mutableStateOf("") }
    var credentialEmail by rememberSaveable { mutableStateOf("") }
    var showCloneDialog by rememberSaveable { mutableStateOf(false) }
    var cloneUrl by rememberSaveable { mutableStateOf("") }
    var showAddPatDialog by rememberSaveable { mutableStateOf(false) }
    var newPatName by rememberSaveable { mutableStateOf("") }
    var newPatHost by rememberSaveable { mutableStateOf("github.com") }
    var newPatUser by rememberSaveable { mutableStateOf("") }
    var newPatToken by rememberSaveable { mutableStateOf("") }

    // ===== Git 反馈 Snackbar 内嵌面板：用户在哪操作就在哪提示，不打扰背后的聊天页 =====
    val gitSnackHost = remember { androidx.compose.material3.SnackbarHostState() }
    val gitSnackContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(gitOp) {
        val snapshot = gitOp
        val isError: Boolean
        val message: String
        val action: GitOpAction?
        when (snapshot) {
            is GitOpMessage.Ok -> { isError = false; message = snapshot.message; action = snapshot.action }
            is GitOpMessage.Error -> { isError = true; message = snapshot.message; action = snapshot.action }
            else -> return@LaunchedEffect
        }
        run {
            val actionLabel = when (action) {
                is GitOpAction.SwitchWorkspaceTo -> "切过去"
                is GitOpAction.RetryWithClean -> "清空再试"
                is GitOpAction.UndoRename -> "撤销"
                is GitOpAction.StashPop -> "还原 stash"
                is GitOpAction.CopyError -> "复制"
                is GitOpAction.RetrySame -> if (isError) null else "重试"
                null -> if (isError) "复制错误" else null
            }
            val result = if (actionLabel != null) {
                gitSnackHost.showSnackbar(message, actionLabel = actionLabel, duration = androidx.compose.material3.SnackbarDuration.Long)
            } else {
                gitSnackHost.showSnackbar(message, duration = androidx.compose.material3.SnackbarDuration.Long)
            }
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                when (action) {
                    is GitOpAction.SwitchWorkspaceTo -> onSwitchWorkspace(action.path)
                    is GitOpAction.RetryWithClean -> onRetryCloneClean(action.url, action.targetDir)
                    is GitOpAction.UndoRename -> onUndoRename(action.newName, action.oldName)
                    is GitOpAction.StashPop -> onStashPop()
                    else -> {}
                }
                // 错误（含无 action 兜底）→ 复制原文到剪贴板，方便粘给助手
                if (isError && (action == null || action is GitOpAction.CopyError)) {
                    val cm = gitSnackContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("git-error", message))
                }
            }
            onConsumeGitOp()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(
            title = if (showCredentialsPage) "凭据与署名" else stringResource(R.string.chat_git_panel_title),
            onBack = { if (showCredentialsPage) showCredentialsPage = false else onDismiss() },
            statusText = null,
            actions = {
                if (!showCredentialsPage) {
                    RuntimeIconButton(onClick = { showCredentialsPage = true }) {
                        RuntimeIcon(RuntimeIconName.Key, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RuntimeIconButton(onClick = onRefresh, enabled = !state.loading) {
                        RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    }
                }
            },
        )

        if (showCredentialsPage) {
            // 凭据子页：署名配置卡 + PAT 列表（AiCode「凭据与署名」页等价物）
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                IdentityCard(
                    hasIdentity = state.hasIdentity,
                    onEdit = { showCredentialDialog = true },
                )
                CredentialsTab(
                    credentials = credentials,
                    onAdd = { showAddPatDialog = true },
                    onDelete = onDeleteCredential,
                    health = credentialHealth,
                    onVerify = onVerifyCredential,
                    onVerifyAll = onVerifyAllCredentials,
                )
            }
        } else {
            // Git 流式进度内嵌在面板顶部（用户在 panel 里点 clone/pull/push，不用回 chat 主页面看）
            progress?.let { p ->
                GitProgressBanner(progress = p, onCancel = onCancelProgress)
            }

            if (!state.loading && !state.notARepo && state.branch != null && state.error == null) {
                GitWorkspaceHeader(state = state)
            }

            // 状态 / 分支 / 提交 三页：星轨轨道页签条（OrbitalTabRow）放在内容上方。
            // 为什么弃用 SecondaryTabRow：它的下划线指示器切页时直接瞬移，三个
            // 「图标+文字」页签在窄屏上还会互相挤压；OrbitalTabRow 的胶囊滑块沿
            // 等宽轨道 spring 滑动（有过冲、有运动过程），且与红队指挥室共用同一套
            // 星轨母题 —— 动效规格改一处全 App 生效。依旧不用底部悬浮 FloatingTabBar：
            // 那会与 App 自己的底部中枢导航叠在一起，两层导航叠影还点不准。
            val panelScope = rememberCoroutineScope()
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(initialPage = selectedTab.coerceAtMost(2)) { 3 }
            LaunchedEffect(pagerState.currentPage) { selectedTab = pagerState.currentPage }
            LaunchedEffect(selectedTab) {
                if (pagerState.currentPage != selectedTab) pagerState.animateScrollToPage(selectedTab)
            }
            OrbitalTabRow(
                specs = listOf(OrbitalTabSpec("状态"), OrbitalTabSpec("分支"), OrbitalTabSpec("提交")),
                selectedIndex = pagerState.currentPage,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                onSelect = { panelScope.launch { pagerState.animateScrollToPage(it) } },
            )
            Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        }
                        state.notARepo || state.branch == null -> NotARepoView(
                            onInit = onInitRepo,
                            onOpenClone = { showCloneDialog = true },
                        )
                        state.error != null -> CenterHint(state.error, isError = true)
                        page == 0 -> StatusTab(state, onFileDiff, onStage, onUnstage, onStageAll, onUnstageAll, onCommit, onPull, onPush, onRevert, onRevertAll, onDeleteUntracked, aiCommit, onAiGenerate, onStash, onStashPop)
                        page == 1 -> BranchesTab(state, onCheckout, onCreateBranch, onDeleteBranch, onRenameBranch, onDeleteRemoteBranch, onCreateTag, onDeleteTag)
                        page == 2 -> LogTab(state, onCommitDetail = onCommitDetail, onCloseCommit = onClearCommitDetail, onCommitFileDiff = onCommitFileDiff, onLoadMore = onLoadMoreCommits, onUnshallow = onUnshallow)
                    }
                }
                // Git 操作反馈 Snackbar：常驻面板底部（notARepo 时 clone 失败也要看得见）。
                // 页签条已经移到顶部，底部不再有悬浮栏，所以不需要再留 84dp 的避让空隙。
                androidx.compose.material3.SnackbarHost(
                    hostState = gitSnackHost,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                )
            }
        }
    }

    if (showCredentialDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCredentialDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCredentialDialog = false
                    if (credentialName.isNotBlank()) onConfigIdentity(credentialName.trim(), credentialEmail.trim())
                }) { Text("保存") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCredentialDialog = false }) { Text("取消") } },
            title = { Text("Git 署名配置") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = credentialName, onValueChange = { credentialName = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("用户名（user.name）") }, singleLine = true)
                    OutlinedTextField(value = credentialEmail, onValueChange = { credentialEmail = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("邮箱（user.email）") }, singleLine = true)
                }
            },
        )
    }

    if (showCloneDialog) {
        var cloneBlankError by remember { mutableStateOf(false) }
        val doClone = {
            val u = cloneUrl.trim()
            if (u.isBlank()) {
                cloneBlankError = true
            } else {
                cloneBlankError = false
                showCloneDialog = false
                onClone(u)
            }
        }
        val detectedHost = remember(cloneUrl, credentials) { GitAuth.hostOf(cloneUrl) ?: credentials.firstOrNull()?.host }
        RuntimeAlertDialog(
            onDismissRequest = { showCloneDialog = false; onClearRepoList() },
            confirmButton = { RuntimeButton(onClick = doClone) { Text("克隆") } },
            dismissButton = { RuntimeTextButton(onClick = { showCloneDialog = false }) { Text("取消") } },
            title = { Text("克隆远程仓库") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "私有仓库会自动使用「凭证」标签页里匹配的 HTTPS PAT。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cloneUrl,
                        onValueChange = {
                            cloneUrl = it
                            onProbeCredential(it.trim())
                            cloneBlankError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://github.com/owner/repo.git") },
                        singleLine = true,
                        isError = cloneBlankError,
                        supportingText = if (cloneBlankError) {
                            { Text("请先输入仓库 URL", color = MaterialTheme.colorScheme.error) }
                        } else null,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { doClone() }),
                    )
                    if (matchedCredentialId != null) {
                        val matched = credentials.firstOrNull { it.id == matchedCredentialId }
                        if (matched != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "✓ 将自动使用凭证：${matched.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                                RuntimeTextButton(onClick = { onFetchRepos(detectedHost ?: matched.host) }) {
                                    Text("📋 浏览我的仓库", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    } else if (cloneUrl.isNotBlank() && GitAuth.hostOf(cloneUrl) != null) {
                        Text(
                            "此主机没有保存的凭证，公有仓库可直接克隆；私有仓库请先到「凭证」标签页添加。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (credentials.isNotEmpty()) {
                        // URL 未填或不含可识别主机：提供浏览快捷入口
                        RuntimeTextButton(onClick = { onFetchRepos(credentials.first().host) }) {
                            Text("📋 从「${credentials.first().name}」拉仓库列表", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    if (recentCloneUrls.isNotEmpty()) {
                        Text(
                            "最近克隆过：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        recentCloneUrls.take(3).forEach { u ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    cloneUrl = u
                                    onProbeCredential(u)
                                }.padding(vertical = 4.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("🕘", style = MaterialTheme.typography.labelSmall)
                                Text(
                                    u,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    when (val st = repoListState) {
                        is top.tianyan.app.ui.chat.GitRepoListState.Loading ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                Text("正在拉取仓库列表…", style = MaterialTheme.typography.labelSmall)
                            }
                        is top.tianyan.app.ui.chat.GitRepoListState.Error ->
                            Text(st.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        is top.tianyan.app.ui.chat.GitRepoListState.Ready -> {
                            Text("点仓库自动填 URL（共 ${st.repos.size} 个）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            st.repos.take(30).forEach { repo ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable { cloneUrl = repo.cloneUrl; onProbeCredential(repo.cloneUrl) }.padding(vertical = 4.dp, horizontal = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (repo.private) "🔒" else "🌐",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                    Text(repo.fullName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        top.tianyan.app.ui.chat.GitRepoListState.Idle -> {}
                    }
                }
            },
        )
    }

    if (showAddPatDialog) {
        val savePat = {
            if (newPatName.isNotBlank() && newPatHost.isNotBlank() && newPatUser.isNotBlank() && newPatToken.isNotBlank()) {
                onAddCredential(newPatName.trim(), newPatHost.trim(), newPatUser.trim(), newPatToken.trim())
                newPatName = ""; newPatHost = "github.com"; newPatUser = ""; newPatToken = ""
                showAddPatDialog = false
            }
        }
        RuntimeAlertDialog(
            onDismissRequest = { showAddPatDialog = false },
            confirmButton = { RuntimeButton(onClick = savePat) { Text("保存") } },
            dismissButton = { RuntimeTextButton(onClick = { showAddPatDialog = false }) { Text("取消") } },
            title = { Text("新增 HTTPS 凭证") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newPatName, onValueChange = { newPatName = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("名称（如 GitHub 我的账号）") }, singleLine = true)
                    OutlinedTextField(value = newPatHost, onValueChange = { newPatHost = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("主机（github.com / gitee.com）") }, singleLine = true)
                    OutlinedTextField(value = newPatUser, onValueChange = { newPatUser = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("用户名（GitLab 可填 oauth2）") }, singleLine = true)
                    OutlinedTextField(
                        value = newPatToken,
                        onValueChange = { newPatToken = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("PAT / 密码 — 填完按键盘上的「完成」即保存") },
                        singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { savePat() }),
                    )
                }
            },
        )
    }

    if (state.pullDirtyConfirm) {
        RuntimeAlertDialog(
            onDismissRequest = onDismissPullDirty,
            title = { Text("确认拉取") },
            text = {
                Text(
                    "本地有未提交改动（已暂存 ${state.staged.size}、未暂存 ${state.unstaged.size}），" +
                        "拉取可能覆盖工作区或产生冲突。建议先提交或暂存后再拉。要继续吗？",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                RuntimeButton(onClick = { onPullNow() }) { Text("仍然拉取") }
            },
            dismissButton = {
                RuntimeTextButton(onClick = onDismissPullDirty) { Text("取消") }
            },
        )
    }

    state.pendingCheckout?.let { target ->
        RuntimeAlertDialog(
            onDismissRequest = onDismissCheckoutConfirm,
            title = { Text("确认切换分支") },
            text = {
                Text(
                    "本地有未提交改动（已暂存 ${state.staged.size}、未暂存 ${state.unstaged.size}）。" +
                        "切到 `$target` 可能覆盖工作区或产生冲突。建议先 stash 或提交。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { RuntimeButton(onClick = { onConfirmCheckoutDirty(target) }) { Text("仍然切换") } },
            dismissButton = { RuntimeTextButton(onClick = onDismissCheckoutConfirm) { Text("取消") } },
        )
    }
}

@Composable
private fun CredentialsTab(
    credentials: List<top.tianyan.app.core.datastore.GitCredential>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    health: Map<String, top.tianyan.app.ui.chat.GitCredHealth> = emptyMap(),
    onVerify: (String) -> Unit = {},
    onVerifyAll: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 不自带 verticalScroll：本页只作为「凭据与署名」子页 outer Column（已有滚动）的
            // 内容块。曾嵌套两层 verticalScroll → 内层收到无限 maxHeight → 点 Key 图标必崩
            // （IllegalStateException: Vertically scrollable ... infinity，真机复现 2 次）。
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已保存的 HTTPS 凭证（加密存储，运行时一次性使用不落盘）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (credentials.isNotEmpty()) {
                RuntimeTextButton(onClick = onVerifyAll) { Text("全部重验", style = MaterialTheme.typography.labelSmall) }
            }
            RuntimeButton(onClick = onAdd) { Text("新增") }
        }
        if (credentials.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RuntimeIcon(
                        RuntimeIconName.Key,
                        Modifier.size(48.dp),
                        MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "还没有 HTTPS 凭证",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "添加一个 GitHub / Gitee / GitLab 的 Personal Access Token，就能克隆或推送私有仓库",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    RuntimeButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) {
                        Text("添加第一条凭证", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            credentials.forEach { cred ->
                RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(cred.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${cred.username}@${cred.host}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("token: ${cred.token.take(4)}${"\u2022".repeat(8)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            when (val h = health[cred.id]) {
                                is top.tianyan.app.ui.chat.GitCredHealth.Ok ->
                                    Text("✓ Token 有效${h.checkedAtMillis.relativeAgo()}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                is top.tianyan.app.ui.chat.GitCredHealth.Invalid ->
                                    Text("✗ Token 无效 (HTTP ${h.code})${h.checkedAtMillis.relativeAgo()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                is top.tianyan.app.ui.chat.GitCredHealth.Unknown ->
                                    Text("⚠ ${h.reason}", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelSmall)
                                is top.tianyan.app.ui.chat.GitCredHealth.Checking ->
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        androidx.compose.foundation.layout.Box(Modifier.size(10.dp)) {
                                            CircularProgressIndicator(strokeWidth = 1.dp, modifier = Modifier.size(10.dp))
                                        }
                                        Text("验证中…", style = MaterialTheme.typography.labelSmall)
                                    }
                                null -> {}
                            }
                        }
                        RuntimeIconButton(onClick = { onVerify(cred.id) }) {
                            RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RuntimeIconButton(onClick = { onDelete(cred.id) }) {
                            RuntimeIcon(RuntimeIconName.Close, Modifier.size(16.dp), MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, isError: Boolean = false, icon: RuntimeIconName? = null) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                RuntimeIcon(
                    icon,
                    Modifier.size(48.dp),
                    if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NotARepoView(onInit: () -> Unit, onOpenClone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(40.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "当前工作区不是 Git 仓库",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuntimeButton(
            onClick = onOpenClone,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("克隆远程仓库", fontWeight = FontWeight.SemiBold) }
        RuntimeTextButton(
            onClick = onInit,
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("初始化空仓库") }
    }
}

// ======================= Diff 视图 =======================

@Composable
private fun GitDiffView(state: GitPanelState, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    ) {
        RuntimeTopBar(title = state.diffPath ?: "", onBack = onBack)
        // weight(1f)：同 LogTab——Column 未加权子项可能拿到无限 maxHeight，
        // 内层 LazyColumn(DiffText) 会崩
        if (state.diffLoading) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                RuntimeCircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                DiffText(state.diffText ?: "")
            }
        }
    }
}

@Composable
private fun DiffText(text: String) {
    val lines = text.lines()
    LazyColumn(Modifier.fillMaxSize()) {
        items(lines.size) { i ->
            val line = lines[i]
            val color = when {
                line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF2E7D32)
                line.startsWith("-") && !line.startsWith("---") -> Color(0xFFC62828)
                line.startsWith("@@") -> Color(0xFF1565C0)
                line.startsWith("diff ") || line.startsWith("index ") || line.startsWith("---") || line.startsWith("+++") || line.startsWith("commit ") || line.startsWith("Author:") || line.startsWith("Date:") -> Color(0xFF00838F)
                else -> MaterialTheme.colorScheme.onSurface
            }
            Text(
                line.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = color,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun GitWorkspaceHeader(state: GitPanelState) {
    val untrackedCount = state.untrackedCount.coerceAtLeast(state.untracked.size)
    val totalChanges = state.staged.size + state.unstaged.size + untrackedCount
    val syncText = state.aheadBehind?.let { (ahead, behind) -> "↑$ahead  ↓$behind" } ?: "已同步"
    RuntimeCard(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            // 驾驶舱首屏第 0 位交错入场：头卡最先浮现，状态页各区块（1/2/3 位）
            // 随后跟上，打开面板时视线顺着动画从「仓库概况」自然落到「文件清单」。
            .staggeredEntrance(0),
        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // 用「仓库」而不是「Git 工作区」：本 App 里「工作区」特指工坊那个
                    // 项目目录（WorkspaceDestination），而这里说的是当前会话目录里的
                    // Git 仓库。两个词混用会让人以为这个面板管的是工坊工作区。
                    Text(
                        "当前会话 · Git 仓库",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.74f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // 分支名配 GitBranch 小图标：驾驶舱语义下「当前在哪个分支」是
                        // 第一等公民，图标 + titleMedium 让它比副标签醒目、又比原来的
                        // titleLarge 省一行高度，右侧才放得下状态组。
                        RuntimeIcon(
                            RuntimeIconName.GitBranch,
                            Modifier.size(14.dp),
                            MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            state.branch ?: "未命名分支",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // 右侧状态组：呼吸点（工作区干净/脏）+ 同步胶囊（ahead/behind）。
                // 原来的纯文本「干净 / N 项改动」要逐字读才知道状态；呼吸点是余光
                // 可辨的信号，改动计数移到状态页的比例条上，这里只留「要不要行动」。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PulseDot(
                        // 干净 = 绿点持续呼吸（一切正常，仓库是「活」的）；
                        // 有改动 = 琥珀点静止高亮（需要注意，但不制造紧迫感）。
                        color = if (totalChanges == 0) Color(0xFF2E7D32) else Color(0xFFF59E0B),
                        pulsing = totalChanges == 0,
                    )
                    val aheadBehind = state.aheadBehind
                    if (aheadBehind != null && (aheadBehind.first > 0 || aheadBehind.second > 0)) {
                        // 有未推/未拉提交：primary 底 + onPrimary 字的反色胶囊，在
                        // primaryContainer 卡面上对比度最强 —— 这是需要用户行动的信号。
                        // 数字用 tnum 等宽：↑↓ 数字跳动时胶囊宽度不抖。
                        Text(
                            "↑${aheadBehind.first} ↓${aheadBehind.second}",
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                            maxLines = 1,
                        )
                    } else {
                        // 与远端一致（或离线时 ahead/behind 未知）：低饱和的「已同步」即可
                        Text(
                            "已同步",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GitHeaderMetric("暂存", state.staged.size.toString(), Modifier.weight(1f))
                GitHeaderMetric("修改", state.unstaged.size.toString(), Modifier.weight(1f))
                GitHeaderMetric("未跟踪", if (state.untrackedOverflow) "99+" else untrackedCount.toString(), Modifier.weight(1f))
                GitHeaderMetric("同步", syncText, Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun GitHeaderMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // value 加 tnum 等宽数字：四个指标横排、刷新时数字跳动，比例数字的「1」比
        // 「8」窄，宽度抖动会带着整列标签一起晃；等宽后数字变化只换字形不换宽度。
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold).tabular(), color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f), maxLines = 1)
    }
}

// ======================= 状态 Tab =======================

@Composable
private fun StatusTab(
    state: GitPanelState,
    onFileDiff: (String) -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onCommit: (String) -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onRevert: (String) -> Unit,
    onRevertAll: () -> Unit = {},
    onDeleteUntracked: (String) -> Unit,
    aiCommit: top.tianyan.app.ui.chat.GitAiCommitState = top.tianyan.app.ui.chat.GitAiCommitState.Idle,
    onAiGenerate: () -> Unit = {},
    onStash: () -> Unit = {},
    onStashPop: () -> Unit = {},
) {
    val staged = state.staged
    val unstaged = state.unstaged
    val untracked = state.untracked
    val untrackedCount = state.untrackedCount.coerceAtLeast(untracked.size)
    // 比例条与「共 N 项改动」文案共用的总口径：与 GitWorkspaceHeader 的
    // totalChanges 完全同一算法，两处数字必须一致，否则用户会怀疑口径不同。
    val totalChanges = staged.size + unstaged.size + untrackedCount
    val clean = staged.isEmpty() && unstaged.isEmpty() && untrackedCount == 0

    var showCommitDialog by rememberSaveable { mutableStateOf(false) }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    // AI 完成后把生成的消息回填到 commitMessage
    LaunchedEffect(aiCommit) {
        when (aiCommit) {
            is top.tianyan.app.ui.chat.GitAiCommitState.Done -> commitMessage = aiCommit.message
            else -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 这里原本还有一张「工作区概览」卡，但面板顶部的 GitWorkspaceHeader 已经
        // 展示了同样的四个数字（暂存/修改/未跟踪/同步），两处并排显示同一份数据，
        // 用户会以为其中一个是别的口径。摘要只保留顶部那张。
        //
        // 变更摘要比例条：三段色带按文件数占比分宽，先于数字给出「改动堆在哪一层」
        // 的直觉 —— primary=已暂存、琥珀=未暂存、蓝灰=未跟踪，与下方 FileRow 徽章
        // 同一套状态语义色，扫一眼色带比例就知道该先「提交」还是先「暂存」。
        // clean 时不渲染：空条没有任何信息量，只会占一行位置。
        if (!clean) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // 总数用 tnum：刷新后数字变化不带动文案宽度抖动
                    Text(
                        "共 ${if (state.untrackedOverflow) "99+" else totalChanges} 项改动",
                        style = MaterialTheme.typography.labelSmall.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "暂存区 ${staged.size} / 修改 ${unstaged.size} / 未跟踪 ${if (state.untrackedOverflow) "99+" else untrackedCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 比例条本体：6dp 高、整条圆角裁剪（clip 在外层，段与段无缝拼接）；
                // 每段 weight = 段文件数 / max(1,总数)，0 项的段直接不渲染 ——
                // 0 宽段既无信息又会把圆角边缘挤变形。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                ) {
                    if (staged.isNotEmpty()) {
                        Box(
                            Modifier
                                .weight(staged.size.toFloat() / totalChanges.coerceAtLeast(1))
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    if (unstaged.isNotEmpty()) {
                        Box(
                            Modifier
                                .weight(unstaged.size.toFloat() / totalChanges.coerceAtLeast(1))
                                .fillMaxHeight()
                                .background(Color(0xFFF59E0B)),
                        )
                    }
                    if (untrackedCount > 0) {
                        Box(
                            Modifier
                                .weight(untrackedCount.toFloat() / totalChanges.coerceAtLeast(1))
                                .fillMaxHeight()
                                .background(Color(0xFF90A4AE)),
                        )
                    }
                }
            }
        }
        //
        // 主操作：提交（有已暂存改动且已配置署名才可用）
        RuntimeButton(
            onClick = { showCommitDialog = true },
            enabled = staged.isNotEmpty() && state.hasIdentity,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) { Text("提交改动", fontWeight = FontWeight.SemiBold, maxLines = 1) }
        if (!state.hasIdentity) {
            Text(
                "请先在凭据中配置署名（用户名称与邮箱）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 次级操作：暂存全部 / 拉取 / 推送
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = onStageAll,
                enabled = !clean,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("暂存全部", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onPull,
                enabled = state.hasRemote,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("拉取", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onPush,
                enabled = state.hasRemote,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("推送", maxLines = 1) }
        }

        // 第三排：stash（工作区脏时可存；有 stash 时可恢复）
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(
                onClick = onStash,
                enabled = !clean,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text("存起改动", maxLines = 1) }
            RuntimeOutlinedButton(
                onClick = onStashPop,
                enabled = state.stashCount > 0,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) { Text(if (state.stashCount > 0) "恢复 ${state.stashCount} 号" else "无 stash", maxLines = 1) }
        }

        if (clean) {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.chat_git_no_changes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // 三个文件区块交错入场（驾驶舱头卡是第 0 位，这里是 1/2/3 位）：整段
            // （SectionHeader + 文件卡）作为一单位浮现，视线自然从暂存→修改→未跟踪
            // 往下扫。staggeredEntrance 内部用 graphicsLayer 只动绘制层，布局一次
            // 成型，滚动中的列表不会跳高度。
            if (staged.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(1),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader("已暂存 (${staged.size})", actionLabel = "全部取消暂存", onAction = onUnstageAll)
                    RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                        Column {
                            staged.forEachIndexed { i, f ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                FileRow(f, action = "取消暂存", onAction = { onUnstage(f.path) }, onClick = { onFileDiff(f.path) })
                            }
                        }
                    }
                }
            }
            if (unstaged.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader("已修改 (${unstaged.size})", actionLabel = if (unstaged.isNotEmpty()) "全部回退" else null, onAction = onRevertAll)
                    RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                        Column {
                            unstaged.forEachIndexed { i, f ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                FileRow(f, action = "暂存", onAction = { onStage(f.path) }, onClick = { onFileDiff(f.path) }, secondaryAction = "回退", onSecondaryAction = { onRevert(f.path) })
                            }
                        }
                    }
                }
            }
            if (untracked.isNotEmpty()) {
                Column(
                    modifier = Modifier.staggeredEntrance(3),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionHeader("未跟踪 (${if (state.untrackedOverflow) "99+" else untrackedCount})")
                    RuntimeCard(contentPadding = PaddingValues(0.dp)) {
                        Column {
                            untracked.forEachIndexed { i, path ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                FileRow(GitFileChange('?', path), action = "添加", onAction = { onStage(path) }, onClick = { onFileDiff(path) }, secondaryAction = "删除", onSecondaryAction = { onDeleteUntracked(path) })
                            }
                        }
                        if (state.untrackedOverflow) {
                            Text(
                                "仅显示 ${untracked.size} 个入口，共 $untrackedCount 项；大目录已聚合，避免打开 Git 时卡顿。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCommitDialog) {
        val aiLoading = aiCommit is top.tianyan.app.ui.chat.GitAiCommitState.Loading
        RuntimeAlertDialog(
            onDismissRequest = { showCommitDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCommitDialog = false
                    if (commitMessage.isNotBlank()) onCommit(commitMessage.trim())
                }) { Text("提交") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCommitDialog = false }) { Text("取消") } },
            title = { Text("提交改动") },
            text = {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        RuntimeButton(
                            onClick = onAiGenerate,
                            enabled = !aiLoading && staged.isNotEmpty(),
                        ) { Text(if (aiLoading) "✨ 生成中..." else "✨ AI 生成", style = MaterialTheme.typography.labelMedium) }
                        if (staged.isEmpty()) Text("需先暂存改动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (aiCommit is top.tianyan.app.ui.chat.GitAiCommitState.Error) {
                        Text(aiCommit.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("提交信息（可让 AI 生成后手动微调）") },
                    )
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            RuntimeTextButton(onClick = onAction) { Text(actionLabel, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun FileRow(
    change: GitFileChange,
    action: String,
    onAction: () -> Unit,
    onClick: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    val copyText = top.tianyan.app.ui.components.rememberTextCopier()
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = {
                copyText(change.path)
                android.widget.Toast.makeText(context, "已复制：${change.path}", android.widget.Toast.LENGTH_SHORT).show()
            })
            .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 22dp 固定边长彩色底徽章：原来 Surface 随字母宽度收缩，M/A/D 各行左侧
        // 参差不齐；固定尺寸后纵向扫一眼就是一条直线，色彩语义也更醒目。
        StatusLetterBadge(change.status)
        Text(
            change.path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        RuntimeTextButton(onClick = onAction) { Text(action, style = MaterialTheme.typography.labelSmall) }
        if (secondaryAction != null && onSecondaryAction != null) {
            RuntimeTextButton(onClick = onSecondaryAction) { Text(secondaryAction, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/**
 * 状态字母徽章：22dp 边长、6dp 圆角的彩色底方块 + 彩色字母。
 *
 * 为什么固定 22dp：字母只有一位，尺寸随内容收缩会让 M/A/D 各行左侧参差，
 * 固定边长 + 居中后纵向扫一眼就是一条直线；22dp 恰好容纳 labelSmall 不显挤。
 * 底色统一 10% 透明度（0x1A 前缀字面量，与既有 Color(0xFF...) 风格一致）：
 * 低饱和底 + 高饱和字在浅色列表上可读，且与驾驶舱比例条、呼吸点共用同一套
 * 状态语义色 —— 用户在状态页顶部看到什么颜色，滚到文件行就是什么颜色。
 * 字母用 tnum 等宽 + Bold：单字母本身不受比例字宽影响，但保持与计数数字同
 * 一渲染口径，避免徽章内基线随字体特性微调而跳动。
 */
@Composable
private fun StatusLetterBadge(status: Char) {
    val (bg, fg) = when (status) {
        'M' -> Color(0x1AF59E0B) to Color(0xFFF59E0B)  // 修改：琥珀（与比例条未暂存段同色）
        'A' -> Color(0x1A2E7D32) to Color(0xFF2E7D32)  // 新增：绿（与干净呼吸点同色）
        'D' -> MaterialTheme.colorScheme.error.copy(alpha = 0.1f) to MaterialTheme.colorScheme.error  // 删除：主题红
        '?' -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) to MaterialTheme.colorScheme.primary  // 未跟踪：主题色
        'R' -> Color(0x1A7C4DFF) to Color(0xFF7C4DFF)  // 重命名：紫
        // 其余状态码（C/U/T…）沿用旧 statusColor 兜底，避免新枚举漏配色
        else -> statusColor(status).let { it.copy(alpha = 0.1f) to it }
    }
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(bg, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            status.toString(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold).tabular(),
            color = fg,
            maxLines = 1,
        )
    }
}

// ======================= 分支 Tab =======================

@Composable
private fun BranchesTab(
    state: GitPanelState,
    onCheckout: (String) -> Unit,
    onCreateBranch: (String) -> Unit,
    onDeleteBranch: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDeleteRemoteBranch: (String) -> Unit,
    onCreateTag: (String) -> Unit,
    onDeleteTag: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var newBranchName by rememberSaveable { mutableStateOf("") }
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRename by rememberSaveable { mutableStateOf<String?>(null) }
    var renameInput by rememberSaveable { mutableStateOf("") }
    var pendingDeleteRemote by rememberSaveable { mutableStateOf<String?>(null) }
    var showCreateTag by rememberSaveable { mutableStateOf(false) }
    var newTagName by rememberSaveable { mutableStateOf("") }
    var pendingDeleteTag by rememberSaveable { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuntimeOutlinedButton(onClick = { showCreateDialog = true }, modifier = Modifier.weight(1f)) { Text("新建分支", maxLines = 1) }
            RuntimeOutlinedButton(onClick = { showCreateTag = true }, modifier = Modifier.weight(1f)) { Text("新建标签", maxLines = 1) }
        }

        if (state.localBranches.isEmpty() && state.remoteBranches.isEmpty() && state.tags.isEmpty()) {
            CenterHint("还没有分支。克隆或初始化仓库后这里会显示本地/远程分支", icon = RuntimeIconName.GitBranch)
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                if (state.localBranches.isNotEmpty()) {
                    item { SectionHeader("本地分支") }
                    items(state.localBranches, key = { it }) { branch ->
                        val isCurrent = branch == state.branch
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !isCurrent) { onCheckout(branch) }.padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (isCurrent) RuntimeIcon(RuntimeIconName.Check, Modifier.size(15.dp), MaterialTheme.colorScheme.primary)
                            else RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                branch,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!isCurrent) RuntimeTextButton(onClick = { pendingDelete = branch }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                            RuntimeTextButton(onClick = { pendingRename = branch; renameInput = branch }) { Text("重命名", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
                if (state.remoteBranches.isNotEmpty()) {
                    item { SectionHeader("远程分支") }
                    items(state.remoteBranches, key = { it }) { branch ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.GitBranch, Modifier.size(15.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(branch, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RuntimeTextButton(onClick = { pendingDeleteRemote = branch }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
                if (state.tags.isNotEmpty()) {
                    item { SectionHeader("标签") }
                    items(state.tags, key = { it }) { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RuntimeIcon(RuntimeIconName.Hub, Modifier.size(15.dp), MaterialTheme.colorScheme.tertiary)
                            Text(tag, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            RuntimeTextButton(onClick = { pendingDeleteTag = tag }) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateDialog = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCreateDialog = false
                    if (newBranchName.isNotBlank()) onCreateBranch(newBranchName.trim())
                }) { Text("创建") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCreateDialog = false }) { Text("取消") } },
            title = { Text("新建分支") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("分支名称") },
                        singleLine = true,
                    )
                }
            },
        )
    }

    pendingDelete?.let { branch ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDelete = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDelete = null
                    onDeleteBranch(branch)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDelete = null }) { Text("取消") } },
            title = { Text("删除分支") },
            text = { Text("确定删除分支 $branch 吗？") },
        )
    }

    if (showCreateTag) {
        RuntimeAlertDialog(
            onDismissRequest = { showCreateTag = false },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    showCreateTag = false
                    if (newTagName.isNotBlank()) onCreateTag(newTagName.trim())
                }) { Text("创建") }
            },
            dismissButton = { RuntimeTextButton(onClick = { showCreateTag = false }) { Text("取消") } },
            title = { Text("新建标签") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("标签名称") },
                        singleLine = true,
                    )
                }
            },
        )
    }

    pendingDeleteTag?.let { tag ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteTag = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDeleteTag = null
                    onDeleteTag(tag)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDeleteTag = null }) { Text("取消") } },
            title = { Text("删除标签") },
            text = { Text("确定删除标签 $tag 吗？") },
        )
    }

    pendingRename?.let { oldName ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingRename = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    val n = renameInput.trim()
                    pendingRename = null
                    if (n.isNotBlank() && n != oldName) onRename(oldName, n)
                }) { Text("重命名") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingRename = null }) { Text("取消") } },
            title = { Text("重命名分支") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原分支：$oldName", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("新的分支名") }, singleLine = true)
                }
            },
        )
    }

    pendingDeleteRemote?.let { remote ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteRemote = null },
            confirmButton = {
                RuntimeTextButton(onClick = {
                    pendingDeleteRemote = null
                    // remote 形如 "origin/main"，git push origin --delete 需要去前缀
                    val branch = remote.substringAfter("/", remote)
                    onDeleteRemoteBranch(branch)
                }) { Text("删除") }
            },
            dismissButton = { RuntimeTextButton(onClick = { pendingDeleteRemote = null }) { Text("取消") } },
            title = { Text("删除远程分支") },
            text = { Text("将从远端删除 $remote，不可撤销。确定？") },
        )
    }
}

// ======================= 提交 Tab（AiCode 拓扑图版） =======================

@Composable
private fun LogTab(
    state: GitPanelState,
    onCommitDetail: (String) -> Unit,
    onCloseCommit: () -> Unit,
    onCommitFileDiff: (String, String) -> Unit,
    onLoadMore: () -> Unit,
    onUnshallow: () -> Unit,
) {
    val graph = state.graph
    val commits = graph.commits
    if (commits.isEmpty()) {
        CenterHint("还没有提交历史", icon = RuntimeIconName.GitCommit)
        return
    }
    val laneColors = remember(graph.maxLane) {
        (0..graph.maxLane).map { GitTokens.laneColors[it % GitTokens.laneColors.size] }
    }
    val edgesByCommit = remember(graph) { groupEdgesByCommit(graph) }
    // 线性历史收紧泳道宽度；有分叉每泳道 16dp（AiCode 同参数）。
    val laneWidth = if (graph.maxLane == 0) 10.dp else 16.dp
    val canvasWidth = laneWidth * (graph.maxLane + 1) + 8.dp
    val rowHeight = 72.dp
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // 预拉取：滑到剩 20 项时后台取下一页（AiCode 同阈值）。
    val shouldLoadMore by remember {
        androidx.compose.runtime.derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 0 && lastVisible >= totalItems - 20
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && graph.hasMore && !state.graphLoadingMore) onLoadMore()
    }
    val cardColor = gitCardSurface()
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            "提交记录 (${commits.size})",
            style = MaterialTheme.typography.labelLarge,
            color = gitSubtleText(),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier
                // weight 而非 fillMaxSize：Column 对未加权子项可能给出无限高度约束，
                // LazyColumn 收到 Infinity maxHeight 直接抛 IllegalStateException（88 提交
                // 大列表实测崩溃）。weight(1f) 保证拿到"剩余有界空间"，从根上消灭这类崩溃。
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 70.dp),
        ) {
            itemsIndexed(commits, key = { _, c -> c.hash }) { index, c ->
                val shape = when {
                    index == 0 && commits.size == 1 && !graph.hasMore -> RoundedCornerShape(14.dp)
                    index == 0 -> RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                    index == commits.lastIndex && !graph.hasMore -> RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
                    else -> androidx.compose.ui.graphics.RectangleShape
                }
                Surface(
                    color = cardColor,
                    shape = shape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    GraphCommitRow(
                        commit = c,
                        lane = graph.lanes[c.hash] ?: 0,
                        edges = edgesByCommit[index].orEmpty(),
                        activeTopLanes = graph.activeTopLanes[c.hash].orEmpty(),
                        activeBottomLanes = graph.activeBottomLanes[c.hash].orEmpty(),
                        laneColors = laneColors,
                        canvasWidth = canvasWidth,
                        laneWidth = laneWidth,
                        rowHeight = rowHeight,
                        refs = graph.refs[c.hash].orEmpty(),
                        isTopTerminal = index == 0,
                        onOpen = { onCommitDetail(c.hash) },
                    )
                }
            }
            item(key = "footer") {
                Surface(
                    color = cardColor,
                    shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (graph.hasMore) {
                            if (state.graphLoadingMore) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Text("上拉加载更早提交", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else if (state.isShallow) {
                            // 浅克隆仓库（clone --depth 1）历史被截断：给出反浅克隆入口
                            TextButton(onClick = onUnshallow) {
                                Text("加载完整历史（取消浅克隆）", style = MaterialTheme.typography.labelMedium)
                            }
                        } else {
                            Text("没有更多了", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    // 提交详情弹层（不打断列表布局，泳道保持完整）
    state.commitDetailHash?.let { hash ->
        val commit = graph.commits.find { it.hash == hash }
        if (commit != null) {
            CommitDetailSheet(
                commit = commit,
                files = state.commitFiles[hash],
                loading = state.loadingCommit == hash && state.commitFiles[hash] == null,
                onDismiss = onCloseCommit,
                onFileDiff = { path -> onCommitFileDiff(commit.hash, path) },
            )
        }
    }
}

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun CommitDetailSheet(
    commit: top.tianyan.app.ui.chat.git.GraphCommit,
    files: List<GitFileChange>?,
    loading: Boolean,
    onDismiss: () -> Unit,
    onFileDiff: (String) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(commit.message, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (commit.body.isNotBlank()) {
                Text(commit.body.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${commit.author} · ${commit.date}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    commit.hash,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val copyHash = top.tianyan.app.ui.components.rememberTextCopier()
                RuntimeTextButton(onClick = { copyHash(commit.shortHash) }) {
                    Text("复制短哈希", style = MaterialTheme.typography.labelMedium)
                }
                RuntimeTextButton(onClick = { copyHash(commit.hash) }) {
                    Text("复制完整哈希", style = MaterialTheme.typography.labelMedium)
                }
            }
            if (commit.isMerge) {
                Text("合并提交", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(4.dp))
            when {
                loading -> Text("正在加载改动文件…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                files == null -> Unit
                files.isEmpty() -> Text("该提交无文件改动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("改动 ${files.size} 个文件", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    files.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onFileDiff(file.path) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            StatusChip(file.status)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    file.path.substringAfterLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                val dir = file.path.substringBeforeLast('/', "")
                                if (dir.isNotEmpty()) {
                                    Text(
                                        dir,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = gitSubtleBorder(), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun GraphCommitRow(
    commit: top.tianyan.app.ui.chat.git.GraphCommit,
    lane: Int,
    edges: List<top.tianyan.app.ui.chat.git.GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    laneColors: List<Color>,
    canvasWidth: androidx.compose.ui.unit.Dp,
    laneWidth: androidx.compose.ui.unit.Dp,
    rowHeight: androidx.compose.ui.unit.Dp,
    refs: List<top.tianyan.app.ui.chat.git.GitGraphRef>,
    isTopTerminal: Boolean,
    onOpen: () -> Unit,
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    Surface(color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .heightIn(min = rowHeight)
                .clickable(onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GraphCanvas(
                edges = edges,
                activeTopLanes = activeTopLanes,
                activeBottomLanes = activeBottomLanes,
                lane = lane,
                isMerge = commit.isMerge,
                laneColors = laneColors,
                canvasWidth = canvasWidth,
                laneWidth = laneWidth,
                suppressTopLane = isTopTerminal,
                // HEAD 行（index==0，最新提交）单独标记：suppressTopLane 管的是
                // 「上半段泳道线画不画」，光环语义是「这是不是最新提交」，分开传。
                isHead = isTopTerminal,
                modifier = Modifier.width(canvasWidth).fillMaxHeight(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp)
                    .padding(end = 16.dp),
            ) {
                if (refs.isNotEmpty()) {
                    RefPills(refs = refs)
                    Spacer(Modifier.height(4.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    RuntimeIcon(
                        RuntimeIconName.ChevronRight,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            commit.message,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = nodeColor.copy(alpha = 0.15f), shape = RoundedCornerShape(999.dp)) {
                                Text(
                                    commit.shortHash,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = nodeColor,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                commit.author,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                commit.date,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        HorizontalDivider(
            color = gitSubtleBorder(),
            thickness = 0.5.dp,
            modifier = Modifier.padding(start = canvasWidth + 12.dp),
        )
    }
}

/** 拓扑图 Canvas：分段竖线 + 跨列贝塞尔 + 节点（合并双圈）。AiCode 原版移植。 */
@Composable
private fun GraphCanvas(
    edges: List<top.tianyan.app.ui.chat.git.GraphEdge>,
    activeTopLanes: List<Int>,
    activeBottomLanes: List<Int>,
    lane: Int,
    isMerge: Boolean,
    laneColors: List<Color>,
    canvasWidth: androidx.compose.ui.unit.Dp,
    laneWidth: androidx.compose.ui.unit.Dp,
    suppressTopLane: Boolean = false,
    // HEAD（最新提交）行标记：与 suppressTopLane 语义不同 —— 那个控制上半段
    // 泳道线要不要画，这个只决定节点要不要画呼吸光环，分开传避免含义纠缠。
    isHead: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val nodeColor = laneColors.getOrElse(lane) { Color.Gray }
    // HEAD 呼吸光环 alpha：只有 HEAD 行才创建 InfiniteTransition —— 提交列表一屏
    // 十几行、长仓库滚动累计上百行，每行都挂一个无限动画纯属耗电；光环语义只属于
    // 最新提交。非 HEAD 行给恒定 1f 的静态 State 占位（isHead=false 时根本不画光环）。
    // 注意 alpha 必须在 draw lambda 里读 State（by 委托每次访问都读 .value），
    // Canvas 的绘制块会观察快照读取并逐帧失效重绘，动画才转得起来。
    val headHaloAlpha by if (isHead) {
        rememberInfiniteTransition(label = "gitHeadHalo").animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(800, easing = EaseOutCubic),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "gitHeadHaloAlpha",
        )
    } else {
        remember { mutableStateOf(1f) }
    }
    // 主题色必须在组合期读取：DrawScope 里访问不到 MaterialTheme
    val headHaloColor = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val lanePx = laneWidth.toPx()
        val padPx = 4.dp.toPx()
        val centerX = lane * lanePx + lanePx / 2f + padPx
        val centerY = size.height / 2f
        val stroke = 2.5.dp.toPx()

        val allLanes = (activeTopLanes + activeBottomLanes + lane).toSet()
        val crossEdgeToLanes = edges.filter { it.fromLane != it.toLane }.map { it.toLane }.toSet()
        val curveBotLanes = crossEdgeToLanes.filterNot { it in activeTopLanes }.toSet()

        for (l in allLanes) {
            val inTop = l in activeTopLanes
            val inBot = l in activeBottomLanes && l !in curveBotLanes
            val color = laneColors.getOrElse(l) { Color.Gray }
            val x = l * lanePx + lanePx / 2f + padPx
            if (inTop && inBot) {
                if (suppressTopLane) {
                    drawLine(color, Offset(x, centerY), Offset(x, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
                } else {
                    drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            } else if (inTop) {
                if (!suppressTopLane) {
                    drawLine(color, Offset(x, 0f), Offset(x, centerY), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            } else if (inBot) {
                drawLine(color, Offset(x, centerY), Offset(x, size.height), strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }

        for (edge in edges) {
            if (edge.fromLane == edge.toLane) continue
            val color = laneColors.getOrElse(edge.lane) { Color.Gray }
            val fromX = edge.fromLane * lanePx + lanePx / 2f + padPx
            val toX = edge.toLane * lanePx + lanePx / 2f + padPx
            val midY = centerY + (size.height - centerY) * 0.5f
            val path = androidx.compose.ui.graphics.Path().apply {
                if (edge.isMergeIn) {
                    moveTo(toX, size.height)
                    cubicTo(toX, midY, fromX, midY, fromX, centerY)
                } else {
                    moveTo(fromX, centerY)
                    cubicTo(fromX, midY, toX, midY, toX, size.height)
                }
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        val nodeRadius = if (isMerge) 7.dp.toPx() else 5.dp.toPx()
        // HEAD 节点呼吸光环：画在节点圆点之下、半径大 4dp 的 primary 晕圈，base
        // alpha 0.35 随 800ms Reverse 呼吸（与 PulseDot 同规格）。为什么用呼吸而不是
        // 静态高亮：静态高亮会和「选中态」混淆，呼吸是「这是活着的 HEAD」的信号，
        // 上百行历史里一眼锁定最新提交。headHaloAlpha 在本 lambda 内读取（快照
        // 观察 → 逐帧重绘），若在外面先取值成 Float，动画就死了。
        if (isHead) {
            drawCircle(
                color = headHaloColor.copy(alpha = 0.35f * headHaloAlpha),
                radius = nodeRadius + 4.dp.toPx(),
                center = Offset(centerX, centerY),
            )
        }
        if (isMerge) {
            drawCircle(nodeColor, radius = nodeRadius, center = Offset(centerX, centerY), style = Stroke(width = 2.5.dp.toPx()))
            drawCircle(nodeColor, radius = nodeRadius / 2f, center = Offset(centerX, centerY))
        } else {
            drawCircle(nodeColor, radius = nodeRadius, center = Offset(centerX, centerY))
        }
    }
}

/** 引用 pill 行：当前分支 primary、分支 secondaryContainer、标签 tertiaryContainer。 */
@Composable
private fun RefPills(refs: List<top.tianyan.app.ui.chat.git.GitGraphRef>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        refs.forEach { ref ->
            val bg = if (ref.isCurrent) MaterialTheme.colorScheme.primary
                else if (ref.isBranch) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.tertiaryContainer
            val fg = if (ref.isCurrent) MaterialTheme.colorScheme.onPrimary
                else if (ref.isBranch) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onTertiaryContainer
            Surface(color = bg, shape = RoundedCornerShape(4.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    RuntimeIcon(
                        when {
                            ref.isRemote -> RuntimeIconName.Cloud
                            ref.isBranch -> RuntimeIconName.GitBranch
                            else -> RuntimeIconName.Tag
                        },
                        Modifier.size(11.dp),
                        tint = fg,
                    )
                    Text(
                        ref.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 状态码彩色小药丸（AiCode StatusChip 等价：32x20 pill）。 */
@Composable
private fun StatusChip(status: Char) {
    Surface(
        color = statusColor(status),
        shape = RoundedCornerShape(999.dp),
        modifier = Modifier.size(width = 32.dp, height = 20.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = status.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}

/** 把 graph.edges 按来源提交索引分组（每提交 parents.size 条边，扁平有序）。AiCode 原版。 */
private fun groupEdgesByCommit(graph: top.tianyan.app.ui.chat.git.GitGraph): Map<Int, List<top.tianyan.app.ui.chat.git.GraphEdge>> {
    val result = mutableMapOf<Int, List<top.tianyan.app.ui.chat.git.GraphEdge>>()
    var edgeIdx = 0
    graph.commits.forEachIndexed { commitIdx, commit ->
        val n = if (commit.parents.isEmpty()) 0 else commit.parents.size
        val list = mutableListOf<top.tianyan.app.ui.chat.git.GraphEdge>()
        repeat(n) {
            if (edgeIdx < graph.edges.size) list.add(graph.edges[edgeIdx++])
        }
        result[commitIdx] = list
    }
    return result
}

/** 签名卡（凭据与署名子页顶部）：显示当前署名状态，点击编辑。 */
@Composable
private fun IdentityCard(hasIdentity: Boolean, onEdit: () -> Unit) {
    RuntimeCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RuntimeIcon(RuntimeIconName.Edit, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("Git 署名（user.name / user.email）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                Text(
                    if (hasIdentity) "已配置，可正常提交" else "未配置，提交前需填写",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (hasIdentity) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
            RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
// ======================= 状态配色 =======================

/** 时间戳 → "（2 分钟前）"；0 或近未来返回空串。 */
private fun Long.relativeAgo(): String {
    if (this <= 0L) return ""
    val diff = System.currentTimeMillis() - this
    if (diff < 0) return ""
    return when {
        diff < 60_000 -> "（刚刚）"
        diff < 60 * 60_000 -> "（${diff / 60_000} 分钟前）"
        diff < 24 * 60 * 60_000 -> "（${diff / (60 * 60_000)} 小时前）"
        else -> "（${diff / (24 * 60 * 60_000)} 天前）"
    }
}

private fun statusColor(status: Char): Color = when (status) {
    'A' -> Color(0xFF2E7D32)  // 新增 绿
    'M' -> Color(0xFFB45309)  // 修改 琥珀
    'D' -> Color(0xFFC62828)  // 删除 红
    'R', 'C' -> Color(0xFF1565C0)  // 重命名/复制 蓝
    '?' -> Color(0xFF757575)  // 未跟踪 灰
    'U' -> Color(0xFF7B1FA2)  // 冲突 紫红
    'T' -> Color(0xFF00838F)  // 类型变更 青
    else -> Color(0xFF757575)
}
