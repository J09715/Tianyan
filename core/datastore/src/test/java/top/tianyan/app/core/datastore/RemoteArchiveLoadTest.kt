package top.tianyan.app.core.datastore

import org.junit.Assert.*
import org.junit.Test

class RemoteArchiveLoadTest {
    @Test fun `decryption failure and unreadable files remain unavailable instead of empty local config`() {
        val corrupt = loadRemoteArchive({ "ciphertext" }) { _: String -> error("invalid key") }
        val unreadable = loadRemoteArchive({ throw java.io.IOException("unreadable") }) { it }
        for (result in listOf(corrupt, unreadable)) {
            assertNull(result.snapshot)
            assertTrue(result.error!!.contains("执行已停用"))
        }
    }
    @Test fun `valid empty archive is a usable explicit local selection`() {
        val valid = loadRemoteArchive({ "local" }) { it }
        assertEquals("local", valid.snapshot); assertNull(valid.error)
    }
}
