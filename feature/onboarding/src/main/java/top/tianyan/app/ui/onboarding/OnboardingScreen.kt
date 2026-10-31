package top.tianyan.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.components.RuntimeIconButton
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf

/** Welcome is local UI state; installation and completion use the existing workflow. */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel? = previewOrNull { koinViewModel() },
    previewPage: Int = -1,
    onOpenAutostartSettings: (() -> Boolean)? = null,
    onNotificationGranted: () -> Unit = {},
) {
    val page by viewModel?.page?.collectAsStateWithLifecycle() ?: previewStateOf(previewPage)
    val runtime by viewModel?.runtimeState?.collectAsStateWithLifecycle() ?: previewStateOf(RuntimeState.Ready)
    val importing by viewModel?.isImporting?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    // Entry UI stays local. Existing installation, permission, and model page indices stay intact.
    var entryStep by rememberSaveable {
        mutableStateOf(when {
            previewPage == -2 -> OnboardingIntroStep.DISCLAIMER
            previewPage >= 0 -> OnboardingIntroStep.SETUP
            else -> OnboardingIntroStep.WELCOME
        })
    }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    val stage = entryStep.stage(page)
    val busy = runtime is RuntimeState.Initializing || importing
    BackHandler(enabled = stage == 1) { entryStep = OnboardingIntroStep.WELCOME }
    BackHandler(enabled = stage == 2 && !busy) { entryStep = OnboardingIntroStep.DISCLAIMER }
    // Linux is already ready here: go back to the notice without offering a second installation.
    BackHandler(enabled = stage == 3) { entryStep = OnboardingIntroStep.DISCLAIMER }
    BackHandler(enabled = stage == 4) { viewModel?.setPage(1) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(WindowInsets.statusBars.asPaddingValues())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (stage == 1 || (stage == 2 && !busy) || stage == 3 || stage == 4) {
                    RuntimeIconButton(onClick = {
                        when (stage) {
                            4 -> viewModel?.setPage(1)
                            3 -> entryStep = OnboardingIntroStep.DISCLAIMER
                            else -> entryStep = entryStep.previous()
                        }
                    }, contentDescription = stringResource(R.string.onboarding_back)) {
                        RuntimeIcon(RuntimeIconName.Back, Modifier.size(24.dp))
                    }
                } else {
                    Box(Modifier.size(48.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    repeat(5) { index ->
                        Surface(
                            shape = MaterialTheme.shapes.extraLarge,
                            color = if (index == stage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(width = if (index == stage) 32.dp else 8.dp, height = 8.dp),
                        ) {}
                    }
                }
                Text(stringResource(R.string.onboarding_step, stage + 1), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
        },
        bottomBar = {
            if (stage == 0) {
                Box(Modifier.fillMaxWidth().padding(WindowInsets.navigationBars.asPaddingValues())
                    .padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
                    RuntimeButton(
                        onClick = { entryStep = entryStep.advance(acknowledged) },
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.onboarding_start),
                            modifier = Modifier.padding(4.dp))
                    }
                }
            }
            if (stage == 1) {
                OnboardingDisclaimerActions(
                    acknowledged = acknowledged,
                    onAcknowledge = { acknowledged = it },
                    onContinue = { entryStep = entryStep.advance(acknowledged) },
                )
            }
            if (stage == 3) {
                PermissionContinueActions(onContinue = { viewModel?.setPage(2) })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(targetState = stage, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "onboardingStage") { current ->
                when (current) {
                    0 -> OnboardingWelcomePage(Modifier.fillMaxSize())
                    1 -> OnboardingDisclaimerPage(Modifier.widthIn(max = 840.dp).fillMaxSize())
                    2 -> SystemSetupPage(viewModel, Modifier.widthIn(max = 840.dp).fillMaxSize())
                    3 -> OnboardingPermissionPage(
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                        onOpenAutostartSettings = onOpenAutostartSettings,
                        onNotificationGranted = onNotificationGranted,
                    )
                    else -> ModelSetupPage(viewModel, Modifier.widthIn(max = 840.dp).fillMaxSize())
                }
            }
        }
    }
}
