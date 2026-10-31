package top.tianyan.app.feature.a2uipoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.A2uiSurfaceContract

class TianyanA2uiCatalogTest {

    @Test
    fun `legacy basic catalog id is rewritten to the tianyan catalog`() {
        val raw = """[{"createSurface":{"catalogId":"${A2uiSurfaceContract.LEGACY_CATALOG_ID}"}}]"""
        val aligned = TianyanA2uiCatalog.alignCatalogId(raw)
        assertTrue(aligned.contains(A2uiSurfaceContract.CATALOG_ID))
        assertFalse(aligned.contains("catalogs/basic"))
    }

    @Test
    fun `surface id is prefixed with the session and text is left alone`() {
        val raw = """[{"version":"v0.9","createSurface":{"surfaceId":"s1","catalogId":"x"},"note":"s1"}]"""
        val scoped = TianyanA2uiCatalog.scopeMessages(raw, "session-a")
        assertTrue(scoped.contains("session-a:s1"))
        assertTrue(scoped.contains("\"note\":\"s1\""))
        assertEquals(raw, TianyanA2uiCatalog.scopeMessages(raw, ""))
    }
}
