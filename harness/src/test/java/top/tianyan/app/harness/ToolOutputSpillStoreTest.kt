package top.tianyan.app.harness

import java.io.File
import java.util.Calendar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 工具输出落盘引流：命名白名单化、过期判定按文件名内嵌时间戳（跨时区/备份可靠）、
 * 写入失败静默降级为 null、GC 只清过期文件。
 */
class ToolOutputSpillStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun now(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(2026, Calendar.SEPTEMBER, 19, 14, 25, 30)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    @Test
    fun `fileName is round-trippable through isExpired`() {
        val now = now()
        val name = ToolOutputSpillStore.fileName("grep", now, random = "abcd")

        assertTrue(name.startsWith("tool-2026"))
        assertTrue(name.endsWith("-grep-abcd.txt"))
        assertFalse(ToolOutputSpillStore.isExpired(name, now))
        assertFalse(ToolOutputSpillStore.isExpired(name, now + ToolOutputSpillStore.RETENTION_MS))
        assertTrue(ToolOutputSpillStore.isExpired(name, now + ToolOutputSpillStore.RETENTION_MS + 1))
    }

    @Test
    fun `fileName sanitizes hostile tool names`() {
        val name = ToolOutputSpillStore.fileName("../../evil name?*|", now(), random = "abcd")

        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertFalse(name.contains('?'))
        assertFalse(name.contains(' '))
        assertTrue(name.endsWith(".txt"))
        // 时间戳段不受污染：仍可解析回内嵌时间
        assertTrue(ToolOutputSpillStore.isExpired(name, now() + ToolOutputSpillStore.RETENTION_MS + 1))
    }

    @Test
    fun `isExpired is conservative for foreign names`() {
        val now = now()
        // 非本目录命名习惯 / 无法解析时间戳的名字：宁可晚删不可误删
        assertFalse(ToolOutputSpillStore.isExpired("notes.md", now))
        assertFalse(ToolOutputSpillStore.isExpired("tool-badname.txt", now))
        assertFalse(ToolOutputSpillStore.isExpired("tool-20200101-grep.txt", now))
        assertTrue(ToolOutputSpillStore.isExpired("tool-20200101-000000-grep-abcd.txt", now))
    }

    @Test
    fun `spill writes full content and returns workspace relative path`() = runBlocking {
        val root = tmp.newFolder("ws")
        val access = WorkspaceFileAccess(root)
        val content = buildString {
            repeat(500) { appendLine("line $it: ${"x".repeat(100)}") }
        }

        val path = ToolOutputSpillStore.spill(access, "grep", content)

        assertTrue(path!!.startsWith("${ToolOutputSpillStore.DIR}/tool-"))
        assertEquals(content, File(root, path).readText())
    }

    @Test
    fun `cleanup removes only expired spills`() = runBlocking {
        val root = tmp.newFolder("ws")
        val access = WorkspaceFileAccess(root)
        val dir = File(root, ToolOutputSpillStore.DIR)
        dir.mkdirs()
        val now = now()
        val oldName = ToolOutputSpillStore.fileName("grep", now - ToolOutputSpillStore.RETENTION_MS - 10_000, random = "aaaa")
        val freshName = ToolOutputSpillStore.fileName("grep", now, random = "bbbb")
        File(dir, oldName).writeText("old")
        File(dir, freshName).writeText("new")

        ToolOutputSpillStore.cleanup(access, now)

        assertFalse("过期文件应被清理", File(dir, oldName).exists())
        assertTrue("未过期文件必须保留", File(dir, freshName).exists())
    }

    @Test
    fun `spill supports multi megabyte output and caps internal artifacts`() = runBlocking {
        val root = tmp.newFolder("ws")
        val access = WorkspaceFileAccess(root)

        assertNull(ToolOutputSpillStore.spill(access, "grep", ""))
        val large = "x".repeat(1_100_000)
        val path = ToolOutputSpillStore.spill(access, "grep", large)
        assertTrue(path != null)
        assertEquals(large.length.toLong(), File(root, path!!).length())
        // 超过内部产物 16 MiB 配额才静默降级，不允许无限吃磁盘
        assertNull(ToolOutputSpillStore.spill(access, "grep", "x".repeat(17 * 1024 * 1024)))
    }

    @Test
    fun `cleanup removes abandoned temporary artifacts`() = runBlocking {
        val root = tmp.newFolder("ws")
        val access = WorkspaceFileAccess(root)
        val dir = File(root, ToolOutputSpillStore.DIR).also { it.mkdirs() }
        val temporary = File(dir, ".tool-output.txt.tmp-123").also { it.writeText("partial") }

        ToolOutputSpillStore.cleanup(access, now())

        assertFalse(temporary.exists())
    }
}
