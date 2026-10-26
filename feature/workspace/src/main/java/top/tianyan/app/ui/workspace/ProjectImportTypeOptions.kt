package top.tianyan.app.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import top.tianyan.app.feature.workspace.R
import top.tianyan.app.runtime.ProjectType
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun projectImportTypeOptions() = listOf(
    ProjectType.ANDROID to ("Android" to RuntimeIconName.Android),
    ProjectType.FLUTTER to ("Flutter" to RuntimeIconName.Flutter),
    ProjectType.REVERSE to (stringResource(R.string.workspace_template_reverse) to RuntimeIconName.Reverse),
    ProjectType.GENERAL to (stringResource(R.string.workspace_template_empty) to RuntimeIconName.Code),
    ProjectType.TIANYAN_PLUGIN to (stringResource(R.string.workspace_project_tianyan_plugin) to RuntimeIconName.Code),
)
