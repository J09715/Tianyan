package top.tianyan.app.ui.settings

import top.tianyan.app.feature.settings.R
import java.io.IOException
import java.util.zip.ZipException

/** Only fixed local categories reach the UI; exception text can contain private paths or archive content. */
internal fun backupErrorMessage(failure: Exception): Int = when (failure.message) {
    "请先停止正在运行的智能体任务，再执行备份或恢复" -> R.string.local_backup_error_busy
    "备份版本与当前天衍不兼容", "备份数据分类不完整或不兼容", "备份字段与当前版本不兼容" ->
        R.string.local_backup_error_version
    "备份期间数据已变化，请重试", "数据已变化，请重新预览", "数据已变化，请重新选择备份并预览",
    "偏好设置已变化，请重新预览", "恢复预览已失效，请重新选择文件" -> R.string.local_backup_error_changed
    "缺少备份清单", "备份清单格式无效", "备份文件清单不一致", "备份文件校验失败", "恢复暂存文件校验失败" ->
        R.string.local_backup_error_archive
    else -> when (failure) {
        is ZipException -> R.string.local_backup_error_archive
        is SecurityException -> R.string.local_backup_error_access
        is IOException -> R.string.local_backup_error_io
        else -> R.string.local_backup_error
    }
}
