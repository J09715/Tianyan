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
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.tianyan.app.feature.settings.R
import top.tianyan.app.harness.workflow.SentinelState
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeSwitch

/** 晨报哨兵控制行：开关 + 开启后展示定时与下次巡检，可改时间。 */
@Composable
internal fun SentinelControlRow(
    state: SentinelState,
    onEnable: (hour: Int, minute: Int) -> Unit,
    onDisable: () -> Unit,
) {
    var showTimeEditor by remember { mutableStateOf(false) }

    RuntimeCard(
        modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainer, contentPadding = PaddingValues(0.dp),
    ) {
        Column {
            SettingsRow(RuntimeIconName.Alarm,
                stringResource(R.string.settings_feature_sentinel_title),
                stringResource(R.string.settings_feature_sentinel_subtitle), tonalIcon = true,
            ) {
                RuntimeSwitch(checked = state.enabled,
                    onCheckedChange = { checked -> if (checked) onEnable(state.hour, state.minute) else onDisable() })
            }

            AnimatedVisibility(
                visible = state.enabled,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.size(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(
                                    R.string.settings_feature_sentinel_schedule,
                                    formatSentinelClock(state.hour, state.minute),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            state.nextRunAt?.let { next ->
                                Text(
                                    text = stringResource(
                                        R.string.settings_feature_sentinel_next_run,
                                        formatSentinelTimestamp(next),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = { showTimeEditor = true }) {
                            Text(stringResource(R.string.settings_feature_sentinel_time_edit))
                        }
                    }
                }
            }
        }
    }

    if (showTimeEditor) {
        SentinelTimeEditorDialog(
            initialHour = state.hour,
            initialMinute = state.minute,
            onConfirm = { hour, minute ->
                showTimeEditor = false
                onEnable(hour, minute)
            },
            onDismiss = { showTimeEditor = false },
        )
    }
}

/** 时/分手动输入弹窗（0-23 / 0-59 校验）。 */
@Composable
private fun SentinelTimeEditorDialog(
    initialHour: Int,
    initialMinute: Int,
    onConfirm: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var hourText by remember { mutableStateOf(initialHour.toString()) }
    var minuteText by remember { mutableStateOf(initialMinute.toString()) }
    val hour = hourText.toIntOrNull()
    val minute = minuteText.toIntOrNull()
    val valid = hour in 0..23 && minute in 0..59

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_feature_sentinel_dialog_title)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RuntimeFilledTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.settings_feature_sentinel_hour_label)) },
                        isError = hour !in 0..23,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    RuntimeFilledTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.settings_feature_sentinel_minute_label)) },
                        isError = minute !in 0..59,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(hour ?: 0, minute ?: 0) }) {
                Text(stringResource(R.string.settings_feature_sentinel_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_feature_sentinel_cancel)) }
        },
    )
}

private fun formatSentinelClock(hour: Int, minute: Int): String =
    "%02d:%02d".format(Locale.getDefault(), hour, minute)

private fun formatSentinelTimestamp(epochMillis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))
