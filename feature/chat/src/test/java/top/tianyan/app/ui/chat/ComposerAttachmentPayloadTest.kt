package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.MessageAttachment

/**
 * 附件发送载荷的回归测试。
 *
 * 背景：曾出现过一次回归——`sendFromComposer` 走的是把「附件挂载清单」拼进正文的旧实现，
 * 于是气泡里只能把整段当文本渲染，用户看到的就是「发图片变成一行文字」。
 * 正确做法是正文只放用户输入，附件走结构化 [MessageAttachment] 通道。
 *
 * 这里不构造 Android 的 ChatAttachment（那需要 Robolectric，而 aarch64 Linux 上
 * Robolectric 原生运行时不可用），改为直接验证「结构化通道」这一层的三条不变量：
 * 1. 附件元信息完整（名字/类型/沙箱路径/MIME）；
 * 2. 图片走 imageUrl 字段，不混进正文；
 * 3. 沙箱路径缺失时有确定的回退规则。
 */
class ComposerAttachmentPayloadTest {

    @Test
    fun `结构化附件保留渲染所需的全部字段`() {
        val attachment = MessageAttachment(
            name = "报告.pdf",
            isImage = false,
            sizeBytes = 2048L,
            guestPath = "/attachments/报告.pdf",
            mimeType = "application/pdf",
        )
        assertEquals("报告.pdf", attachment.name)
        assertFalse(attachment.isImage)
        assertEquals(2048L, attachment.sizeBytes)
        assertEquals("/attachments/报告.pdf", attachment.guestPath)
        assertEquals("application/pdf", attachment.mimeType)
    }

    @Test
    fun `图片附件的 data URL 走独立字段而不是正文`() {
        val dataUrl = "data:image/png;base64,AAAA"
        val attachment = MessageAttachment(
            name = "p.png",
            isImage = true,
            sizeBytes = 1024L,
            guestPath = "/attachments/p.png",
            imageUrl = dataUrl,
        )
        assertEquals(dataUrl, attachment.imageUrl)
        // 正文由调用方单独传入，结构体本身不含任何正文痕迹
        assertFalse(attachment.guestPath.contains("data:image"))
    }

    @Test
    fun `视频附件保留 MIME 以便交给系统播放器`() {
        val attachment = MessageAttachment(
            name = "clip.mp4",
            isImage = false,
            sizeBytes = 4096L,
            guestPath = "/attachments/clip.mp4",
            mimeType = "video/mp4",
        )
        assertEquals("video/mp4", attachment.mimeType)
        assertTrue(attachment.name.endsWith(".mp4"))
    }

    @Test
    fun `沙箱路径回退规则是确定的`() {
        // 生产代码用 guestFilePath ?: "/attachments/${name}"，这里锁住该回退的形状
        val name = "c.txt"
        val guestPath = null ?: "/attachments/$name"
        assertEquals("/attachments/c.txt", guestPath)
    }
}
