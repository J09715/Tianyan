package top.tianyan.app.harness.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词分层守卫：逐轮变化的内容不得留在可缓存前缀里。
 *
 * ## 边界说明（重要）
 *
 * 这里只做**分节归属**的源码断言，它管的是「recallSection 有没有被放进
 * dynamic 组」这一件事。
 *
 * 它**不能**证明请求字节真的稳定 —— 那由
 * [top.tianyan.app.harness.session.RequestPrefixInvariantTest] 负责，
 * 后者直接对 `HarnessApiMapper` 的真实渲染结果做断言。
 *
 * 保留源码断言的唯一理由：`SystemPromptBuilder` 依赖
 * `android.content.Context`，本机 aarch64 上 Robolectric 不可用，
 * 纯行为测试在本地跑不起来；而把逐轮块放回 frozen 的表现是**静默的**
 * 缓存失效（不报错，只是每轮多花钱）。两道守卫互补，缺一不可。
 */
class PromptStabilityTest {

    private val builderSource = File(
        "src/main/java/top/tianyan/app/harness/prompt/SystemPromptBuilder.kt",
    )

    /** 依赖当轮输入、必须在 frozen 之外的四块。 */
    private val perTurnBlocks = listOf(
        "routedBlocks" to "依赖当轮用户消息里的信号词",
        "skillSection" to "依赖当轮 @ 提及",
        "recallSection" to "按当轮用户消息检索记忆",
        "planSection" to "计划每推进一步就变",
    )

    @Test
    fun sourcesAreReachable() {
        assertTrue("找不到 SystemPromptBuilder.kt：${builderSource.absolutePath}", builderSource.isFile)
    }

    /** 四块逐轮变化的内容必须落在 dynamic 组，不能留在 frozen。 */
    @Test
    fun perTurnBlocksLiveInTheDynamicGroup() {
        val text = builderSource.readText()
        val frozenGroup = extractGroup(text, "frozen")
        val dynamicGroup = extractGroup(text, "dynamic")

        perTurnBlocks.forEach { (name, why) ->
            assertTrue(
                "$name（$why）必须出现在 dynamic 组里，否则它会污染可缓存前缀",
                dynamicGroup.contains(name),
            )
            assertFalse(
                "$name（$why）不得出现在 frozen 组里——" +
                    "它逐轮变化，放进前缀会让其后整段对话历史每轮重新 prefill",
                frozenGroup.contains(name),
            )
        }
    }

    /** 会话常量必须留在 frozen，移出去会白白失去缓存收益。 */
    @Test
    fun sessionConstantsStayInTheFrozenGroup() {
        val text = builderSource.readText()
        val frozenGroup = extractGroup(text, "frozen")
        listOf("basePrompt", "toolsSection", "prootSection", "pinnedSection").forEach { name ->
            assertTrue(
                "$name 是会话常量，应留在 frozen 组以命中前缀缓存",
                frozenGroup.contains(name),
            )
        }
    }

    /**
     * 提取 `frozen = listOf(...)` / `dynamic = listOf(...)` 的块文本。
     * 取到配对的右括号为止，避免把整文件当成一组。
     */
    private fun extractGroup(text: String, name: String): String {
        val marker = "$name = listOf("
        val start = text.indexOf(marker)
        if (start < 0) return ""
        var depth = 0
        var i = text.indexOf('(', start)
        val begin = i
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(begin, i + 1)
                }
            }
            i++
        }
        return text.substring(begin)
    }
}
