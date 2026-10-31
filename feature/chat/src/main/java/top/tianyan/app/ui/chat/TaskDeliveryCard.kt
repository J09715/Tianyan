package top.tianyan.app.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.TaskDelivery
import top.tianyan.app.harness.FileArtifactOrigin
import top.tianyan.app.harness.task.DeliveryArtifactKind
import java.text.DateFormat
import java.util.Date
import top.tianyan.app.harness.task.DeliveryGap
import top.tianyan.app.harness.task.ObservationState
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeTextButton

/** A transcript-bound delivery record, with observed execution separated from goal verification. */
@Composable
internal fun TaskDeliveryCard(
    message: TaskDelivery,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)? = null,
) {
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    var showAll by rememberSaveable(message.id) { mutableStateOf(false) }
    val report = message.report
    RuntimeCard(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(stringResource(R.string.delivery_title), style = MaterialTheme.typography.labelMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.delivery_counts, report.observations.size, report.artifacts.size),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                RuntimeTextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(stringResource(if (expanded) R.string.delivery_collapse else R.string.delivery_expand), maxLines = 1)
                }
            }
            if (expanded) {
                Text(stringResource(R.string.delivery_unverified), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                report.gaps.filter { it != DeliveryGap.GOAL_NOT_VERIFIED }.forEach { gap ->
                    Text(stringResource(gapLabel(gap)), style = MaterialTheme.typography.bodySmall)
                }
                val observations = if (showAll) report.observations else report.observations.take(8)
                observations.forEach { observation ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(observation.toolName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(stateLabel(observation.state)), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
                if (report.observations.size > 8 && !showAll) {
                    RuntimeTextButton(onClick = { showAll = true }) { Text(stringResource(R.string.delivery_show_all), maxLines = 1) }
                }
                report.artifacts.forEach { artifact ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(kindLabel(artifact.kind)), style = MaterialTheme.typography.labelMedium)
                        artifact.origin?.let { Text(stringResource(originLabel(it)), style = MaterialTheme.typography.bodySmall) }
                        artifact.observedAt?.let { Text(stringResource(R.string.delivery_observed_at,
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))), style = MaterialTheme.typography.labelSmall) }
                        // 路径、哈希与打开/分享/另存入口都在这里：图片给缩略图，日志可回编辑器查看。
                        DeliveryArtifactRow(artifact, workspace, onOpenFile)
                    }
                }
                if (report.artifacts.isNotEmpty()) Text(stringResource(R.string.delivery_artifact_version), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun kindLabel(kind: DeliveryArtifactKind): Int = when (kind) {
    DeliveryArtifactKind.OUTPUT_LOG -> R.string.delivery_kind_log
    DeliveryArtifactKind.APK -> R.string.delivery_kind_apk
    DeliveryArtifactKind.IMAGE -> R.string.delivery_kind_image
    DeliveryArtifactKind.FILE -> R.string.delivery_kind_file
}
private fun originLabel(origin: FileArtifactOrigin): Int = when (origin) {
    FileArtifactOrigin.NEW_AFTER_COMMAND -> R.string.delivery_origin_new
    FileArtifactOrigin.CHANGED_AFTER_COMMAND -> R.string.delivery_origin_changed
    FileArtifactOrigin.UNCHANGED_AFTER_COMMAND -> R.string.delivery_origin_unchanged
    FileArtifactOrigin.OBSERVED_AFTER_COMMAND -> R.string.delivery_origin_observed
    FileArtifactOrigin.AFTER_DOWNLOAD -> R.string.delivery_origin_download
    FileArtifactOrigin.IMAGE_READ -> R.string.delivery_origin_image_read
}

private fun stateLabel(state: ObservationState): Int = when (state) {
    ObservationState.SUCCEEDED -> R.string.delivery_tool_succeeded
    ObservationState.FAILED -> R.string.delivery_tool_failed
    ObservationState.OUTCOME_UNKNOWN -> R.string.delivery_tool_unknown
    ObservationState.WAITING_APPROVAL -> R.string.delivery_tool_waiting
    ObservationState.UNSETTLED -> R.string.delivery_tool_unsettled
}
private fun gapLabel(gap: DeliveryGap): Int = when (gap) {
    DeliveryGap.GOAL_NOT_VERIFIED -> R.string.delivery_unverified
    DeliveryGap.NO_TOOL_EVIDENCE -> R.string.delivery_no_evidence
    DeliveryGap.HISTORY_INCOMPLETE -> R.string.delivery_history_incomplete
    DeliveryGap.UNKNOWN_OUTCOME -> R.string.delivery_unknown_outcome
    DeliveryGap.PENDING_RESULT -> R.string.delivery_pending_result
    DeliveryGap.FAILED_TOOL -> R.string.delivery_failed_tool
    DeliveryGap.INCOMPLETE_ARTIFACT -> R.string.delivery_incomplete_artifact
    DeliveryGap.INVALID_ARTIFACT -> R.string.delivery_invalid_artifact
}
