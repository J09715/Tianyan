package top.tianyan.app.core.tools.skill

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.common.result.AppResult

class ClawHubClientTest {

    private val httpClient = OkHttpClient()
    private val client = ClawHubClient(httpClient, hubRegistryBaseUrl = "mock://disabled")
    private val parser = SkillPackageParser()

    @Test
    fun fetchMarketCatalog_returnsBuiltinPresetsWhenOffline() = runBlocking {
        val result = client.fetchMarketCatalog()
        assertTrue(result is AppResult.Success)
        val items = (result as AppResult.Success).data
        assertEquals(5, items.size)
        assertTrue(items.any { it.id == "git-workflow" })
        assertTrue(items.any { it.id == "python-pro" })
        assertTrue(items.any { it.id == "code-auditor" })
    }

    @Test
    fun fetchMarketCatalog_filtersByQueryAndCategory() = runBlocking {
        val gitResult = client.fetchMarketCatalog(query = "git")
        assertTrue(gitResult is AppResult.Success)
        val gitItems = (gitResult as AppResult.Success).data
        assertEquals(1, gitItems.size)
        assertEquals("git-workflow", gitItems.first().id)

        val secResult = client.fetchMarketCatalog(category = "安全审计")
        assertTrue(secResult is AppResult.Success)
        val secItems = (secResult as AppResult.Success).data
        assertEquals(1, secItems.size)
        assertEquals("code-auditor", secItems.first().id)
    }

    @Test
    fun downloadPackage_generatesValidZipForPreset() = runBlocking {
        val result = client.downloadPackage("git-workflow")
        assertTrue(result is AppResult.Success)
        val zipBytes = (result as AppResult.Success).data
        assertTrue(zipBytes.isNotEmpty())

        // 验证下载的 ZIP 包能被 parser 完整解构
        val pkg = parser.parseFromZip(zipBytes)
        assertEquals("git-workflow", pkg.manifest.id)
        assertNotNull(pkg.templates.skillMd)
        assertNotNull(pkg.templates.agentMd)
        assertNotNull(pkg.templates.soulMd)
        assertNotNull(pkg.templates.toolsMd)
    }
}
