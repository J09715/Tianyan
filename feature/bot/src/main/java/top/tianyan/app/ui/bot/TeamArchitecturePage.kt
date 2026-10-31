package top.tianyan.app.ui.bot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.bot.*
import top.tianyan.app.feature.bot.R
import top.tianyan.app.ui.components.RuntimeFullscreenPage

@Composable
internal fun TeamArchitecturePage(team: GroupTeam, governance: GroupGovernance, members: List<Bot>,
    editable: Boolean = false, onDismiss: () -> Unit, runtime: TeamGraphRuntime? = null, onSelectMember: (String) -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    RuntimeFullscreenPage(title = stringResource(R.string.fbot_graph_title), onBack = onDismiss,
        statusText = runtime?.let { "${it.group.name} · 团队 v${it.group.teamVersion ?: 0}" } ?: stringResource(if (editable) R.string.fbot_graph_editable else R.string.fbot_graph_readonly)) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).padding(16.dp)) {
            TeamArchitectureGraph(team, governance, members, editable = editable, fullScreen = true,
                runtime = runtime, onSelectMember = { if (runtime != null) selected = it else onSelectMember(it) })
        }
    }
    if (runtime != null) selected?.let { id ->
        TeamRuntimeDetails(runtime, members, id.takeUnless { it.startsWith("proposal:") },
            id.takeIf { it.startsWith("proposal:") }?.removePrefix("proposal:")?.split(","), { selected = null })
    }
}
