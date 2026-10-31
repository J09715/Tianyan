package top.tianyan.app.harness.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.database.AgentMemoryEntity

/**
 * BM25 + CJK bigram 召回选择测试：泛化轮次抑制、bigram 词重叠、
 * project 加权、新鲜度降权与条数预算。
 */
class MemoryRecallSelectorTest {

    private fun memory(
        id: String,
        key: String,
        value: String,
        scope: String = "project",
        updatedAt: Long = System.currentTimeMillis(),
    ) = AgentMemoryEntity(id = id, scope = scope, kind = "fact", key = key, value = value, updatedAt = updatedAt)

    private val now = System.currentTimeMillis()

    // ---------- 泛化轮次抑制 ----------

    @Test
    fun `generic turns do not trigger recall`() {
        val candidates = listOf(memory("m1", "build.tool", "使用 gradle 构建"))
        assertTrue(MemoryRecallSelector.selectRecall("继续", candidates, now).isEmpty())
        assertTrue(MemoryRecallSelector.selectRecall("好的", candidates, now).isEmpty())
        assertTrue(MemoryRecallSelector.selectRecall("continue", candidates, now).isEmpty())
        assertTrue(MemoryRecallSelector.selectRecall("ok", candidates, now).isEmpty())
    }

    @Test
    fun `generic phrase inside informative sentence still recalls`() {
        val candidates = listOf(memory("m1", "build.tool", "本项目使用 gradle 构建与 kotlinter 检查"))
        val selected = MemoryRecallSelector.selectRecall("继续帮我改 gradle 配置", candidates, now)
        assertEquals(listOf("m1"), selected.map { it.id })
    }

    @Test
    fun `no candidate matches query returns empty`() {
        val candidates = listOf(memory("m1", "build.tool", "使用 gradle 构建"))
        assertTrue(MemoryRecallSelector.selectRecall("讲讲 coroutines flow 的用法", candidates, now).isEmpty())
    }

    // ---------- bigram 词重叠 ----------

    @Test
    fun `cjk bigram matches real word overlap`() {
        val candidates = listOf(memory("m1", "code.navigation", "项目重构时优先用 codegraph 查调用链路"))
        val selected = MemoryRecallSelector.selectRecall("帮我梳理调用链", candidates, now)
        // 查询 "调用链" 与文档 "调用链路" 共享 bigram「调用」「用链」
        assertEquals(listOf("m1"), selected.map { it.id })
    }

    @Test
    fun `latin words match case-insensitively`() {
        val candidates = listOf(memory("m1", "pref.style", "回复里倾向使用 Kotlin Coroutines 而不是 RxJava"))
        val selected = MemoryRecallSelector.selectRecall("讲讲 COROUTINES 的用法", candidates, now)
        assertEquals(listOf("m1"), selected.map { it.id })
    }

    // ---------- 排序：scope 加权与新鲜度 ----------

    @Test
    fun `project scope outranks global on equal match`() {
        val global = memory("global", "build.tool", "构建使用 gradle wrapper", scope = "global")
        val project = memory("project", "build.tool", "构建使用 gradle wrapper", scope = "project")
        val selected = MemoryRecallSelector.selectRecall("gradle 构建怎么配置", listOf(global, project), now)
        assertEquals("project", selected.first().id)
    }

    @Test
    fun `stale memories are down-ranked not removed`() {
        val fresh = memory("fresh", "db.migration", "room 迁移测试要用 Robolectric", updatedAt = now - 5L * 86_400_000)
        val stale = memory("stale", "db.migration", "room 迁移测试要用 Robolectric", updatedAt = now - 400L * 86_400_000)
        val selected = MemoryRecallSelector.selectRecall("room 迁移", listOf(stale, fresh), now)
        assertEquals(listOf("fresh", "stale"), selected.map { it.id })
    }

    // ---------- 预算 ----------

    @Test
    fun `recall is capped at max facts`() {
        val candidates = (1..9).map { index -> memory("m$index", "topic.$index", "关于 gradle 的事实 $index") }
        val selected = MemoryRecallSelector.selectRecall("gradle 相关都有哪些", candidates, now)
        assertEquals(MemoryRecallSelector.MAX_RECALL_FACTS, selected.size)
    }

    // ---------- 渲染 ----------

    @Test
    fun `rendered block is bounded and well formed`() {
        val longValue = "很长的记忆内容。".repeat(400)
        val candidates = listOf(memory("m1", "long.entry", longValue))
        val block = MemoryRecallSelector.renderBlock(candidates)
        assertTrue(block.startsWith("<recalled_memory>"))
        assertTrue(block.endsWith("</recalled_memory>"))
        assertTrue(block.length <= MemoryRecallSelector.MAX_RECALL_BLOCK_CHARS + 64)
    }
}
