package top.tianyan.app.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownCacheTest {

    @Before
    fun setUp() {
        clearMarkdownCachesForTest()
    }

    @After
    fun tearDown() {
        clearMarkdownCachesForTest()
    }

    @Test
    fun `large cache keyed only by id must not reuse a different body`() {
        val key = "assistant:msg-1"
        val first = "# first body\n\nhello"
        val second = "# second body\n\ncompletely different"
        markdownBlocksPreview(first, key, largeCacheMinChars = 8)
        val reused = markdownBlocksPreview(second, key, largeCacheMinChars = 8)
        assertEquals("H1:second body\nP:completely different", reused)
        assertNotEquals(markdownBlocksPreview(first, key, largeCacheMinChars = 8), reused)
    }

    @Test
    fun `matching body with the same id is a cache hit`() {
        val key = "assistant:msg-2"
        val body = "# stable\n\nunchanged paragraph"
        val first = markdownBlocksPreview(body, key, largeCacheMinChars = 8)
        val second = markdownBlocksPreview(body, key, largeCacheMinChars = 8)
        assertEquals(first, second)
        assertEquals("H1:stable\nP:unchanged paragraph", second)
    }

    @Test
    fun `short messages stream by reparsing when the body grows`() {
        val key = "assistant:live"
        val first = markdownBlocksPreview("Hi", key, largeCacheMinChars = 10_000)
        val second = markdownBlocksPreview("Hi there", key, largeCacheMinChars = 10_000)
        assertEquals("P:Hi", first)
        assertEquals("P:Hi there", second)
    }
}
