package top.tianyan.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.core.model.bot.BotSessionGroupRef
import top.tianyan.app.feature.chat.R
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.runtime.WorkspaceProject
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.theme.semanticColors

/**
 * Solab 风格的会话侧栏：固定搜索与底部功能入口，会话列表独立滚动。
 * - 屏幕左侧展开，含背景遮罩与进退场滑动动画。
 * - 搜索会话标题/工作区，顶部 + 创建新会话，底部展开主功能与插件。
 * - 分区自上而下为「项目」→「Bot 会话」→「最近」，统一用名称 + 数量 + 折叠箭头的分区头。
 * - 「项目」展示关联工作区工程，支持展开/折叠与单项目一键「+」新建会话。
 * - 「最近」展示未关联工作区的普通会话，按最近修改时间倒序排列。
 */
@Composable
internal fun SessionsSideDrawer(
    visible: Boolean,
    sessions: List<HarnessSessionEntity>,
    botSessions: List<HarnessSessionEntity> = emptyList(),
    botSessionGroups: Map<String, BotSessionGroupRef> = emptyMap(),
    hiddenBotSessionIds: Set<String> = emptySet(),
    onHideBotSession: (String, Boolean) -> Unit = { _, _ -> },
    currentSessionId: String,
    workspaces: List<WorkspaceProject>,
    sessionRunStates: Map<String, SessionRunState>,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
    onSwitch: (String) -> Unit,
    onNew: () -> Unit,
    onCreateInWorkspace: (WorkspaceProject) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onCollapse: (() -> Unit)? = null, navigationContent: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    var renameTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var projectsExpanded by rememberSaveable { mutableStateOf(true) }
    // Search reveals matching projects without overwriting the user's normal collapsed state.
    var searchProjectsExpanded by rememberSaveable(query.isNotBlank()) { mutableStateOf(true) }
    val (partition, botMatches) = remember(sessions, botSessions, botSessionGroups, hiddenBotSessionIds, workspaces, query) {
        val term = query.trim()
        fun HarnessSessionEntity.matches() = term.isEmpty() ||
            title.contains(term, ignoreCase = true) || workspace.contains(term, ignoreCase = true)
        val filtered = sessions.filter { it.matches() }
        val result = partitionSessions(filtered, workspaces)
        val bots = botSessions.filter { it.matches() && it.id !in hiddenBotSessionIds }
            // 私有/独立会话默认不进抽屉（Bot 详情页可直达），搜索时才作为历史入口出现。
            .filter { term.isNotEmpty() || it.id in botSessionGroups }
            .sortedByDescending { it.updatedAt }
        (if (term.isEmpty()) result else result.copy(projectGroups = result.projectGroups.filter { it.sessions.isNotEmpty() })) to bots
    }
    val hiddenBotSessions = remember(botSessions, hiddenBotSessionIds) {
        botSessions.filter { it.id in hiddenBotSessionIds }.sortedByDescending { it.updatedAt }
    }
    var expandedProjects by rememberSaveable {
        val initialExpanded = partition.projectGroups
            .filter { group -> group.sessions.any { it.id == currentSessionId } }
            .map { it.workspacePath }
            .toSet()
        mutableStateOf(initialExpanded)
    }
    val attentionSessions = remember(sessions, botSessions, sessionRunStates) {
        (sessions + botSessions)
            .filter {
                sessionRunStates[it.id] == SessionRunState.WAITING_APPROVAL ||
                    sessionRunStates[it.id] == SessionRunState.FAILED
            }
            .sortedWith(
                compareByDescending<HarnessSessionEntity> {
                    sessionRunStates[it.id] == SessionRunState.WAITING_APPROVAL
                }.thenByDescending { it.updatedAt },
            )
    }

    Surface(
        modifier = modifier.fillMaxHeight().fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            SessionDrawerSearch(query, { query = it }, onNew, onCollapse)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (query.isNotBlank() && partition.projectGroups.isEmpty() && partition.recentSessions.isEmpty() && botMatches.isEmpty()) {
                    Text(stringResource(R.string.chat_drawer_no_matches),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (query.isBlank() && attentionSessions.isNotEmpty()) {
                    SessionAttentionBanner(
                        count = attentionSessions.size,
                        firstTitle = attentionSessions.first().title,
                        onOpen = { onSwitch(attentionSessions.first().id) },
                    )
                }
                if (query.isBlank() || partition.projectGroups.isNotEmpty()) {
                    ProjectSection(
                        projectGroups = partition.projectGroups,
                        sectionExpanded = if (query.isBlank()) projectsExpanded else searchProjectsExpanded,
                        onToggleSection = {
                            if (query.isBlank()) projectsExpanded = !projectsExpanded
                            else searchProjectsExpanded = !searchProjectsExpanded
                        },
                        currentSessionId = currentSessionId,
                        sessionRunStates = sessionRunStates,
                        expandedProjects = if (query.isBlank()) expandedProjects
                            else partition.projectGroups.map { it.workspacePath }.toSet(),
                        onToggleExpand = { path ->
                            expandedProjects = if (expandedProjects.contains(path)) expandedProjects - path else expandedProjects + path
                        },
                        onSwitch = onSwitch,
                        onCreateInProject = { group ->
                            val matchedWs = workspaces.firstOrNull { it.linuxPath == group.workspacePath }
                                ?: WorkspaceProject(
                                    name = group.projectName,
                                    path = group.workspacePath,
                                    linuxPath = group.workspacePath,
                                    sizeBytes = 0L,
                                    projectType = group.projectType,
                                )
                            onCreateInWorkspace(matchedWs)
                        },
                        onRename = { id -> renameTargetId = id },
                        onDelete = { id -> deleteTargetId = id },
                    )
                }

                if (botMatches.isNotEmpty()) {
                    BotSessionsSection(
                        botSessions = botMatches,
                        groupRefs = botSessionGroups,
                        currentSessionId = currentSessionId,
                        sessionRunStates = sessionRunStates,
                        searchActive = query.isNotBlank(),
                        onSwitch = onSwitch,
                        onRename = { id -> renameTargetId = id },
                        onDelete = { id -> deleteTargetId = id },
                        onHide = onHideBotSession,
                    )
                }

                if (query.isBlank() || partition.recentSessions.isNotEmpty()) {
                    RecentSection(
                        recentSessions = partition.recentSessions,
                        currentSessionId = currentSessionId,
                        sessionRunStates = sessionRunStates,
                        onSwitch = onSwitch,
                        onRename = { id -> renameTargetId = id },
                        onDelete = { id -> deleteTargetId = id },
                    )
                }

                if (hiddenBotSessions.isNotEmpty()) HiddenSessionsEntry(hiddenBotSessions, onRestore = onHideBotSession)
            }
            SessionDrawerFooter(sessions.size, onSettings, navigationContent)
        }
    }

    // 重命名会话弹窗
    val renameTarget = renameTargetId?.let { id -> (sessions + botSessions).firstOrNull { it.id == id } }
    renameTarget?.let { target ->
        RenameSessionDialog(
            currentTitle = target.title,
            onDismiss = { renameTargetId = null },
            onRename = { newTitle ->
                onRename(target.id, newTitle)
                renameTargetId = null
            },
        )
    }

    // 删除会话二次确认弹窗
    deleteTargetId?.let { targetId ->
        RuntimeAlertDialog(
            onDismissRequest = { deleteTargetId = null },
            title = { Text(stringResource(R.string.chat_delete_session_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.chat_delete_session_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                RuntimeButton(
                    onClick = {
                        deleteTargetId = null
                        onDelete(targetId)
                    },
                ) {
                    Text(stringResource(R.string.chat_confirm_delete), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { deleteTargetId = null }) {
                    Text(stringResource(R.string.chat_cancel))
                }
            },
        )
    }
}

/** 最近分区（未关联项目的会话）。 */
@Composable
private fun RecentSection(
    recentSessions: List<HarnessSessionEntity>,
    currentSessionId: String,
    sessionRunStates: Map<String, SessionRunState>,
    onSwitch: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SessionSectionHeader(
            title = stringResource(R.string.chat_drawer_recent),
            count = recentSessions.size,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (!expanded) return@Column

        if (recentSessions.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.chat_drawer_no_recent_sessions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(10.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                recentSessions.forEach { session ->
                    SessionDrawerItem(
                        session = session,
                        isCurrent = session.id == currentSessionId,
                        runState = sessionRunStates[session.id] ?: SessionRunState.IDLE,
                        onSwitch = { onSwitch(session.id) },
                        onRename = { onRename(session.id) },
                        onDelete = { onDelete(session.id) },
                    )
                }
            }
        }
    }
}

/** 单条会话项：只保留标题一行；重命名/删除走长按，当前与执行中的行额外给出 ⋮。 */
@Composable
internal fun SessionDrawerItem(
    session: HarnessSessionEntity, isCurrent: Boolean, runState: SessionRunState,
    onSwitch: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
    onHide: (() -> Unit)? = null,
) {
    var showMenu by remember(session.id) { mutableStateOf(false) }
    val moreLabel = stringResource(R.string.chat_more_controls)
    val isBusy = runState == SessionRunState.RUNNING || runState == SessionRunState.WAITING_APPROVAL ||
        runState == SessionRunState.FAILED
    val dotColor = when (runState) {
        SessionRunState.RUNNING -> semanticColors().warning
        SessionRunState.WAITING_APPROVAL -> semanticColors().agent
        SessionRunState.FAILED -> MaterialTheme.colorScheme.error
        else -> Color.Transparent
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(onClick = onSwitch, onLongClick = { showMenu = true })
                .semantics { selected = isCurrent }
                .padding(start = 12.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 空闲时留透明占位，保证各层级标题左边界对齐。
            Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
            Text(
                text = session.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                if (isCurrent || isBusy) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { showMenu = true }
                            .semantics { contentDescription = moreLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(RuntimeIconName.More, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_rename_session)) },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Edit, Modifier.size(18.dp)) },
                        onClick = { showMenu = false; onRename() },
                    )
                    if (onHide != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_hide_session)) },
                            leadingIcon = { RuntimeIcon(RuntimeIconName.VisibilityOff, Modifier.size(18.dp)) },
                            onClick = { showMenu = false; onHide() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_delete_session), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Trash, Modifier.size(18.dp), MaterialTheme.colorScheme.error) },
                        onClick = { showMenu = false; onDelete() },
                    )
                }
            }
        }
    }
}
