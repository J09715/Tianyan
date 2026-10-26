package top.tianyan.app.ui.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import top.tianyan.app.core.common.files.BoundedStreamCopy
import top.tianyan.app.core.common.files.PayloadTooLargeException
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.tianyan.app.feature.chat.R
import top.tianyan.app.ui.components.RuntimeIcon
import top.tianyan.app.ui.theme.semanticColors
import top.tianyan.app.ui.components.RuntimeIconName
import java.io.File
import java.util.Locale
import java.util.UUID
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 用户选取的图片或附件元数据
 */
data class ChatAttachment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val isImage: Boolean,
    val sizeBytes: Long,
    val uri: Uri,
    val hostFilePath: String? = null,
    val guestFilePath: String? = null,
    val base64DataUrl: String? = null,
) {
    val localFilePath: String get() = guestFilePath ?: hostFilePath.orEmpty()
}

/** 附件处理结果：成功 / 超限（附实际大小与上限，用于大小范围提示）/ 其他失败 */
sealed interface AttachmentProcessResult {
    data class Success(val attachment: ChatAttachment) : AttachmentProcessResult
    data class TooLarge(val name: String, val sizeBytes: Long, val maxBytes: Long) : AttachmentProcessResult
    data class Failed(val name: String) : AttachmentProcessResult
}

/** 附件批量处理结果：成功项 + 需要 Toast 上抛的提示（含超限大小范围） */
data class AttachmentProcessOutcome(
    val items: List<ChatAttachment>,
    val notice: String?,
)

object AttachmentHelper {
    private const val TAG = "AttachmentHelper"

    fun processUri(context: Context, uri: Uri, isImage: Boolean): AttachmentProcessResult {
        val contentResolver = context.contentResolver
        var name = if (isImage) "image_${System.currentTimeMillis()}.jpg" else "file_${System.currentTimeMillis()}"
        var size = 0L

        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        }

        // 预检：cursor 能拿到真实大小时，超限直接拦截，避免先复制再中止浪费 IO。
        val limit = BoundedStreamCopy.DEFAULT_MAX_ATTACHMENT_BYTES
        if (exceedsLimit(size, limit)) {
            return AttachmentProcessResult.TooLarge(name, size, limit)
        }

        // 统一复制到所有发行版共用的 Runtime 目录，PRoot 固定挂载为 /attachments。
        val attachmentsDir = File(context.filesDir, "linux-runtime/attachments").apply { mkdirs() }
        val safeName = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val targetFile = File(attachmentsDir, "${System.currentTimeMillis()}_$safeName")

        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                BoundedStreamCopy.copyToFile(
                    input = input,
                    targetFile = targetFile,
                    maxBytes = BoundedStreamCopy.DEFAULT_MAX_ATTACHMENT_BYTES,
                    policy = BoundedStreamCopy.OverflowPolicy.ABORT,
                )
            } ?: return AttachmentProcessResult.Failed(name)
            if (size == 0L) size = targetFile.length()

            val guestPath = "/attachments/${targetFile.name}"
            val sourceMimeType = contentResolver.getType(uri)
                ?.takeIf { it.startsWith("image/", ignoreCase = true) }
                ?: "image/jpeg"
            val base64 = if (isImage) {
                runCatching {
                    // 与 ImagePayloadCompressor 对齐：长边 1280、JPEG 80，避免相册原图把请求体顶到 413。
                    val boundsOptions = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    BitmapFactory.decodeFile(targetFile.absolutePath, boundsOptions)

                    var inSampleSize = 1
                    val maxDimension = 1280
                    if (boundsOptions.outHeight > maxDimension || boundsOptions.outWidth > maxDimension) {
                        val halfHeight = boundsOptions.outHeight / 2
                        val halfWidth = boundsOptions.outWidth / 2
                        while ((halfHeight / inSampleSize) >= maxDimension || (halfWidth / inSampleSize) >= maxDimension) {
                            inSampleSize *= 2
                        }
                    }

                    val decodeOptions = BitmapFactory.Options().apply {
                        this.inSampleSize = inSampleSize
                    }
                    val bitmap = BitmapFactory.decodeFile(targetFile.absolutePath, decodeOptions)
                    if (bitmap != null) {
                        val baos = ByteArrayOutputStream()
                        try {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        } finally {
                            bitmap.recycle()
                        }
                        val bytes = baos.toByteArray()
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        "data:image/jpeg;base64,$encoded"
                    } else if (targetFile.length() <= 4L * 1024 * 1024) {
                        val bytes = targetFile.readBytes()
                        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        "data:$sourceMimeType;base64,$encoded"
                    } else {
                        null
                    }
                }.getOrNull()
            } else null

            AttachmentProcessResult.Success(
                ChatAttachment(
                    name = name,
                    isImage = isImage,
                    sizeBytes = size,
                    uri = uri,
                    hostFilePath = targetFile.absolutePath,
                    guestFilePath = guestPath,
                    base64DataUrl = base64,
                ),
            )
        } catch (e: PayloadTooLargeException) {
            // 流式拷贝中触发超限：以实际观测大小构造提示，保证"大小范围"信息完整
            Log.w(TAG, "附件超限 uri=$uri size=${e.actualBytesObserved} max=${e.maxBytes}")
            AttachmentProcessResult.TooLarge(name, e.actualBytesObserved, e.maxBytes)
        } catch (e: Exception) {
            // 失败不能静默：记录日志并由 ChatViewModel 统计后以 Toast 上抛给用户
            Log.w(TAG, "附件处理失败 uri=$uri", e)
            AttachmentProcessResult.Failed(name)
        }
    }

    /** 纯判定：sizeBytes 是否超过 maxBytes（0 表示未知大小，不拦截，交给拷贝后兜底） */
    internal fun exceedsLimit(sizeBytes: Long, maxBytes: Long = BoundedStreamCopy.DEFAULT_MAX_ATTACHMENT_BYTES): Boolean =
        sizeBytes > maxBytes

    /** 清理 host 侧已拷贝文件；移除附件时调用，避免 200MB 上限下撑满 filesDir */
    fun deleteHostFile(path: String?) {
        if (path == null) return
        runCatching { File(path).delete() }
            .onFailure { Log.w(TAG, "删除附件 host 文件失败 path=$path", it) }
    }

    /** 纯聚合：按 TooLarge/Failed 计数拼装提示；无异常时返回 null（保持单测不依赖 Context） */
    internal fun buildAttachmentNotice(
        tooLargeCount: Int,
        failedCount: Int,
        limitLabel: String,
        tooLargeMessage: (count: Int, limitLabel: String) -> String,
        failedMessage: (count: Int) -> String,
    ): String? {
        if (tooLargeCount <= 0 && failedCount <= 0) return null
        return buildList {
            if (tooLargeCount > 0) add(tooLargeMessage(tooLargeCount, limitLabel))
            if (failedCount > 0) add(failedMessage(failedCount))
        }.joinToString("；")
    }

    /** 批量处理附件并聚合超限/失败提示；通知文案在此拼装，保持 ViewModel 精简 */
    fun processPicked(context: Context, uris: List<Uri>, isImage: Boolean): AttachmentProcessOutcome {
        val results = uris.map { processUri(context, it, isImage) }
        val items = results.mapNotNull { (it as? AttachmentProcessResult.Success)?.attachment }
        val tooLargeCount = results.count { it is AttachmentProcessResult.TooLarge }
        val failedCount = results.count { it is AttachmentProcessResult.Failed }
        val notice = buildAttachmentNotice(
            tooLargeCount = tooLargeCount,
            failedCount = failedCount,
            limitLabel = formatFileSize(results.filterIsInstance<AttachmentProcessResult.TooLarge>().firstOrNull()?.maxBytes ?: BoundedStreamCopy.DEFAULT_MAX_ATTACHMENT_BYTES),
            tooLargeMessage = { count, label -> context.getString(R.string.chat_attachment_too_large, count, label) },
            failedMessage = { count -> context.getString(R.string.chat_attachment_process_failed, count) },
        )
        return AttachmentProcessOutcome(items, notice)
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }
}

/**
 * 待发送附件预览栏
 */
@Composable
fun AttachmentPreviewRow(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    LazyRow(
        modifier = modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { item ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (item.isImage) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                else semanticColors().success.copy(alpha = 0.15f),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        RuntimeIcon(
                            name = if (item.isImage) RuntimeIconName.Image else RuntimeIconName.Attach,
                            modifier = Modifier.size(13.dp),
                            tint = if (item.isImage) MaterialTheme.colorScheme.primary else semanticColors().success,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = AttachmentHelper.formatFileSize(item.sizeBytes),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    val removeLabel = stringResource(R.string.chat_remove_attachment)
                    Box(
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clickable { onRemove(item) }
                            .semantics { contentDescription = removeLabel },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            RuntimeIcon(
                                name = RuntimeIconName.Close,
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
