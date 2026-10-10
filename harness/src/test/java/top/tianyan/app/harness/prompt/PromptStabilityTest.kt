package top.tianyan.app.harness.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词分层守卫。
 *
 * 这组断言守的是「前缀缓存不会被逐轮内容污染」这件事本身，
 * 而不是某个具体实现细节。背景与代价计算见 [PromptParts] 的注释。
 *
 * 为什么用源码断言而不是只跑行为：
 * `SystemPromptBuilder` 依赖 `android.content.Context`，本机 aarch64 上
 * Robolectric 不可用，纯行为测试在本地跑不起来。而这条界线一旦被越过
 * （有人把逐轮变化的块放回 frozen），表现是**静默的**缓存失效 ——
 * 不会报错，只会每轮多花钱。所以这里用结构断言把它钉死。
 */
class PromptStabilityTest {

    private val builderSource = File(
        "src/main/java/top/tianyan/app/harness/prompt/SystemPromptBuilder.kt",
    )

    private val assemblerSource = File(
        "src/main/java/top/tianyan/app/harness/session/ApiContextAssembler.kt",
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
        assertTrue("找不到 ApiContextAssembler.kt：${assemblerSource.absolutePath}", assemblerSource.isFile)
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

    /** 组装器必须真正使用拆分结果：system 用 frozen，尾部用 dynamic。 */
    @Test
    fun assemblerKeepsSystemPromptFrozenAndAppendsTail() {
        val text = assemblerSource.readText()
        assertTrue(
            "组装器必须用 buildParts(...).frozenText() 作为 system prompt",
            text.contains("frozenText()"),
        )
        assertTrue(
            "组装器必须把 dynamicText() 作为尾部内容注入",
            text.contains("dynamicText()"),
        )
        // 旧的「一次性 build 出整段 system prompt」写法必须消失，
        // 否则动态块会重新混进位置 0。
        assertFalse(
            "组装器不应再调用会被塞进 system 的整体 build()",
            Regex("""systemPromptBuilder\.build\(""").containsMatchIn(text),
        )
    }

    /** 尾部注入的具体位置：挂在最后一条 user 消息之后，而不是新建 system 消息。 */
    @Test
    fun dynamicTailIsAppendedToTheLastUserMessage() {
        val text = assemblerSource.readText()
        val tailIndex = text.indexOf("dynamicTail.isNotEmpty()")
        assertTrue("组装器里找不到 dynamicTail 的注入点", tailIndex >= 0)
        val context = text.substring(tailIndex, minOf(text.length, tailIndex + 900))
        assertTrue(
            "尾部必须挂到最后一条 user 消息上（只追加尾部才不破坏前缀缓存）",
            context.contains("""indexOfLast { it.role == "user" }"""),
        )
        assertFalse(
            "尾部不得作为新的 system 消息插入——那会改变位置 0 之后的字节",
            context.contains("""role = "system""""),
        )
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
