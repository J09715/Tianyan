package top.tianyan.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconName

@Composable
internal fun OnboardingWelcomePage(modifier: Modifier = Modifier) {
    Box(modifier.verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            OnboardingArtwork()
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.onboarding_welcome), style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text(stringResource(R.string.onboarding_welcome_description), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                WelcomeFeature(RuntimeIconName.Terminal, stringResource(R.string.onboarding_feature_linux),
                    stringResource(R.string.onboarding_feature_linux_description))
                WelcomeFeature(RuntimeIconName.Sparkles, stringResource(R.string.onboarding_feature_agent),
                    stringResource(R.string.onboarding_feature_agent_description))
                WelcomeFeature(RuntimeIconName.Folder, stringResource(R.string.onboarding_feature_workspace),
                    stringResource(R.string.onboarding_feature_workspace_description))
            }
        }
    }
}

@Composable
private fun WelcomeFeature(icon: RuntimeIconName, title: String, description: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                RuntimeIcon(icon, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun OnboardingPageHeading(
    title: String,
    description: String,
    action: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            action()
        }
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
