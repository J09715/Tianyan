package top.tianyan.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.*
import top.tianyan.app.ui.components.*
import java.util.UUID

@Composable
internal fun RemoteServerDialog(viewModel: RemoteServerViewModel, existing: RemoteServer? = null, onDismiss: () -> Unit) {
    val state by viewModel.connectionState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "root") }
    var workspace by remember { mutableStateOf(existing?.workspace ?: "/root") }
    var keyMode by remember { mutableStateOf(existing?.authentication == SshAuthentication.PRIVATE_KEY) }
    var secret by remember { mutableStateOf("") }
    var phrase by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    var showSecret by remember { mutableStateOf(false) }
    val serverId = remember { existing?.id ?: UUID.randomUUID().toString() }
    fun server() = RemoteServer(serverId, name.trim(), host.trim(), port.toIntOrNull() ?: 0,
        username.trim(), if (keyMode) SshAuthentication.PRIVATE_KEY else SshAuthentication.PASSWORD, workspace.trim(),
        existing?.takeIf { it.host == host.trim() && it.port == port.toIntOrNull() }?.fingerprint.orEmpty())
    val valid = name.isNotBlank() && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535 &&
        username.isNotBlank() && workspace.startsWith('/') && secret.isNotBlank()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(existing?.id) {
        if (existing != null) {
            try { val credentials = viewModel.credentials(existing.id); secret = credentials.secret; phrase = credentials.passphrase }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { importError = "读取凭证失败，请重新填写认证信息" }
        }
    }
    val importKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 65536)
                            output.write(buffer, 0, count)
                        }
                        String(output.toByteArray(), Charsets.UTF_8)
                    } ?: error("文件不可读")
                }
                secret = content
                importError = null
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { importError = "私钥读取失败（最大 64 KiB）" }
        }
    }
    LaunchedEffect(state.saved) { if (state.saved) { secret = ""; phrase = ""; onDismiss(); viewModel.clear() } }
    if (state.fingerprint != null) {
        RuntimeAlertDialog(
            onDismissRequest = { viewModel.clear() },
            title = { Text("确认服务器身份") },
            text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text("请与服务器管理员提供的 SSH 主机指纹核对。确认后才会发送密码或使用私钥认证。")
                Spacer(Modifier.height(12.dp))
                Text(state.fingerprint.orEmpty(), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { RuntimeTextButton(onClick = viewModel::confirmFingerprint) { Text("指纹一致，连接") } },
            dismissButton = { RuntimeTextButton(onClick = viewModel::clear) { Text("取消") } },
        )
        return
    }
    RuntimeAlertDialog(
        onDismissRequest = { if (!state.busy) { viewModel.clear(); onDismiss() } },
        title = { Text(if (existing == null) "新增连接" else "编辑连接") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("保存 SSH 连接档案后，可选择它作为主系统。每个档案独立保存认证信息。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true, enabled = !state.busy)
                OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("主机地址") }, singleLine = true, enabled = !state.busy)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(port, { port = it }, modifier = Modifier.weight(1f), label = { Text("端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = !state.busy)
                    OutlinedTextField(username, { username = it }, modifier = Modifier.weight(1f), label = { Text("用户名") }, singleLine = true, enabled = !state.busy)
                }
                OutlinedTextField(workspace, { workspace = it }, modifier = Modifier.fillMaxWidth(), label = { Text("工作目录") }, supportingText = { Text("服务器上已存在的目录，需允许当前用户访问") }, singleLine = true, enabled = !state.busy)
                HorizontalDivider()
                Text("认证方式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuntimeOutlinedButton(modifier = Modifier.weight(1f), onClick = { if (keyMode) { keyMode = false; secret = "" } }, enabled = !state.busy) { Text(if (!keyMode) "✓ 密码" else "密码") }
                    RuntimeOutlinedButton(modifier = Modifier.weight(1f), onClick = { if (!keyMode) { keyMode = true; secret = "" } }, enabled = !state.busy) { Text(if (keyMode) "✓ SSH 私钥" else "SSH 私钥") }
                }
                OutlinedTextField(secret, { secret = it }, modifier = Modifier.fillMaxWidth(), label = { Text(if (keyMode) "PEM / OpenSSH 私钥" else "密码") },
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(), singleLine = !keyMode, maxLines = 5, enabled = !state.busy,
                    trailingIcon = { RuntimeTextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "隐藏" else "显示") } })
                if (keyMode) {
                    RuntimeTextButton(onClick = { importKey.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("从文件导入私钥") }
                    OutlinedTextField(phrase, { phrase = it }, modifier = Modifier.fillMaxWidth(), label = { Text("私钥口令（可选）") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), enabled = !state.busy)
                }
                HorizontalDivider()
                Text("连接测试", style = MaterialTheme.typography.labelLarge)
                Text("测试不会保存配置。首次连接需核对主机指纹，服务器需启用 SSH / SFTP。", style = MaterialTheme.typography.bodySmall)
                RuntimeOutlinedButton(enabled = valid && !state.busy, onClick = { viewModel.test(server(), SshCredentials(secret, phrase)) }) { Text("测试连接") }
                (state.message ?: importError)?.let { Text(it, color = if (state.tested) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                if (state.busy) RuntimeLinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { RuntimeTextButton(enabled = valid && !state.busy, onClick = {
            viewModel.connect(server(), SshCredentials(secret, phrase))
        }) { Text(if (state.busy) "验证中…" else "保存") } },
        dismissButton = { RuntimeTextButton(enabled = !state.busy, onClick = { viewModel.clear(); onDismiss() }) { Text("取消") } },
    )
}
