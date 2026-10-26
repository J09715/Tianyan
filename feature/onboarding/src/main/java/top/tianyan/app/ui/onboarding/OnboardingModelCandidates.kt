package top.tianyan.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import top.tianyan.app.ui.components.RuntimeFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun OnboardingModelCandidates(candidateModels: List<String>, model: String, onSelect: (String) -> Unit) {
    if (candidateModels.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        candidateModels.forEach { candidate ->
            FilterChip(
                selected = model == candidate, shape = androidx.compose.foundation.shape.CircleShape,
                onClick = { onSelect(candidate) },
                label = {
                    Text(candidate, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingIcon = if (model == candidate) {
                    { RuntimeIcon(RuntimeIconName.Check, Modifier.size(18.dp)) }
                } else null,
            )
        }
    }
}
