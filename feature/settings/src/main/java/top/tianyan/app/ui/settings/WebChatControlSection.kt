package top.tianyan.app.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import top.tianyan.app.feature.settings.R
import top.tianyan.app.runtime.webchat.WebChatServerStatus
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSwitch

/** WebChat 电脑大屏协作控制行：开关 + 运行中展示访问地址、配对码与一键复制。 */
@Composable
internal fun WebChatControlRow(
    status: WebChatServerStatus,
    onToggle: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            SettingsRow(RuntimeIconName.Globe,
                stringResource(R.string.settings_feature_webchat_title),
                stringResource(if (status.isRunning) R.string.settings_feature_webchat_running else R.string.settings_feature_webchat_idle),
                tonalIcon = true,
            ) {
                RuntimeSwitch(checked = status.isRunning, onCheckedChange = onToggle)
            }

            AnimatedVisibility(
                visible = status.isRunning,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(R.string.settings_feature_webchat_url_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = status.accessUrl,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(R.string.settings_feature_webchat_pin_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = status.pinCode,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 2.sp,
                            ),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }

                    val copiedMessage = stringResource(R.string.settings_feature_webchat_copied)
                    RuntimeButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val directUrl = "${status.accessUrl}?token=${status.pinCode}"
                            clipboard.setPrimaryClip(ClipData.newPlainText("WebChat Direct URL", directUrl))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        RuntimeIcon(RuntimeIconName.Copy, Modifier.size(20.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.settings_feature_webchat_copy))
                    }

                    Text(
                        text = stringResource(R.string.settings_feature_webchat_hint, status.activeConnections),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
