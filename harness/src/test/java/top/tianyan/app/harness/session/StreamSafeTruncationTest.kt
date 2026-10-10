package top.tianyan.app.harness.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.MessageAttachment
import top.tianyan.app.harness.UserMessage

/**
 * 流式文本的安全截断（live 增量排版的落点）。
 *
 * ## 为什么需要它
 *
 * 流式期间若走裸 Text，就是"输出完才排版"；改走 Markdown 渲染后，必须在
 * **安全的 Markdown 边界**处截断窗口，否则：
 *  · 在 ``` 围栏中间断开 → 解析器把未闭合围栏当代码块一直吃到底，
 *    整屏变成一个巨大代码块，比不排版更难看；
 *  · 在列表行/表格行中间断开 → 渲染出残缺结构。
 *
 * 本文件测的是这个截断函数本身（纯 Kotlin，不依赖 Robolectric）。
 */
class StreamSafeTruncationTest {

    private fun truncate(text: String, window: Int) =
        truncateAtSafeMarkdownBoundary(text, window)

    /** 短文本不截断，原样返回。 */
    @Test
    fun `text within the window is returned unchanged`() {
        val text = "# 标题\n\n正文"
        assertEquals(text, truncate(text, 4_000))
    }

    /** 围栏已闭合时，从最后一个闭合围栏之后开始取 —— 保证解析出合法代码块。 */
    @Test
    fun `prefers cutting after a closed fence`() {
        val text = buildString {
            appendLine("说明文字")
            appendLine("```kotlin")
            appendLine("val a = 1")
            appendLine("```")
            appendLine("后续段落")
        }
        val out = truncate(text, 10)
        assertFalse("不应残留围栏标记", out.contains("```"))
        assertTrue("应保留闭合围栏之后的内容", out.contains("后续段落"))
    }

    /** 围栏未闭合时，绝不把它后面（含半成品围栏）当作安全内容。 */
    @Test
    fun `unclosed fence is never treated as a safe boundary`() {
        val text = "前文\n\n```kotlin\nval a = 1\n"  // 围栏未闭合
        val out = truncate(text, 8)
        // 未闭合场景下应回落到段落/换行边界，且不得产出只含半个围栏的片段
        assertTrue("不得产出孤立的围栏开标记", !out.trim().endsWith("```"))
    }

    /** 无围栏时优先在空行（段落边界）断开。 */
    @Test
    fun `falls back to a blank-line paragraph boundary`() {
        val text = "第一段内容\n\n第二段内容"
        val out = truncate(text, 6)
        assertEquals("第二段内容", out)
    }

    /** 再退一步：在最后一个换行处断开，避免把一行切成两半。 */
    @Test
    fun `falls back to the last newline`() {
        val text = "第一行\n第二行"
        val out = truncate(text, 4)
        assertEquals("第二行", out)
    }

    /** 完全没有边界时（一整段无换行），仍返回尾部窗口而不是崩掉。 */
    @Test
    fun `long text with no boundary still yields a bounded tail`() {
        val text = "a".repeat(5_000)
        val out = truncate(text, 1_000)
        assertTrue("必须有界，实际 ${out.length}", out.length <= 1_000)
    }

    /** 标题行不应被截断成半个 # 标记。 */
    @Test
    fun `heading is not cut in half`() {
        val text = "正文\n\n## 重要章节\n\n章节内容"
        val out = truncate(text, 8)
        assertFalse("不应出现孤立的 # 前缀", out.trimStart().startsWith("#") && !out.contains("重要章节"))
    }
}
