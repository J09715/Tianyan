package top.tianyan.app.ui.onboarding

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.tianyan.app.core.tools.AgentProviderDefinition
import top.tianyan.app.feature.onboarding.R
import top.tianyan.app.ui.components.*

@Composable
internal fun ModelSetupPage(viewModel: OnboardingViewModel?, modifier: Modifier) {
    val provider by viewModel?.modelProvider?.collectAsStateWithLifecycle() ?: previewStateOf("OpenAI 兼容接口")
    val model by viewModel?.modelId?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val baseUrl by viewModel?.baseUrl?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val apiKey by viewModel?.apiKey?.collectAsStateWithLifecycle() ?: previewStateOf("")
    val discovered by viewModel?.discoveredModels?.collectAsStateWithLifecycle() ?: previewStateOf(emptyList())
    val discovering by viewModel?.discoveringModels?.collectAsStateWithLifecycle() ?: previewStateOf(false)
    val discoveryError by viewModel?.modelDiscoveryError?.collectAsStateWithLifecycle() ?: previewStateOf(null)
    val providers = viewModel?.providerCatalog ?: listOf(AgentProviderDefinition("custom", provider, "", group = top.tianyan.app.core.tools.ProviderGroup.CUSTOM))
    val selectedProvider = providers.firstOrNull { it.name == provider } ?: providers.first()

    val context = LocalContext.current
    var showImportDialog by remember { mutableStateOf(false) }
    var keyRevealed by remember { mutableStateOf(false) }
    var providerMenuExpanded by remember { mutableStateOf(false) }

    val candidateModels = remember(discovered, selectedProvider) {
        (discovered + selectedProvider.recommendedModels).distinct().filter { it.isNotBlank() }
    }

    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = runtimeContentPadding(horizontal = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.largeIncreased, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        RuntimeIcon(RuntimeIconName.Brain, Modifier.padding(12.dp).size(24.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.weight(1f))
                    RuntimeTextButton(onClick = { showImportDialog = true }) {
                        RuntimeIcon(RuntimeIconName.Download, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.model_setup_import))
                    }
                }
                Text(stringResource(R.string.onboarding_connect_model), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.onboarding_connect_model_description), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Box {
                RuntimeProviderCard(
                    providerId = selectedProvider.id, name = selectedProvider.name,
                    description = stringResource(if (selectedProvider.protocol == top.tianyan.app.core.tools.ProviderProtocol.ANTHROPIC) R.string.model_setup_protocol_anthropic else R.string.model_setup_protocol_openai),
                    onClick = { providerMenuExpanded = true },
                )
                DropdownMenu(expanded = providerMenuExpanded, onDismissRequest = { providerMenuExpanded = false },
                    modifier = Modifier.heightIn(max = 360.dp)) {
                    providers.forEach { option ->
                        DropdownMenuItem(
                            leadingIcon = { ProviderBadge(option.id, size = 24.dp) },
                            text = { Text(option.name, style = MaterialTheme.typography.bodyMedium) },
                            trailingIcon = if (option.id == selectedProvider.id) {
                                { RuntimeIcon(RuntimeIconName.Check, Modifier.size(20.dp)) }
                            } else null,
                            onClick = { viewModel?.selectProvider(option.id); providerMenuExpanded = false },
                        )
                    }
                }
            }
        }
        item {
            RuntimeCard(shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.model_setup_connection), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    RuntimeFilledTextField(
                        value = baseUrl, onValueChange = { viewModel?.setBaseUrl(it) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.model_setup_url)) },
                        placeholder = { Text("https://api.openai.com/v1") },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Globe, Modifier.size(20.dp)) },
                        trailingIcon = {
                            RuntimeIconButton(onClick = { viewModel?.discoverModels() }, enabled = !discovering && baseUrl.isNotBlank(),
                                contentDescription = stringResource(R.string.model_setup_discover)) {
                                if (discovering) RuntimeCircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                else RuntimeIcon(RuntimeIconName.Refresh, Modifier.size(20.dp))
                            }
                        },
                    )
                    RuntimeFilledTextField(
                        value = apiKey, onValueChange = { viewModel?.setApiKey(it) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(stringResource(R.string.model_setup_key)) }, placeholder = { Text("sk-…") },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Key, Modifier.size(20.dp)) },
                        visualTransformation = if (keyRevealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            if (apiKey.isNotEmpty()) RuntimeIconButton(onClick = { keyRevealed = !keyRevealed },
                                contentDescription = stringResource(if (keyRevealed) R.string.model_setup_hide_key else R.string.model_setup_show_key)) {
                                RuntimeIcon(if (keyRevealed) RuntimeIconName.VisibilityOff else RuntimeIconName.Visibility, Modifier.size(20.dp))
                            }
                        },
                    )
                }
            }
        }
        item {
            RuntimeCard(shape = MaterialTheme.shapes.extraLarge,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.model_setup_model), style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    RuntimeFilledTextField(
                        value = model, onValueChange = { viewModel?.setModelId(it) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Model ID") }, placeholder = { Text("gpt-4o / deepseek-chat") },
                        leadingIcon = { RuntimeIcon(RuntimeIconName.Model, Modifier.size(20.dp)) },
                    )
                    OnboardingModelCandidates(candidateModels, model) { viewModel?.setModelId(it) }
                }
            }
        }
        discoveryError?.let { err -> item { Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RuntimeButton(onClick = { viewModel?.saveModelAndFinish() }, enabled = model.isNotBlank() && baseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_enter))
                    Spacer(Modifier.width(8.dp))
                    RuntimeIcon(RuntimeIconName.ChevronRight, Modifier.size(20.dp))
                }
                RuntimeTextButton(onClick = { viewModel?.skipModel() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onboarding_later))
                }
            }
        }
    }

    // JSON 导入对话框
    if (showImportDialog) {
        OnboardingModelImportDialog(
            onDismiss = { showImportDialog = false },
            onImportJson = { jsonStr ->
                val result = viewModel?.importProfileFromJson(jsonStr)
                result?.fold(
                    onSuccess = { profile ->
                        Toast.makeText(context, "成功载入模型配置「${profile.name.ifBlank { profile.model }}」", Toast.LENGTH_SHORT).show()
                        showImportDialog = false
                    },
                    onFailure = { err ->
                        Toast.makeText(context, "解析失败: ${err.message}", Toast.LENGTH_LONG).show()
                    },
                )
            },
        )
    }
}
