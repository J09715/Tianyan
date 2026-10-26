package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.ui.components.RuntimeAlertDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import top.tianyan.app.ui.components.SpotlightGuideOverlay
import top.tianyan.app.ui.components.rememberSpotlightAnchor
import top.tianyan.app.ui.components.spotlightAnchor
import top.tianyan.app.ui.components.RuntimeButton as Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import top.tianyan.app.ui.components.RuntimeFilledTonalButton as FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import top.tianyan.app.ui.components.RuntimeIconButton as IconButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator as LinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeOutlinedButton as OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import top.tianyan.app.ui.settings.LocalizedText as Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.model.ToolState
import top.tianyan.app.core.tools.ToolInstallProgress
import top.tianyan.app.core.tools.ToolVerification
import top.tianyan.app.ui.components.NoticeBanner
import top.tianyan.app.ui.components.InfoRow
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.components.StatusBadge
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import top.tianyan.app.core.tools.LocalPluginImportState
import top.tianyan.app.feature.components.R as ComponentsR
import top.tianyan.app.feature.settings.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolCard(
    tool: ToolEntity,
    source: String,
    progress: ToolInstallProgress?,
    verification: ToolVerification?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    onLaunch: () -> Unit,
    onViewLogs: () -> Unit,
    onOpenDetail: () -> Unit,
    onStartAiHealing: () -> Unit = {},
) {
    val isInstalled = tool.state == ToolState.INSTALLED.name || tool.state == ToolState.UPDATE_AVAILABLE.name
    val isInstalling = tool.state == ToolState.INSTALLING.name
    val isUpdateAvailable = tool.state == ToolState.UPDATE_AVAILABLE.name
    val isFailed = tool.state == ToolState.FAILED.name

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onOpenDetail,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(SettingsDensity.cardPadding),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header: Avatar + Title/Publisher + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    ToolBrandAvatar(
                        toolId = tool.id,
                        category = tool.category,
                        size = 46.dp,
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val displayVersion = tool.installedVersion ?: tool.manifestVersion
                            if (displayVersion.isNotBlank()) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    shape = RoundedCornerShape(50),
                                ) {
                                    Text(
                                        text = "v$displayVersion",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                        maxLines = 1,
                                        softWrap = false, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Surface(
                                color = if (source == "LOCAL") {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                                shape = RoundedCornerShape(50),
                            ) {
                                Text(
                                    text = if (source == "LOCAL") "本地插件" else "在线插件",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                    color = if (source == "LOCAL") {
                                        MaterialTheme.colorScheme.onTertiaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }

                        Text(
                            text = "${tool.publisher} · ${when (tool.category) {
                                "CODING_AGENT" -> "编程智能体"
                                "AI_AGENT" -> "AI 智能体"
                                "DEVELOPER_TOOL" -> "开发加速包"
                                else -> tool.category
                            }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Status Badge
                StatusBadge(
                    text = when {
                        isInstalling -> "安装中"
                        isUpdateAvailable -> "可更新"
                        isInstalled -> "已就绪"
                        isFailed -> "安装失败"
                        else -> "未安装"
                    },
                    color = when {
                        isUpdateAvailable -> MaterialTheme.colorScheme.primary
                        isInstalled -> successStatusColor()
                        isInstalling -> MaterialTheme.colorScheme.tertiary
                        isFailed -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    },
                )
            }

            // Description with comfortable reading line height
            Text(
                text = tool.description,
                style = settingsSupportingStyle(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // Installing Progress Bar
            if (isInstalling && progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = progress.message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        progress.progress?.let { p ->
                            Text(
                                text = "${(p * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { progress.progress ?: 0.5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // Verification info (if any)
            if (verification != null && !verification.healthy) {
                NoticeBanner(text = "自检异常: ${verification.detail}", isError = true)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f))

            // Actions row: Spacious, distinct secondary buttons on left, prominent action on right
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Secondary actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(
                        onClick = onOpenDetail,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("详情配置", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }

                    TextButton(
                        onClick = onViewLogs,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        Text("日志", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                    }

                    if (isInstalled) {
                        TextButton(
                            onClick = onUninstall,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text("卸载", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                        }
                    }
                }

                // Primary actions (Install / Retry / Update)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isUpdateAvailable) {
                        Button(
                            onClick = onUpdate,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text("更新", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    } else if (isFailed) {
                        Button(
                            onClick = onStartAiHealing,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Brain,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("AI 自愈", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                        FilledTonalButton(
                            onClick = onInstall,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text("重试", fontWeight = FontWeight.Medium, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    } else if (!isInstalled && !isInstalling) {
                        Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Download,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("安装", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}
