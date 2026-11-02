package top.tianyan.app.core.common.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * SafeFileTree 行为测试（批次 E）：复制/删除永不跟随符号链接，
 * 树内的符号链接按链接本身复制，目标不会被链接拖出根目录。
 */
class SafeFileTreeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copy replicates regular files and nested directories`() {
        val source = tmp.newFolder("src")
        File(source, "a.txt").writeText("hello")
        File(source, "sub").mkdirs()
        File(source, "sub/b.txt").writeText("world")

        val target = File(tmp.root, "dst")
        SafeFileTree.copy(source, target)

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "sub/b.txt").readText())
    }

    @Test
    fun `symlink inside tree is copied as link not as target content`() {
        val source = tmp.newFolder("src2")
        val outside = File(tmp.root, "outside.txt").apply { writeText("secret") }
        val link = File(source, "leak")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        File(source, "keep.txt").writeText("ok")

        val target = File(tmp.root, "dst2")
        SafeFileTree.copy(source, target)

        val copiedLink = File(target, "leak")
        assertTrue(Files.isSymbolicLink(copiedLink.toPath()))
        // 链接仍指向原目标（按链接复制），但复制过程本身没有把外部内容物化进树。
        assertEquals("secret", copiedLink.readText())
        assertEquals("ok", File(target, "keep.txt").readText())
    }

    @Test
    fun `delete removes tree without following links`() {
        val source = tmp.newFolder("src3")
        File(source, "x.txt").writeText("data")
        val outside = File(tmp.root, "outside3.txt").apply { writeText("keep-me") }
        Files.createSymbolicLink(File(source, "l").toPath(), outside.toPath())

        SafeFileTree.delete(source)

        assertFalse(source.exists())
        // 外部目标必须完好：删除树不能顺着链接把外部文件删掉。
        assertTrue(outside.exists())
        assertEquals("keep-me", outside.readText())
    }
}
