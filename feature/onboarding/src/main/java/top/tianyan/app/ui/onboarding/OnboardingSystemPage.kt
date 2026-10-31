package top.tianyan.app.ui.onboarding

import top.tianyan.app.ui.components.previewStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.DistributionCatalog
import top.tianyan.app.ui.components.RuntimeCard
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.runtimeContentPadding
import androidx.compose.foundation.layout.heightIn
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeLinearProgressIndicator
import top.tianyan.app.ui.components.RuntimeTextButton

private data class SetupOption(val id: String, val label: String)

private val distributionOptions = DistributionCatalog.supported.map { SetupOption(it.id, it.displayWithVersion) }

@Composable
internal fun SystemSetupPage(viewModel: OnboardingViewModel?, modifier: Modifier) {
    val distribution by viewModel?.distribution?.collectAsStateWithLifecycle() ?: previewStateOf("ubuntu")
    val mirror by viewModel?.mirror?.collectAsStateWithLifecycle() ?: previewStateOf("auto")
    val state by viewModel?.runtimeState?.collectAsStateWithLifecycle() ?: previewStateOf(top.tianyan.app.core.model.RuntimeState.Ready)
    val importing by viewModel?.isImporting?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val importError by viewModel?.importError?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val installing = state is RuntimeState.Initializing || importing
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let({ arg0 -> viewModel?.importArchive(arg0) }) }
    val mirrorOptions = listOf(
        SetupOption("auto", stringResource(R.string.onboarding_mirror_auto)),
        SetupOption("official", stringResource(R.string.onboarding_mirror_official)),
        SetupOption("china", stringResource(R.string.onboarding_mirror_china)),
    )

    LazyColumn(
        modifier = modifier,
        contentPadding = runtimeContentPadding(horizontal = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OnboardingPageHeading(
                title = stringResource(R.string.onboarding_title),
                description = stringResource(R.string.onboarding_description),
            )
        }
        item {
            SetupDropdown(
                label = stringResource(R.string.onboarding_distribution),
                selectedId = distribution,
                options = distributionOptions,
                enabled = !installing,
                onSelected = { arg0 -> viewModel?.selectDistribution(arg0) },
            )
        }
        item {
            SetupDropdown(
                label = stringResource(R.string.onboarding_mirror),
                selectedId = mirror,
                options = mirrorOptions,
                enabled = !installing,
                onSelected = { arg0 -> viewModel?.selectMirror(arg0) },
            )
        }
        if (state is RuntimeState.Initializing) {
            val progress = state as RuntimeState.Initializing
            item {
                RuntimeCard(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = MaterialTheme.shapes.extraLarge,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = progress.step,
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${(progress.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        RuntimeLinearProgressIndicator(
                            progress = { progress.progress },
                            modifier = Modifier.fillMaxWidth().height(10.dp).clip(MaterialTheme.shapes.small),
                            color = MaterialTheme.colorScheme.primary,
                            wavy = true,
                        )
                        progress.detail?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
        (state as? RuntimeState.Error)?.let { error ->
            item {
                Text(
                    error.throwable.message ?: stringResource(R.string.onboarding_initialization_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            RuntimeButton(
                onClick = { viewModel?.install() },
                enabled = !installing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(
                    stringResource(
                        if (state is RuntimeState.Error) R.string.onboarding_retry
                        else if (installing) R.string.onboarding_preparing
                        else R.string.onboarding_initialize,
                    ),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        importError?.let { error ->
            item {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item {
            RuntimeTextButton(
                onClick = { importLauncher.launch(arrayOf("application/gzip", "application/x-gzip", "application/x-tar", "application/x-xz", "application/octet-stream")) },
                enabled = !installing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.onboarding_import))
            }
        }
        item {
            Text(
                stringResource(R.string.onboarding_import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state is RuntimeState.Error) {
            item {
                RuntimeTextButton(
                    onClick = { viewModel?.retryReady() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.onboarding_recheck), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupDropdown(
    label: String,
    selectedId: String,
    options: List<SetupOption>,
    enabled: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedId } ?: options.first()
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = !expanded },
    ) {
        RuntimeFilledTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            shape = MaterialTheme.shapes.large,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(
                ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                enabled,
            ),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelected(option.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
