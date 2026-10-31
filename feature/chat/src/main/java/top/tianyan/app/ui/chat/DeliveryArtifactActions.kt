package top.tianyan.app.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.tianyan.app.core.common.files.FileDigest
import top.tianyan.app.feature.chat.R
import top.tianyan.app.harness.task.DeliveryArtifact
import top.tianyan.app.harness.task.DeliveryArtifactKind
import top.tianyan.app.runtime.HostFileActions
import top.tianyan.app.ui.components.LocalSandboxHostRoots
import top.tianyan.app.ui.components.RuntimeTextButton

/** 交付产物的当前磁盘状态：只有与记录哈希一致时才敢说「这就是记录的版本」。 */
internal enum class DeliveryIntegrity { UNRESOLVED, MISSING, PENDING, MATCHED, CHANGED }

/**
 * 交付卡片里的一份产物：图片给缩略图，其他给打开/分享/另存入口。
 *
 * 哈希核验在后台做一次（`FileDigest` 是流式的，不会把 128MB 的包读进内存）；
 * 文件不存在或已改动都会显式提示，而不是假装成功。
 */
@Composable
internal fun DeliveryArtifactRow(
    artifact: DeliveryArtifact,
    workspace: String,
    onOpenFile: ((String, String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val hostRoots = LocalSandboxHostRoots.current
    val file = remember(artifact.path, workspace, hostRoots) {
        DeliveryArtifactPaths.hostFile(workspace, artifact.path, hostRoots["workspace"])
    }
    // 指纹随大小/修改时间变化，否则卡片一直停留在旧结论（agent 重写文件后仍显示「一致」）。
    val stamp = remember(file) { file?.let { "${it.length()}|${it.lastModified()}" } }
    val existing = remember(file, stamp) { file?.takeIf { it.isFile } }
    var integrity by remember(file, stamp, artifact.sha256) { mutableStateOf(DeliveryIntegrity.PENDING) }

    LaunchedEffect(file, stamp, artifact.sha256) {
        integrity = when {
            // 工作区不在宿主内部目录下（如 /sdcard 工程）时不猜路径，也不谎报「文件已丢失」。
            file == null -> DeliveryIntegrity.UNRESOLVED
            existing == null -> DeliveryIntegrity.MISSING
            else -> DeliveryIntegrityCache.verify(existing, artifact.sha256)
        }
    }

    val canAct = existing != null
    val scope = rememberCoroutineScope()
    val saveMime = remember(existing) { existing?.let { HostFileActions.mimeType(it) } ?: "application/octet-stream" }
    // SAF 另存：流式拷贝，不把 128MB 的包整份读进内存。
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(saveMime)) { uri ->
        val target = existing
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        target.inputStream().use { it.copyTo(output, 64 * 1024) }
                    } ?: error("无法写入目标位置")
                }.isSuccess
            }
            Toast.makeText(
                context,
                if (saved) R.string.delivery_artifact_saved else R.string.delivery_artifact_save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val launch: (Boolean) -> Unit = { share ->
        val target = existing
        if (target != null) {
            val ok = if (share) HostFileActions.share(context, target, subject = target.name)
            else HostFileActions.open(context, target)
            if (!ok) Toast.makeText(context, R.string.delivery_artifact_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (artifact.kind == DeliveryArtifactKind.IMAGE && existing != null) {
                AsyncImage(
                    model = existing,
                    contentDescription = artifact.path,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (canAct) launch(false) },
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            SelectionContainer(Modifier.weight(1f)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = artifact.path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artifact.path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    integrityLabel(integrity)?.let { label ->
                        Text(
                            text = stringResource(label),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (integrity == DeliveryIntegrity.MATCHED) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // 文本日志在编辑器里看最直接（有语法高亮/行号），不必先找个外部应用。
                    if (artifact.kind == DeliveryArtifactKind.OUTPUT_LOG && onOpenFile != null) {
                        RuntimeTextButton(onClick = {
                            DeliveryArtifactPaths.projectName(workspace)?.let { project -> onOpenFile(project, artifact.path) }
                        }) { Text(stringResource(R.string.delivery_artifact_view), maxLines = 1) }
                    }
                    RuntimeTextButton(onClick = { launch(false) }, enabled = canAct) {
                        Text(stringResource(R.string.delivery_artifact_open), maxLines = 1)
                    }
                    RuntimeTextButton(onClick = { launch(true) }, enabled = canAct) {
                        Text(stringResource(R.string.delivery_artifact_share), maxLines = 1)
                    }
                }
                RuntimeTextButton(
                    onClick = { existing?.let { saveLauncher.launch(it.name) } },
                    enabled = canAct,
                ) { Text(stringResource(R.string.delivery_artifact_save), maxLines = 1) }
            }
        }
        Text(
            text = stringResource(R.string.delivery_hash_at_creation),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = artifact.sha256,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 校验结果按「路径 + 大小 + mtime + 记录哈希」缓存，避免每次重入列表都对 128MB 的包重算一遍。 */
private object DeliveryIntegrityCache {
    private const val MAX_ENTRIES = 32
    private val entries = object : LinkedHashMap<String, DeliveryIntegrity>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DeliveryIntegrity>) = size > MAX_ENTRIES
    }

    suspend fun verify(file: File, recordedSha256: String): DeliveryIntegrity = withContext(Dispatchers.IO) {
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}|$recordedSha256"
        entries[key] ?: run {
            val digest = FileDigest.sha256(file)
            val result = when {
                digest == null -> DeliveryIntegrity.MISSING
                digest.equals(recordedSha256, ignoreCase = true) -> DeliveryIntegrity.MATCHED
                else -> DeliveryIntegrity.CHANGED
            }
            entries[key] = result
            result
        }
    }
}

private fun integrityLabel(integrity: DeliveryIntegrity): Int? = when (integrity) {
    DeliveryIntegrity.MISSING -> R.string.delivery_artifact_missing
    DeliveryIntegrity.CHANGED -> R.string.delivery_artifact_changed
    DeliveryIntegrity.PENDING, DeliveryIntegrity.MATCHED, DeliveryIntegrity.UNRESOLVED -> null
}