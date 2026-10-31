package top.tianyan.app.ui.iteration
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.custom_iteration.R
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator
import top.tianyan.app.ui.components.NoticeBanner
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeFilledTextField
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.SectionHeader
@Composable
fun CustomIterationScreen(
    onBack: () -> Unit,
    onNavigateToChat: (prefillPrompt: String) -> Unit,
    viewModel: CustomIterationViewModel? = previewOrNull { koinViewModel() }
) {
    val uiState by viewModel?.uiState?.collectAsStateWithLifecycle() ?: previewStateOf(CustomIterationViewModel.UiState())
    val buildState by viewModel?.buildState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator.BuildStatus.Idle)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar(title = stringResource(R.string.iteration_title), onBack = onBack) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(innerPadding))
                .verticalScroll(rememberScrollState()).padding(bottom = innerPadding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头部 Hero 卡片
            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.iteration_hero_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.iteration_hero_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 22.sp
                    )
                }
            }

            // 特性清单
            SectionHeader(
                title = stringResource(R.string.iteration_features_title),
                subtitle = stringResource(R.string.iteration_features_subtitle),
            )

            IterationFeatureItem(
                title = stringResource(R.string.iteration_feature_workspace_title),
                description = stringResource(R.string.iteration_feature_workspace_desc),
                badgeColor = MaterialTheme.colorScheme.primary
            )

            IterationFeatureItem(
                title = stringResource(R.string.iteration_feature_skill_title),
                description = stringResource(R.string.iteration_feature_skill_desc),
                badgeColor = MaterialTheme.colorScheme.secondary
            )

            IterationFeatureItem(
                title = stringResource(R.string.iteration_feature_ci_title),
                description = stringResource(R.string.iteration_feature_ci_desc),
                badgeColor = MaterialTheme.colorScheme.tertiary
            )

            IterationFeatureItem(
                title = stringResource(R.string.iteration_feature_dual_title),
                description = stringResource(R.string.iteration_feature_dual_desc),
                badgeColor = MaterialTheme.colorScheme.primary
            )

            // 错误提示：可关闭，不再常驻；重试走下方"开启自定义迭代"按钮
            if (uiState.errorMessage != null) {
                NoticeBanner(
                    text = uiState.errorMessage ?: "",
                    isError = true,
                    onDismiss = { viewModel?.dismissError() }
                )
            }

            Spacer(Modifier.height(8.dp))

            // 启动按钮
            RuntimeButton(
                onClick = {
                    viewModel?.startCustomIteration { prompt ->
                        onNavigateToChat(prompt)
                    }
                },
                enabled = !uiState.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (uiState.isBusy) {
                    RuntimeCircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.iteration_preparing_workspace))
                } else {
                    Text(
                        text = stringResource(R.string.iteration_start),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 云端构建区块：触发 tianyandev-build.yml 并跟踪状态
            SectionHeader(
                title = stringResource(R.string.iteration_build_section_title),
                subtitle = stringResource(R.string.iteration_build_section_subtitle),
            )

            var branchText by remember { mutableStateOf("main") }

            RuntimeCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    RuntimeFilledTextField(
                        value = branchText,
                        onValueChange = { branchText = it },
                        label = { Text(stringResource(R.string.iteration_build_branch_label)) },
                        singleLine = true,
                        enabled = !isBuildPipelineActive(buildState),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = describeBuildStatus(buildState),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )

                    if (buildState is TianyanDevBuildCoordinator.BuildStatus.Failed) {
                        NoticeBanner(
                            text = (buildState as TianyanDevBuildCoordinator.BuildStatus.Failed).error,
                            isError = true,
                            onDismiss = { viewModel?.dismissBuildStatus() }
                        )
                    }

                    RuntimeButton(
                        onClick = { viewModel?.startCloudBuild(branchText) },
                        enabled = !isBuildPipelineActive(buildState),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        if (isBuildPipelineActive(buildState)) {
                            RuntimeCircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.iteration_build_in_progress))
                        } else {
                            Text(
                                text = stringResource(R.string.iteration_build_trigger),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 流水线是否处于进行中状态（禁止重复触发）。 */
private fun isBuildPipelineActive(state: TianyanDevBuildCoordinator.BuildStatus): Boolean =
    when (state) {
        is TianyanDevBuildCoordinator.BuildStatus.Dispatching,
        is TianyanDevBuildCoordinator.BuildStatus.Running,
        is TianyanDevBuildCoordinator.BuildStatus.Downloading,
        -> true
        else -> false
    }

/** 把构建状态机渲染为用户可读的单行文案。 */
@Composable
private fun describeBuildStatus(state: TianyanDevBuildCoordinator.BuildStatus): String = when (state) {
    TianyanDevBuildCoordinator.BuildStatus.Idle -> stringResource(R.string.iteration_build_idle)
    is TianyanDevBuildCoordinator.BuildStatus.Dispatching ->
        stringResource(R.string.iteration_build_dispatching, state.branch)
    is TianyanDevBuildCoordinator.BuildStatus.Running ->
        stringResource(R.string.iteration_build_running, state.runId.toString(), state.statusText)
    is TianyanDevBuildCoordinator.BuildStatus.Downloading -> stringResource(R.string.iteration_build_downloading)
    is TianyanDevBuildCoordinator.BuildStatus.Success ->
        stringResource(R.string.iteration_build_success, state.apkPath, state.sha256.take(16))
    is TianyanDevBuildCoordinator.BuildStatus.Failed ->
        stringResource(R.string.iteration_build_failed, state.error)
}

@Composable
private fun IterationFeatureItem(
    title: String,
    description: String,
    badgeColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 18.sp
            )
        }
    }
}
