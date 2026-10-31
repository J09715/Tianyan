package top.tianyan.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import top.tianyan.app.ui.components.RuntimeButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.feature.components.R as ComponentsR
import top.tianyan.app.ui.components.RuntimeCheckbox
import top.tianyan.app.ui.components.RuntimeIllustration
import top.tianyan.app.ui.components.RuntimeIllustrationScene
import top.tianyan.app.ui.components.RuntimeUsageNoticeContent
import top.tianyan.app.ui.components.runtimeContentPadding

@Composable
internal fun OnboardingDisclaimerPage(
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = runtimeContentPadding(horizontal = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeIllustration(RuntimeIllustrationScene.Notice, Modifier.height(140.dp))
                Text(stringResource(ComponentsR.string.runtime_notice_title), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onboarding_notice_intro), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { RuntimeUsageNoticeContent() }
    }
}

/** The acknowledgement and primary action stay together outside the scrollable notice. */
@Composable
internal fun OnboardingDisclaimerActions(
    acknowledged: Boolean,
    onAcknowledge: (Boolean) -> Unit,
    onContinue: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Box(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues()),
            contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().toggleable(acknowledged, role = Role.Checkbox, onValueChange = onAcknowledge)
                        .heightIn(min = 48.dp).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RuntimeCheckbox(acknowledged, onCheckedChange = null)
                    Text(stringResource(R.string.onboarding_notice_acknowledge),
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
                RuntimeButton(onClick = onContinue, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_notice_continue), modifier = Modifier.padding(4.dp))
                }
            }
        }
    }
}
