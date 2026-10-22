package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeEmptyState
import top.tianyan.app.ui.components.StateTone
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeLoadingState
import top.tianyan.app.ui.components.RuntimeSwitch
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.theme.Spacing

/**
 * 运行引擎设置页：PRoot（默认，全兼容）与 UML（无仿真，快）二选一切换。
 *
 * 全部控件走 feature/components 的 Runtime* 包装，不自写 Material 组件。
 * 探测未通过（本机跑不了 UML，如无可写 tmpfs / 16KB 页）时给空态 + 回退 PRoot 的出路，
 * 不让用户点一个永远起不来的开关。
 */
@Composable
fun UmlEngineSettingsScreen(
    onBack: () -> Unit,
    viewModel: UmlEngineSettingsViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            RuntimeTopBar(
                title = "运行引擎",
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            when (ui.phase) {
                UmlEngineSettingsViewModel.Phase.PROBING -> {
                    RuntimeLoadingState(
                        message = "正在探测本设备能否跑 UML…",
                        modifier = Modifier.padding(Spacing.xl),
                    )
                }

                UmlEngineSettingsViewModel.Phase.READY -> {
                    // 引擎开关：主控件
                    RuntimeCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Cpu,
                                modifier = Modifier.size(Spacing.xl),
                            )
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text("UML 引擎", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "无 CPU 仿真，guest 代码在本机内核上原生执行，流畅度远高于 PRoot/QEMU。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RuntimeSwitch(
                                checked = ui.umlEnabled,
                                onCheckedChange = { viewModel.onToggleUml(it) },
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))
                        if (ui.probeResult?.ruledOut() == true) {
                            Text(
                                text = ui.probeResult!!.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    // 引擎产物检查
                    RuntimeCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    ) {
                        Text("引擎产物", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Spacing.sm))
                        if (ui.missingBinaries.isEmpty()) {
                            Text(
                                "内核 / stub / 网络 / passt 四件套均已就位。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            Text(
                                "缺少：${ui.missingBinaries.joinToString("、")}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(Spacing.md))
                        if (ui.missingBinaries.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RuntimeIconButton(onClick = { viewModel.refreshProbe() }) {
                                    RuntimeIcon(name = RuntimeIconName.Refresh)
                                }
                                Text("重检", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                UmlEngineSettingsViewModel.Phase.NO_ARTIFACTS -> {
                    RuntimeEmptyState(
                        icon = RuntimeIconName.Package,
                        title = "UML 引擎未安装",
                        description = "内核等四件套缺失，先运行 tools/prepare-uml-runtime.sh 预取再开此开关。",
                        tone = StateTone.Warning,
                        fillHeight = false,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }

                UmlEngineSettingsViewModel.Phase.BLOCKED -> {
                    RuntimeEmptyState(
                        icon = RuntimeIconName.Alert,
                        title = "本设备无法运行 UML",
                        description = ui.blockReason ?: "缺少可写 tmpfs 或内核页大小不为 4KB，建议保持 PRoot。",
                        tone = StateTone.Danger,
                        fillHeight = false,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                }
            }

            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
