package top.tianyan.app.harness.groupchat

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.runtime.RuntimePathManager

internal data class PreparedGroupAttachments(val description: String = "", val imageUrls: List<String> = emptyList())

/** Copies SAF inputs to the existing /attachments mount; URI labels alone are not model inputs. */
class BotAttachmentPreparer(private val context: Context, private val paths: RuntimePathManager) {
    internal suspend fun prepare(attachments: List<GroupAttachment>): PreparedGroupAttachments = withContext(Dispatchers.IO) {
        require(attachments.size <= 5) { "最多发送 5 个附件" }
        if (attachments.isEmpty()) return@withContext PreparedGroupAttachments()
        val folderName = "bot-input/${UUID.randomUUID()}"
        val directory = File(paths.attachmentsDir, folderName)
        check(directory.mkdirs()) { "无法创建附件目录" }
        try {
            var total = 0L
            val images = mutableListOf<String>()
            val descriptions = attachments.mapIndexed { index, attachment ->
                val uri = Uri.parse(attachment.uri)
                require(uri.scheme == "content") { "仅支持通过文件选择器授权的附件" }
                val name = attachment.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(80).ifBlank { "attachment" }
                val fileName = "$index-$name"
                val target = File(directory, fileName)
                val mime = context.contentResolver.getType(uri).orEmpty()
                var size = 0L
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取附件：${attachment.name}" }
                input.use { source ->
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(size <= MAX_FILE_BYTES && total <= MAX_TOTAL_BYTES) { "附件超限：单个最多 20 MiB，总计最多 32 MiB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (mime in IMAGE_TYPES) {
                    require(size <= MAX_IMAGE_BYTES) { "图片最多 4 MiB" }
                    images += "data:$mime;base64,${Base64.encodeToString(target.readBytes(), Base64.NO_WRAP)}"
                }
                "- $name：/attachments/$folderName/$fileName"
            }
            PreparedGroupAttachments(descriptions.joinToString("\n"), images)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 20L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
        const val MAX_IMAGE_BYTES = 4L * 1024 * 1024
        val IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
    }
}
