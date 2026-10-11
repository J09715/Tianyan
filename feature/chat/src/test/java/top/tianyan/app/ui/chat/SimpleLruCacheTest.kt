package top.tianyan.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Markdown 解析缓存改用纯 JDK 实现后的行为保护。
 *
 * 换掉 android.util.LruCache 的原因：它在纯 JVM 单测里是未实现的桩，
 * 会让整块 Markdown 解析逻辑无法在 CI 上验证。
 */
class SimpleLruCacheTest {

    @Test
    fun `evicts the least recently used entry`() {
        val cache = SimpleLruCache<Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)

        assertEquals(1, cache.get("a"))
        cache.put("c", 3)

        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(3, cache.get("c"))
    }

    @Test
    fun `repeated put keeps a single entry per key`() {
        val cache = SimpleLruCache<Int>(4)
        cache.put("a", 1)
        cache.put("a", 2)

        assertEquals(2, cache.get("a"))
    }

    @Test
    fun `zero capacity degrades to a single-entry cache instead of dropping everything`() {
        val cache = SimpleLruCache<Int>(0)
        cache.put("a", 1)

        assertEquals(1, cache.get("a"))
    }
}
