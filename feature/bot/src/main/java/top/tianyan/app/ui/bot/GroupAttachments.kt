package top.tianyan.app.ui.bot

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import top.tianyan.app.core.model.bot.GroupAttachment

internal const val MAX_ATTACHMENTS = 5

/** 读取所选文档的显示名/类型/大小，并申请持久化读权限，重启后仍可打开。 */
internal fun pickAttachments(context: Context, uris: List<Uri>): List<GroupAttachment> = uris.mapNotNull { uri ->
    runCatching {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "附件"
        var size = 0L
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        }
        GroupAttachment(
            name = name,
            uri = uri.toString(),
            mimeType = context.contentResolver.getType(uri).orEmpty(),
            sizeBytes = size,
        )
    }.getOrNull()
}
