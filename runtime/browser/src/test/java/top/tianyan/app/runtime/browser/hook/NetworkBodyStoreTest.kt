package top.tianyan.app.runtime.browser.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class NetworkBodyStoreTest {

    private fun body(id: String, reqLen: Int, resLen: Int, tabId: String = "t") =
        NetworkBodyStore.NetworkBody(
            id = id,
            tabId = tabId,
            requestBody = "r".repeat(reqLen),
            responseBody = "s".repeat(resLen),
        )

    @Test
    fun `put and get round trip`() {
        val s = NetworkBodyStore()
        s.put(body("n1", 10, 20))
        val got = s.get("n1")
        assertNotNull(got)
        assertEquals(10, got!!.requestBody.length)
        assertEquals(30, s.totalStoredBytes())
    }

    @Test
    fun `oversized entry rejected`() {
        val s = NetworkBodyStore(totalBudgetBytes = 100)
        s.put(body("big", 200, 0))
        assertNull(s.get("big"))
        assertEquals(0, s.totalStoredBytes())
    }

    @Test
    fun `budget overflow evicts oldest`() {
        val s = NetworkBodyStore(totalBudgetBytes = 150)
        s.put(body("old", 60, 40))       // 100B
        s.put(body("mid", 20, 20))       // 40B → 140B
        s.get("old") // Refresh old; mid becomes least-recently used.
        s.put(body("new", 25, 25))       // 50B → evict mid, retain old and new.
        assertNull(s.get("mid"))
        assertNotNull(s.get("old"))
        assertNotNull(s.get("new"))
        assertEquals(150L, s.totalStoredBytes())
    }

    @Test
    fun `budget counts utf8 bytes and rejects an entry above budget`() {
        val s = NetworkBodyStore(totalBudgetBytes = 5)
        s.put(NetworkBodyStore.NetworkBody("emoji", "t", "你", "好"))
        assertEquals(6L, "你好".toByteArray(Charsets.UTF_8).size.toLong())
        assertNull(s.get("emoji"))
        assertEquals(0L, s.totalStoredBytes())
    }

    @Test
    fun `replacement adjusts utf8 byte accounting`() {
        val s = NetworkBodyStore(totalBudgetBytes = 5)
        s.put(NetworkBodyStore.NetworkBody("replace", "t", "é", ""))
        assertEquals(2L, s.totalStoredBytes())
        s.put(NetworkBodyStore.NetworkBody("replace", "t", "你", "好"))
        assertEquals("é", s.get("replace")?.requestBody)
        assertEquals(2L, s.totalStoredBytes())
    }

    @Test
    fun `zero budget accepts only empty bodies`() {
        val s = NetworkBodyStore(totalBudgetBytes = 0)
        s.put(NetworkBodyStore.NetworkBody("empty", "t", "", ""))
        s.put(NetworkBodyStore.NetworkBody("text", "t", "x", ""))
        assertNotNull(s.get("empty"))
        assertNull(s.get("text"))
        assertEquals(0L, s.totalStoredBytes())
    }

    @Test
    fun `negative budget is rejected`() {
        try {
            NetworkBodyStore(totalBudgetBytes = -1)
            fail("negative budget must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `clearForTab only touches given tab`() {
        val s = NetworkBodyStore()
        s.put(body("a", 5, 5, tabId = "tabA"))
        s.put(body("b", 5, 5, tabId = "tabB"))
        s.clearForTab("tabA")
        assertNull(s.get("a"))
        assertNotNull(s.get("b"))
    }

    @Test
    fun `clear removes everything`() {
        val s = NetworkBodyStore()
        s.put(body("a", 5, 5))
        s.put(body("b", 5, 5))
        s.clear()
        assertEquals(0, s.size())
        assertEquals(0L, s.totalStoredBytes())
    }
}
