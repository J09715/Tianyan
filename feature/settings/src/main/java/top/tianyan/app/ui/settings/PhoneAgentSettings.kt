package top.tianyan.app.ui.settings
import top.tianyan.app.ui.components.previewOrNull
import top.tianyan.app.ui.components.previewStateOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import top.tianyan.app.core.datastore.AgentPreferences
import top.tianyan.app.core.datastore.PhoneAgentEndpoint
import top.tianyan.app.core.tools.AgentModelConnectionTester
import top.tianyan.app.ui.components.RuntimeButton
import top.tianyan.app.ui.components.RuntimeCircularProgressIndicator
import top.tianyan.app.ui.components.RuntimeIconName
import top.tianyan.app.ui.components.RuntimeOutlinedButton
import top.tianyan.app.ui.components.RuntimeTextButton
import top.tianyan.app.ui.components.RuntimeTopBar
import top.tianyan.app.ui.settings.LocalizedText as Text
class PhoneAgentSettingsViewModel(
    private val preferences: AgentPreferences,
    private val connectionTester: AgentModelConnectionTester,
) : ViewModel() {
    val config: Flow<PhoneAgentEndpoint> = preferences.phoneAgentConfig
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    fun save(value: PhoneAgentEndpoint) {
        viewModelScope.launch { preferences.setPhoneAgentConfig(value) }
    }

    fun test(baseUrl: String, model: String, apiKey: String, onResult: (String) -> Unit) {
        val error = phoneAgentConfigError(baseUrl, model, apiKey)
        if (error != null) {
            onResult(error)
            return
        }
        viewModelScope.launch {
            _testing.value = true
            val result = runCatching {
                connectionTester.test(baseUrl.trim(), model.trim(), apiKey.trim())
            }.fold(
                onSuccess = { "连接成功" },
                onFailure = { it.message?.take(180) ?: "连接失败，请检查接口地址、密钥与网络" },
            )
            _testing.value = false
            onResult(result)
        }
    }
}

@Composable
internal fun PhoneAgentSettingsEntry(
    onOpen: () -> Unit,
    viewModel: PhoneAgentSettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val config by viewModel?.config?.collectAsStateWithLifecycle(PhoneAgentEndpoint()) ?: previewStateOf(PhoneAgentEndpoint())
    SettingsRow(
        icon = RuntimeIconName.Android,
        title = "手机操作模型",
        subtitle = "单独的接口、模型名和密钥，不会出现在聊天模型里",
        value = config.model.ifBlank { "未配置" },
        onClick = onOpen, tonalIcon = true,
    )
}

@Composable
fun PhoneAgentSettingsScreen(
    onBack: () -> Unit,
    viewModel: PhoneAgentSettingsViewModel? = previewOrNull { koinViewModel() },
) {
    val saved by viewModel?.config?.collectAsStateWithLifecycle(PhoneAgentEndpoint()) ?: previewStateOf(PhoneAgentEndpoint())
    var baseUrl by remember(saved) { mutableStateOf(saved.baseUrl) }
    var model by remember(saved) { mutableStateOf(saved.model) }
    var apiKey by remember(saved) { mutableStateOf(saved.apiKey) }
    var message by remember { mutableStateOf<String?>(null) }
    val testing by viewModel?.testing?.collectAsStateWithLifecycle() ?: previewStateOf(false)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { RuntimeTopBar("手机操作模型", onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .then(top.tianyan.app.ui.components.runtimeScrollViewport(padding))
                .verticalScroll(rememberScrollState()).padding(bottom = padding.calculateBottomPadding())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "这里只给虚拟屏用，看画面、点击、滑动和输入。聊天主模型仍在「模型档案管理」里配置，两边互不影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsGroup {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RuntimeFilledTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("接口地址") },
                        placeholder = { Text("https://open.bigmodel.cn/api/paas/v4") },
                        supportingText = { Text("智谱开放平台用上面这个地址") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    RuntimeFilledTextField(
                        value = model,
                        onValueChange = { model = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名") },
                        placeholder = { Text("autoglm-phone") },
                        supportingText = { Text("智谱的手机操作模型名是 autoglm-phone") },
                        singleLine = true,
                    )
                    RuntimeFilledTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API 密钥") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                }
            }
            RuntimeOutlinedButton(
                onClick = { viewModel?.test(baseUrl, model, apiKey) { message = it } },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RuntimeCircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("正在测试…")
                    }
                } else {
                    Text("测试连接")
                }
            }
            RuntimeButton(
                onClick = {
                    val error = phoneAgentConfigError(baseUrl, model, apiKey)
                    if (error != null) {
                        message = error
                    } else {
                        viewModel?.save(PhoneAgentEndpoint(baseUrl.trim(), model.trim(), apiKey.trim()))
                        message = "已保存。聊天主模型不会用到这里。"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) { Text("保存") }
            RuntimeTextButton(
                onClick = {
                    baseUrl = ""
                    model = ""
                    apiKey = ""
                    viewModel?.save(PhoneAgentEndpoint())
                    message = "已清除"
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("清除") }
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun phoneAgentConfigError(baseUrl: String, model: String, apiKey: String): String? {
    val url = baseUrl.trim()
    if (url.isBlank() || model.isBlank() || apiKey.isBlank()) return "接口地址、模型名和密钥都要填写"
    val local = url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
    if (!url.startsWith("https://") && !local) return "外部接口必须使用 https://"
    return null
}
