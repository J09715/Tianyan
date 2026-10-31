package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import top.tianyan.app.ui.components.RuntimeFilledTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.tianyan.app.core.model.StorageMountBinding
import top.tianyan.app.ui.components.RuntimeAlertDialog
import top.tianyan.app.ui.components.RuntimeButton as Button
import top.tianyan.app.ui.components.RuntimeTextButton as TextButton
import top.tianyan.app.ui.settings.LocalizedText as Text

@Composable
internal fun AddMountDialog(
    existingGuestPaths: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, hostPath: String, guestPath: String) -> Unit,
) {
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var hostPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/storage/emulated/0/") }
    var guestPath by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("/mnt/") }

    val nameValid = name.isNotBlank()
    val trimmedHost = hostPath.trim()
    val hostCharInvalid = ':' in trimmedHost
    val hostRootAllowed = StorageMountBinding.isHostPathAllowed(trimmedHost)
    val hostPathValid = hostPath.isNotBlank() && !hostCharInvalid && hostRootAllowed

    // 与 SettingsViewModel.addCustomMountBinding 的入参归一保持一致：无前导 / 自动补
    val trimmedGuest = guestPath.trim().let { if (it.startsWith("/")) it else "/$it" }
    val guestDuplicate = trimmedGuest in existingGuestPaths
    val guestNormalized = StorageMountBinding.normalizeGuestPath(trimmedGuest)
    val guestCharInvalid = ':' in trimmedGuest
    val guestRootAllowed = guestNormalized != null && StorageMountBinding.isGuestPathAllowed(trimmedGuest)
    val guestPathValid = guestPath.isNotBlank() && guestNormalized != null &&
        guestRootAllowed && !guestCharInvalid && !guestDuplicate

    RuntimeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增存储挂载点", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RuntimeFilledTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("挂载点名称") },
                    placeholder = { Text("例如：相册照片、项目源码") },
                    isError = !nameValid,
                    supportingText = { if (!nameValid) Text("请输入挂载点名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RuntimeFilledTextField(
                    value = hostPath,
                    onValueChange = { hostPath = it },
                    label = { Text("宿主路径 (Android)") },
                    placeholder = { Text("/storage/emulated/0/...") },
                    isError = hostPath.isNotBlank() && !hostPathValid,
                    supportingText = {
                        when {
                            hostPath.isNotBlank() && hostCharInvalid -> Text("宿主路径不能包含冒号等非法字符")
                            hostPath.isNotBlank() && !hostRootAllowed ->
                                Text("宿主路径必须位于 /storage/emulated/0 内")
                            else -> {}
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                RuntimeFilledTextField(
                    value = guestPath,
                    onValueChange = { guestPath = it },
                    label = { Text("容器挂载路径 (Linux)") },
                    placeholder = { Text("/mnt/my_folder") },
                    isError = guestPath.isNotBlank() && !guestPathValid,
                    supportingText = {
                        when {
                            guestPath.isNotBlank() && !guestPath.trim().startsWith("/") -> Text("路径必须以 / 开头")
                            guestPath.isNotBlank() && guestNormalized == null -> Text("容器路径不允许包含 ..")
                            guestPath.isNotBlank() && guestCharInvalid -> Text("容器路径不能包含冒号等非法字符")
                            guestPath.isNotBlank() && !guestRootAllowed ->
                                Text("容器路径必须位于 /mnt 或 /sdcard 内")
                            guestDuplicate -> Text("该容器路径已被其他挂载点使用")
                            else -> {}
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, hostPath, guestPath) },
                enabled = nameValid && hostPathValid && guestPathValid,
            ) {
                Text("添加挂载")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
