package top.tianyan.app.harness.acp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TianyanSlashTranslationTest {

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("帮我写个函数", translateTianyanSlash("帮我写个函数"))
        assertEquals("hello world", translateTianyanSlash("hello world"))
    }

    @Test
    fun `run command translates to natural language task`() {
        assertEquals("运行：python main.py", translateTianyanSlash("/run python main.py"))
        assertEquals("运行当前工作区的入口代码", translateTianyanSlash("/run"))
    }

    @Test
    fun `install command translates`() {
        assertEquals("安装依赖：flask", translateTianyanSlash("/install flask"))
    }

    @Test
    fun `git command translates`() {
        assertEquals("执行 git 操作：status", translateTianyanSlash("/git status"))
        assertEquals("查看 git 状态", translateTianyanSlash("/git"))
    }

    @Test
    fun `test command translates`() {
        assertEquals("运行当前项目的测试", translateTianyanSlash("/test"))
    }

    @Test
    fun `unknown slash command passes through`() {
        assertEquals("/foo bar", translateTianyanSlash("/foo bar"))
    }

    @Test
    fun `help and clear pass through`() {
        assertEquals("/help", translateTianyanSlash("/help"))
        assertEquals("/clear", translateTianyanSlash("/clear"))
    }
}
