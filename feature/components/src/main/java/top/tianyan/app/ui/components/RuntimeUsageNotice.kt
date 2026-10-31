package top.tianyan.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.components.R

/** The same product disclosure is available in first-run setup and Settings. */
@Composable
fun RuntimeUsageNoticeContent(modifier: Modifier = Modifier) {
    val sections = listOf(
        R.string.runtime_notice_actions_title to R.string.runtime_notice_actions_body,
        R.string.runtime_notice_ai_title to R.string.runtime_notice_ai_body,
        R.string.runtime_notice_data_title to R.string.runtime_notice_data_body,
        R.string.runtime_notice_access_title to R.string.runtime_notice_access_body,
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        sections.forEachIndexed { index, (title, body) ->
            RuntimeCard(
                modifier = Modifier.fillMaxWidth(),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.extraLarge.copy(
                    topStart = if (index == 0) MaterialTheme.shapes.extraLarge.topStart else MaterialTheme.shapes.extraSmall.topStart,
                    topEnd = if (index == 0) MaterialTheme.shapes.extraLarge.topEnd else MaterialTheme.shapes.extraSmall.topEnd,
                    bottomStart = if (index == sections.lastIndex) MaterialTheme.shapes.extraLarge.bottomStart else MaterialTheme.shapes.extraSmall.bottomStart,
                    bottomEnd = if (index == sections.lastIndex) MaterialTheme.shapes.extraLarge.bottomEnd else MaterialTheme.shapes.extraSmall.bottomEnd,
                ),
            ) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(body), modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun RuntimeUsageNoticeDialog(onDismiss: () -> Unit) {
    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_notice_title)) },
        text = {
            RuntimeUsageNoticeContent(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
        },
        confirmButton = {
            RuntimeTextButton(onClick = onDismiss) { Text(stringResource(R.string.runtime_notice_close)) }
        },
    )
}
