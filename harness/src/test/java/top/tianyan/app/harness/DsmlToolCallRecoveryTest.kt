package top.tianyan.app.harness

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSML 文本工具调用恢复。
 *
 * 背景：部分 DeepSeek 系网关/中转把工具调用以标记文本塞进 content，
 * 而不是填结构化 tool_calls 字段。早先天衍的正则只认 `…tool_call>`（单数），
 * 匹配不到 DSML 的 `…tool_calls>`（复数，且中间隔着 `｜DSML｜`），
 * 于是本轮被判「零工具调用」→ 走完成分支 → 用户看到「无缘无故停了」。
 *
 * 本测试用线上抓样（含全角/半角两种竖线）钉住该路径，
 * 并覆盖「正文复述不算调用」的反向用例。
 */
class DsmlToolCallRecoveryTest {

    private val json = Json { isLenient = true }

    private fun calls(text: String) = TextToolCallCodec.normalize(json, text)

    /** 线上抓样：全角竖线 U+FF5C。 */
    private val fullwidthSample = """
        <｜DSML｜tool_calls>
          <｜DSML｜invoke name="edit_file">
            <｜DSML｜parameter name="file_path" string="true">lab.py</｜DSML｜parameter>
            <｜DSML｜parameter name="old_string" string="true">foo</｜DSML｜parameter>
            <｜DSML｜parameter name="new_string" string="true">bar</｜DSML｜parameter>
          </｜DSML｜invoke>
        </｜DSML｜tool_calls>
    """.trimIndent()

    /** 部分网关用半角竖线。 */
    private val halfwidthSample =
        """<|DSML|tool_calls><|DSML|invoke name="read"><|DSML|parameter name="path" string="true">a.kt</|DSML|parameter></|DSML|invoke></|DSML|tool_calls>"""

    @Test
    fun `fullwidth dsml sample yields one call with its parameters`() {
        val result = calls(fullwidthSample)
        assertEquals("应解出 1 个调用，实际=${result.calls}", 1, result.calls.size)
        val call = result.calls.single()
        assertEquals("edit_file", call.name)
        val args = json.parseToJsonElement(call.argumentsJson).jsonObject
        assertEquals("lab.py", args["file_path"]?.jsonPrimitive?.content)
        assertEquals("foo", args["old_string"]?.jsonPrimitive?.content)
        assertEquals("bar", args["new_string"]?.jsonPrimitive?.content)
        assertTrue("不应残留未解析标记", !result.hasUnresolvedMarkers)
    }

    @Test
    fun `halfwidth dsml sample is accepted too`() {
        val result = calls(halfwidthSample)
        assertEquals("半角竖线同样要认", 1, result.calls.size)
        assertEquals("read", result.calls.single().name)
    }

    @Test
    fun `dsml markers are stripped from display text`() {
        val result = calls("先看一下文件\n$fullwidthSample\n完成")
        assertTrue(
            "展示文本不应残留 DSML 标记，实际=${result.displayText}",
            !result.displayText.contains("DSML"),
        )
        assertTrue("应保留标记之外的正文", result.displayText.contains("先看一下文件"))
    }

    @Test
    fun `one dsml block may carry several invokes`() {
        val block = """
            <｜DSML｜tool_calls>
              <｜DSML｜invoke name="read">
                <｜DSML｜parameter name="path" string="true">a.kt</｜DSML｜parameter>
              </｜DSML｜invoke>
              <｜DSML｜invoke name="read">
                <｜DSML｜parameter name="path" string="true">b.kt</｜DSML｜parameter>
              </｜DSML｜invoke>
            </｜DSML｜tool_calls>
        """.trimIndent()
        val result = calls(block)
        assertEquals("同一块的多个 invoke 都要解出", 2, result.calls.size)
        assertEquals(listOf("read", "read"), result.calls.map { it.name })
        // 一段多调用不能把 invalidMarkerCount 算成负数
        assertTrue("invalidMarkerCount 不应为负", result.invalidMarkerCount >= 0)
        assertTrue(!result.hasUnresolvedMarkers)
    }

    /**
     * 反向用例：模型在正文里「讨论」该格式（贴文档、贴日志）时，
     * 没有完整的 invoke 闭合，不得被当成真实调用。
     */
    @Test
    fun `prose mentioning the format without a complete invoke is not a call`() {
        val prose = "我可以用 <｜DSML｜tool_calls> 这种格式来表达工具调用，但没有真的调用。"
        val result = calls(prose)
        assertTrue("复述格式不应产生调用，实际=${result.calls}", result.calls.isEmpty())
    }

    @Test
    fun `unclosed invoke inside a dsml block yields nothing`() {
        val broken = """
            <｜DSML｜tool_calls>
              <｜DSML｜invoke name="edit_file">
                <｜DSML｜parameter name="file_path" string="true">lab.py</｜DSML｜parameter>
        """.trimIndent()
        val result = calls(broken)
        assertTrue("未闭合的块不应产生调用，实际=${result.calls}", result.calls.isEmpty())
    }

    /**
     * string="true" 的值必须原样保留书写形式：
     * 否则形如 "1.0" 的版本号会被 JSON 推断成数字 1.0，回写文件时丢格式。
     */
    @Test
    fun `string typed parameter keeps its literal form`() {
        val block = """<｜DSML｜tool_calls><｜DSML｜invoke name="write"><｜DSML｜parameter name="content" string="true">1.0</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>"""
        val args = json.parseToJsonElement(calls(block).calls.single().argumentsJson).jsonObject
        assertEquals("1.0", args["content"]?.jsonPrimitive?.content)
    }

    /** 未被标为 string 的值仍按 JSON 字面量推断，保持与既有分支一致。 */
    @Test
    fun `untyped parameter still infers json literals`() {
        val block = """<｜DSML｜tool_calls><｜DSML｜invoke name="process"><｜DSML｜parameter name="tail_lines">120</｜DSML｜parameter></｜DSML｜invoke></｜DSML｜tool_calls>"""
        val args = json.parseToJsonElement(calls(block).calls.single().argumentsJson).jsonObject
        assertEquals("120", args["tail_lines"]?.jsonPrimitive?.content)
    }

    /** 既有协议形态不能因新增分支而回归。 */
    @Test
    fun `existing protocols still parse`() {
        val bracket = calls("""[[tool_call]]{"name":"read","arguments":{"path":"x"}}[[/tool_call]]""")
        assertEquals("read", bracket.calls.single().name)

        val xml = calls("""<tool_call>{"name":"write","arguments":{"path":"y"}}</tool_call>""")
        assertEquals("write", xml.calls.single().name)
    }

    /** DSML 与既有形态混排时都要认出来。 */
    @Test
    fun `dsml coexists with other protocols`() {
        val mixed = """<tool_call>{"name":"read","arguments":{"path":"a"}}</tool_call>
$halfwidthSample"""
        val result = calls(mixed)
        assertEquals("两种形态都要解出", 2, result.calls.size)
        assertEquals(listOf("read", "read"), result.calls.map { it.name })
    }
}
