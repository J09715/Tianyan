package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户气泡正文剥离历史附件挂载说明。
 *
 * 背景：≤0.18.17 把「附件：已复制并挂载到 Linux 沙箱」整段拼进消息正文并落库，
 * 升级后历史消息仍会显示一整段路径文字。渲染层统一剥离，新消息恒等变换。
 */
class AttachmentManifestStripTest {

    @Test
    fun `strips legacy attachment manifest appended to user text`() {
        val text = "看看这个\n\n[附件：已复制并挂载到 Linux 沙箱]\n" +
            "1. [文件] a.pdf (593 KB) -> Linux 沙箱绝对路径: /attachments/a.pdf\n" +
            "需要通过工具访问时，请使用 base 在上述绝对路径读取；不要使用 Android content:// URI 或宿主机路径。"
        assertEquals("看看这个", stripAttachmentManifest(text))
    }

    @Test
    fun `keeps plain user text untouched`() {
        assertEquals("看看这个", stripAttachmentManifest("看看这个"))
    }

    @Test
    fun `strips english header variant`() {
        assertEquals("look", stripAttachmentManifest("look\n\n[Attachment: mounted]"))
    }

    @Test
    fun `attachment only legacy message becomes blank`() {
        val text = "\n\n[附件：已复制并挂载到 Linux 沙箱]\n" +
            "1. [图片] a.jpg (10 KB) -> Linux 沙箱绝对路径: /attachments/a.jpg"
        assertEquals("", stripAttachmentManifest(text))
    }

    @Test
    fun `text mentioning attachment inline is not truncated`() {
        // 只有「空行 + [附件：」才算程序生成的段落，正文里顺口提到不剥离
        assertEquals("附件：我发你了", stripAttachmentManifest("附件：我发你了"))
    }
}
