package top.tianyan.app.runtime.uml

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Ext4InlineWriter 验证：往真 ext4 镜像里写 inline-data 文件。
 *
 * 背景（真内核实测出来的故障）：`init=/host/tianyan-uml-init` 会在引导时执行，
 * 但 /host 由 init 自己挂载 —— 鸡生蛋，内核 panic（error -2）。
 * 修法是把 init 放进镜像内，而 Android 无 root/debugfs，所以需要这个纯 Kotlin 写入器。
 *
 * 这里用一个真 ext4 镜像（ TemporaryFolder 现造）验证「写进去 → debugfs 读得回来」。
 * 读回用 debugfs（仅测试环境有），生产环境不需要它。
 */
class Ext4InlineWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个最小的真 ext4 镜像。 */
    private fun makeImage(): File {
        val img = tmp.newFile("test.img")
        val size = 4L * 1024 * 1024
        RandomAccessFile(img, "rw").use { it.setLength(size) }
        // 关掉 metadata_csum：真 guest 镜像（Stryker rootfs.imgz）没开它，
        // 开了会让目录块校验和必须重算，而我们的写入器只处理无 csum 的情况。
        val pb = ProcessBuilder("mkfs.ext4", "-q", "-F", "-O", "^metadata_csum", img.absolutePath)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.readBytes()
        assertEquals("mkfs.ext4 failed", 0, proc.waitFor())
        return img
    }

    private fun debugfsRead(img: File, path: String): String {
        val pb = ProcessBuilder("debugfs", "-R", "cat $path", img.absolutePath)
        pb.redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.readBytes().toString(Charsets.UTF_8)
        proc.waitFor()
        // debugfs 会在正文前打印版本横幅（"debugfs 1.47.4 ..."），过滤掉
        return out.substringAfter("\n", out).trim()
    }

    @Test
    fun writesInlineEntryReadableByExt4Tools() {
        val img = makeImage()
        val content = "#!/bin/sh\necho TIANYAN_INLINE_OK\n".toByteArray()

        val result = Ext4InlineWriter.writeRootEntry(img, "tianyan-uml-init", content)
        assertTrue("write failed: $result", result is Ext4InlineWriter.Result.Ok)

        // debugfs 能读到 = 文件系统认这条 inline 记录（extent/日志/校验和都自洽）
        val back = debugfsRead(img, "/tianyan-uml-init")
        assertEquals(
            "ext4 must read back the inline content",
            String(content, Charsets.UTF_8).trim(),
            back.trim(),
        )
    }

    @Test
    fun isIdempotentForSameContent() {
        val img = makeImage()
        val content = "#!/bin/sh\necho IDEMPOTENT\n".toByteArray()
        val first = Ext4InlineWriter.writeRootEntry(img, "tianyan-uml-init", content)
        val second = Ext4InlineWriter.writeRootEntry(img, "tianyan-uml-init", content)
        assertTrue(first is Ext4InlineWriter.Result.Ok)
        assertTrue("second write must be idempotent: $second", second is Ext4InlineWriter.Result.Ok)
        // 幂等的可观测判据：内容一致时两次读回必须相同（inode 复用是优化，不是契约）
        assertEquals(
            String(content, Charsets.UTF_8).trim(),
            debugfsRead(img, "/tianyan-uml-init"),
        )
    }

    @Test
    fun rejectsContentLargerThanInlineArea() {
        val img = makeImage()
        // 256 字节 inode 的 inline 区远小于 100KB
        val big = ByteArray(100 * 1024) { 1 }
        val result = Ext4InlineWriter.writeRootEntry(img, "big", big)
        assertTrue("oversize must be rejected: $result", result is Ext4InlineWriter.Result.TooLarge)
    }

    @Test
    fun writesMultipleDistinctEntries() {
        val img = makeImage()
        val a = "#!/bin/sh\necho A\n".toByteArray()
        val b = "#!/bin/sh\necho B\n".toByteArray()
        assertTrue(Ext4InlineWriter.writeRootEntry(img, "a.sh", a) is Ext4InlineWriter.Result.Ok)
        assertTrue(Ext4InlineWriter.writeRootEntry(img, "b.sh", b) is Ext4InlineWriter.Result.Ok)
        assertEquals(String(a, Charsets.UTF_8).trim(), debugfsRead(img, "/a.sh"))
        assertEquals(String(b, Charsets.UTF_8).trim(), debugfsRead(img, "/b.sh"))
    }
}
