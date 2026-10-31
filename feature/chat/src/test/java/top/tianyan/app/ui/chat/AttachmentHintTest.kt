package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件超限提示的行为单测（审查意见：以行为覆盖替代常量钉死）。
 * 全部纯 JVM 可跑：只依赖 AttachmentHelper 的纯函数，不碰 Android Context/Robolectric。
 */
class AttachmentHintTest {

    private val mb = 1024L * 1024L
    private val limit200Mb = 200L * mb

    // ---- 预检判定：exceedsLimit ----

    @Test
    fun `exactly 200MB is allowed`() {
        assertFalse(AttachmentHelper.exceedsLimit(limit200Mb, limit200Mb))
    }

    @Test
    fun `one byte over 200MB is rejected`() {
        assertTrue(AttachmentHelper.exceedsLimit(limit200Mb + 1, limit200Mb))
    }

    @Test
    fun `small files are allowed`() {
        assertFalse(AttachmentHelper.exceedsLimit(50L * mb, limit200Mb))
    }

    @Test
    fun `unknown size zero is not rejected by pre-check`() {
        // cursor 拿不到大小时 size=0，交给流式拷贝兜底，而不是误判超限
        assertFalse(AttachmentHelper.exceedsLimit(0L, limit200Mb))
    }

    // ---- 提示聚合：buildAttachmentNotice ----

    @Test
    fun `too large only yields message with limit label`() {
        val notice = AttachmentHelper.buildAttachmentNotice(
            tooLargeCount = 1,
            failedCount = 0,
            limitLabel = "200.0 MB",
            tooLargeMessage = { count, label -> "$count 个文件超过大小上限（最大 $label），未添加" },
            failedMessage = { count -> "$count 个附件处理失败，请重试" },
        )
        assertEquals("1 个文件超过大小上限（最大 200.0 MB），未添加", notice)
    }

    @Test
    fun `failed only yields failure message`() {
        val notice = AttachmentHelper.buildAttachmentNotice(
            tooLargeCount = 0,
            failedCount = 2,
            limitLabel = "200.0 MB",
            tooLargeMessage = { count, label -> "$count files over $label" },
            failedMessage = { count -> "$count failed" },
        )
        assertEquals("2 failed", notice)
    }

    @Test
    fun `too large and failed together are joined with semicolon`() {
        val notice = AttachmentHelper.buildAttachmentNotice(
            tooLargeCount = 2,
            failedCount = 3,
            limitLabel = "200.0 MB",
            tooLargeMessage = { count, label -> "$count 个文件超过大小上限（最大 $label）" },
            failedMessage = { count -> "$count 个处理失败" },
        )
        assertEquals("2 个文件超过大小上限（最大 200.0 MB）；3 个处理失败", notice)
    }

    @Test
    fun `all success yields null notice`() {
        val notice = AttachmentHelper.buildAttachmentNotice(
            tooLargeCount = 0,
            failedCount = 0,
            limitLabel = "200.0 MB",
            tooLargeMessage = { _, _ -> "should not be called" },
            failedMessage = { _ -> "should not be called" },
        )
        assertNull(notice)
    }

    // ---- 上限标签格式：formatFileSize ----

    @Test
    fun `limit label renders 200MB as MB with one decimal`() {
        assertEquals("200.0 MB", AttachmentHelper.formatFileSize(limit200Mb))
    }

    @Test
    fun `tiny limit renders as bytes`() {
        assertEquals("512 B", AttachmentHelper.formatFileSize(512))
    }
}
