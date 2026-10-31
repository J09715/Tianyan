package top.tianyan.app.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.tianyan.app.ui.components.RuntimeIllustration
import top.tianyan.app.ui.components.RuntimeIllustrationScene

@Composable
internal fun OnboardingArtwork() {
    RuntimeIllustration(RuntimeIllustrationScene.Welcome, Modifier.height(208.dp))
}
