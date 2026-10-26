package top.tianyan.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.feature.settings.R
import java.io.IOException
import java.util.zip.ZipException

class LocalBackupErrorsTest {
    @Test fun `known restore failures give actionable categories`() {
        assertEquals(R.string.local_backup_error_version, backupErrorMessage(IllegalArgumentException("备份字段与当前版本不兼容")))
        assertEquals(R.string.local_backup_error_changed, backupErrorMessage(IllegalStateException("数据已变化，请重新预览")))
        assertEquals(R.string.local_backup_error_busy,
            backupErrorMessage(IllegalStateException("请先停止正在运行的智能体任务，再执行备份或恢复")))
    }

    @Test fun `external exception text is never used as a message`() {
        assertEquals(R.string.local_backup_error_io, backupErrorMessage(IOException("/private/path token=secret")))
        assertEquals(R.string.local_backup_error_archive, backupErrorMessage(ZipException("private entry")))
        assertEquals(R.string.local_backup_error_access, backupErrorMessage(SecurityException("content://private")))
        assertEquals(R.string.local_backup_error, backupErrorMessage(IllegalStateException("private content")))
    }
}
