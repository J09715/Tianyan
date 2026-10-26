package top.tianyan.app.ui.bot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.model.bot.GroupChat
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeFilterChip
import top.tianyan.app.ui.components.RuntimeFloatingActionButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.RuntimeTopBar

@Composable
fun BotsScreen(
    onBack: () -> Unit,
    onOpenBot: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    viewModel: BotListViewModel = koinViewModel(),
) {
    val bots by viewModel.bots.collectAsStateWithLifecycle()
    val modelProfiles by viewModel.modelProfiles.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val memberGroups by viewModel.memberGroups.collectAsStateWithLifecycle()
    val marketItems by viewModel.marketItems.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var showCreateBot by rememberSaveable { mutableStateOf(false) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var showTeamTemplates by rememberSaveable { mutableStateOf(false) }
    var showCreateGroup by rememberSaveable { mutableStateOf(false) }
    var showManageGroups by rememberSaveable { mutableStateOf(false) }
    var selectedMemberGroupId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingDeleteGroup by remember { mutableStateOf<GroupChat?>(null) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importPackage)
    }

    LaunchedEffect(Unit) {
        viewModel.notices.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(tab) {
        if (tab == TAB_MARKET) viewModel.refreshMarket()
    }
    LaunchedEffect(memberGroups) {
        if (selectedMemberGroupId != null && memberGroups.none { it.id == selectedMemberGroupId }) {
            selectedMemberGroupId = null
        }
    }

    Scaffold(
        topBar = {
            RuntimeTopBar(
                title = stringResource(R.string.fbot_title),
                onBack = onBack,
                statusText = stringResource(R.string.fbot_subtitle),
                actions = {
                    RuntimeIconButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"),
                            )
                        },
                        contentDescription = stringResource(R.string.fbot_import),
                    ) {
                        RuntimeIcon(RuntimeIconName.FolderDownload, Modifier.size(21.dp))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (tab != TAB_MARKET) {
                RuntimeFloatingActionButton(
                    onClick = { if (tab == TAB_BOTS) showCreateBot = true else showCreateGroup = true },
                ) {
                    RuntimeIcon(RuntimeIconName.Plus, Modifier.size(22.dp))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    R.string.fbot_tab_bots to RuntimeIconName.Bot,
                    R.string.fbot_tab_groups to RuntimeIconName.Chat,
                    R.string.fbot_tab_market to RuntimeIconName.Hub,
                ).forEachIndexed { index, (label, icon) ->
                    RuntimeFilterChip(
                        selected = tab == index,
                        onClick = { tab = index },
                        label = { Text(stringResource(label)) },
                        leadingIcon = { RuntimeIcon(icon, Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (tab == TAB_GROUPS) RuntimeTextButton(onClick = { selectedTemplateId = null; showTeamTemplates = true }, enabled = !uiState.busy) {
                Text(stringResource(R.string.fbot_team_import))
            }
            when (tab) {
                TAB_BOTS -> BotList(
                    bots = bots,
                    modelProfiles = modelProfiles,
                    memberGroups = memberGroups,
                    selectedGroupId = selectedMemberGroupId,
                    onSelectGroup = { selectedMemberGroupId = it },
                    onManageGroups = { showManageGroups = true },
                    onOpenBot = onOpenBot,
                )
                TAB_GROUPS -> GroupList(
                    groups = groups,
                    adminName = { id -> bots.firstOrNull { it.id == id }?.name.orEmpty() },
                    onOpenGroup = onOpenGroup,
                    onDeleteGroup = { pendingDeleteGroup = it },
                )
                else -> MarketList(
                    items = marketItems,
                    installedNames = bots.map { it.name }.toSet(),
                    installingId = uiState.installingId,
                    onInstall = viewModel::installMarketBot,
                    onImportTeam = { selectedTemplateId = it; showTeamTemplates = true },
                )
            }
        }
    }

    if (showCreateBot) {
        CreateBotDialog(
            groups = groups,
            busy = uiState.busy,
            onDismiss = { showCreateBot = false },
            onCreate = { name, role, persona, groupId, avatarSeed ->
                viewModel.createBot(name, role, persona, groupId, avatarSeed)
                showCreateBot = false
            },
        )
    }
    if (showTeamTemplates) TeamTemplateDialog(onDismiss = { showTeamTemplates = false }, initialTemplateId = selectedTemplateId) { id, name ->
        val created = viewModel.importTeam(id, name)
        selectedMemberGroupId = created
        tab = TAB_BOTS
        showManageGroups = true
    }
    if (showCreateGroup) {
        CreateGroupDialog(
            bots = bots,
            memberGroups = memberGroups,
            busy = uiState.busy,
            onDismiss = { showCreateGroup = false },
            onCreate = { name, description, adminBotId, memberIds, teamId ->
                viewModel.createGroup(name, description, adminBotId, memberIds, teamId)
                showCreateGroup = false
            },
        )
    }
    if (showManageGroups) {
        MemberGroupsDialog(
            groups = memberGroups,
            bots = bots,
            onDismiss = { showManageGroups = false },
            onSave = viewModel::saveTeamGroup,
            loadImpact = viewModel::teamImpact, loadVersions = viewModel::teamVersions,
            onPublish = viewModel::publishTeam, onUpgrade = viewModel::upgradeTeams,
            initialGroupId = selectedMemberGroupId,
            onDelete = viewModel::deleteMemberGroup,
        )
    }
    pendingDeleteGroup?.let { group ->
        RuntimeAlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(stringResource(R.string.fbot_group_delete_title)) },
            text = { Text(stringResource(R.string.fbot_group_delete_confirm, group.name)) },
            confirmButton = {
                RuntimeButton(onClick = {
                    viewModel.deleteGroup(group)
                    pendingDeleteGroup = null
                }) { Text(stringResource(R.string.fbot_delete)) }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { pendingDeleteGroup = null }) {
                    Text(stringResource(R.string.fbot_cancel))
                }
            },
        )
    }
}

private const val TAB_BOTS = 0
private const val TAB_GROUPS = 1
private const val TAB_MARKET = 2
