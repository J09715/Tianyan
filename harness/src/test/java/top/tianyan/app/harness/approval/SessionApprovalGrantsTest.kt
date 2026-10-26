package top.tianyan.app.harness.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话内审批授权表测试：类别键归一化、前缀/目录覆盖语义、容量上限与会话清理。
 * 安全不变量：无永久授权（纯内存）、类别粒度不可一揽子放行。
 */
class SessionApprovalGrantsTest {

    @Test
    fun `command grants cover same prefix and reject other commands`() {
        val key = SessionApprovalGrants.grantKey(
            "base",
            """{"command": "git push origin main", "timeout": 30}""",
        )
        assertEquals("cmd:git push", key)

        val grants = SessionApprovalGrants()
        grants.grant("s1", "base", """{"command": "git push origin main"}""")
        // 同前缀（更长参数）放行
        assertTrue(grants.isGranted("s1", "base", """{"command": "git push origin dev"}"""))
        // 精确同命令放行
        assertTrue(grants.isGranted("s1", "base", """{"command": "git push origin main"}"""))
        // 不同子命令不放行
        assertFalse(grants.isGranted("s1", "base", """{"command": "git status"}"""))
        // 不同会话不共享
        assertFalse(grants.isGranted("s2", "base", """{"command": "git push origin main"}"""))
    }

    @Test
    fun `env assignment prefix is stripped from command prefix`() {
        assertEquals("cmd:git push", SessionApprovalGrants.grantKey("base", """{"command": "FOO=1 BAR=2 git push origin"}"""))
    }

    @Test
    fun `path scoped tools grant by parent directory`() {
        val key = SessionApprovalGrants.grantKey("write", """{"path": "/sdcard/Download/report.png"}""")
        assertEquals("dir:/sdcard/Download", key)

        val grants = SessionApprovalGrants()
        grants.grant("s1", "write", """{"path": "/sdcard/Download/report.png"}""")
        // 同目录其他文件放行
        assertTrue(grants.isGranted("s1", "write", """{"path": "/sdcard/Download/other.png"}"""))
        // 子目录按层级放行
        assertTrue(grants.isGranted("s1", "write", """{"path": "/sdcard/Download/sub/a.txt"}"""))
        // 兄弟目录不放行
        assertFalse(grants.isGranted("s1", "write", """{"path": "/sdcard/Movies/a.mp4"}"""))
    }

    @Test
    fun `mcp tools grant per server and tool`() {
        assertEquals("mcp:browser:navigate", SessionApprovalGrants.grantKey("mcp__browser__navigate", """{"url": "https://a.com"}"""))
        val grants = SessionApprovalGrants()
        grants.grant("s1", "mcp__browser__navigate", """{"url": "https://a.com"}""")
        assertTrue(grants.isGranted("s1", "mcp__browser__navigate", """{"url": "https://b.com"}"""))
        assertFalse(grants.isGranted("s1", "mcp__browser__click", """{"x": 1}"""))
        assertFalse(grants.isGranted("s1", "mcp__websearch__search", """{"q": "x"}"""))
    }

    @Test
    fun `uncategorizable tools fall back to exact args hash`() {
        val args = """{"action": "screenshot"}"""
        val key = SessionApprovalGrants.grantKey("host", args)
        assertTrue(key!!.startsWith("exact:"))
        val grants = SessionApprovalGrants()
        grants.grant("s1", "host", args)
        assertTrue(grants.isGranted("s1", "host", args))
        assertFalse(grants.isGranted("s1", "host", """{"action": "other"}"""))
    }

    @Test
    fun `unparseable arguments never grant and uncategorizable args fall back to exact`() {
        assertNull(SessionApprovalGrants.grantKey("base", "not json"))
        // write 无路径参数无法归类为目录键 → 回退到精确参数键（只匹配完全相同的参数，安全）
        val key = SessionApprovalGrants.grantKey("write", """{"content": "没有路径"}""")
        assertTrue(key!!.startsWith("exact:"))
    }

    @Test
    fun `absolute filesystem root path must not collide with workspace root dir key`() {
        // 批准下载到 /sdcard 的授权键是 "dir:/"（文件系统根），不得与工作区根 "dir:." 碰撞，
        // 否则批准一次绝对根路径下载会放行工作区根级文件的写入。
        assertEquals("dir:/", SessionApprovalGrants.grantKey("download", """{"destination": "/sdcard"}"""))
        assertEquals("dir:.", SessionApprovalGrants.grantKey("write", """{"path": "report.md"}"""))

        val grants = SessionApprovalGrants()
        grants.grant("s1", "download", """{"destination": "/sdcard"}""")
        // 工作区根级相对路径不被连带放行
        assertFalse(grants.isGranted("s1", "write", """{"path": "report.md"}"""))
        // 同一绝对根目录下的其他绝对路径放行
        assertTrue(grants.isGranted("s1", "download", """{"destination": "/sdcard/other.bin"}"""))
    }

    @Test
    fun `grant count per session is bounded`() {
        val grants = SessionApprovalGrants()
        (1..80).forEach { index ->
            grants.grant("s1", "base", """{"command": "cmd$index one two three four"}""")
        }
        // 上限 64：最早写入的 cmd1 应被淘汰
        assertFalse(grants.isGranted("s1", "base", """{"command": "cmd1 one two three four"}"""))
        assertTrue(grants.isGranted("s1", "base", """{"command": "cmd80 one two three four"}"""))
    }

    @Test
    fun `revoke session clears all grants`() {
        val grants = SessionApprovalGrants()
        grants.grant("s1", "base", """{"command": "git push origin main"}""")
        grants.revokeSession("s1")
        assertFalse(grants.isGranted("s1", "base", """{"command": "git push origin main"}"""))
    }
}
