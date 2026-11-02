package top.tianyan.app.core.network

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AppUpdateManager 行为测试（批次 E）。
 * 全部走本地 MockWebServer：checkUpdate 的失败分支与 downloadApk 的 HTTP 错误拒绝路径，
 * 不触真实 GitHub API，不下载真实 APK。
 *
 * Robolectric 仅提供 Context.cacheDir；仅在支持 Robolectric native 的环境执行
 * （CI x86_64，见 unit-test.yml）。本机 Linux aarch64 上 Robolectric native 未提供，
 * 标 @Ignore 本机跳过。
 */
@RunWith(RobolectricTestRunner::class)
@Ignore("Robolectric native runtime unavailable on local aarch64; runs in CI x86_64")
class AppUpdateManagerTest {

    private lateinit var server: MockWebServer
    private lateinit var manager: AppUpdateManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        manager = AppUpdateManager(ApplicationProvider.getApplicationContext(), client)
    }

    @After
    fun tearDown() {
        server.shutdown()
        File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "updates")
            .deleteRecursively()
    }

    @Test
    fun `checkUpdate fails on http error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val result = manager.checkUpdate("1.0.0")
        // HTTP 500 必须走失败分支，不能把错误体当成 release 信息解析。
        assertTrue(result.isFailure)
    }

    @Test
    fun `checkUpdate fails on malformed body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json {{{"))
        val result = manager.checkUpdate("1.0.0")
        assertTrue(result.isFailure)
    }

    @Test
    fun `downloadApk rejects http error and never writes partial file`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("nope"))
        val progress = mutableListOf<Pair<Long, Long?>>()
        val result = manager.downloadApk(server.url("/tianyan.apk").toString()) { d, t -> progress += d to t }
        assertTrue(result.isFailure)
        assertTrue(progress.isEmpty())
        // 拒绝分支不能在缓存里留下半截 APK。
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "updates")
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `downloadApk succeeds on valid body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("PK\u0003\u0004fake-apk-bytes"))
        val result = manager.downloadApk(server.url("/tianyan.apk").toString()) { _, _ -> }
        val file = result.getOrNull()
        assertTrue(file != null && file.length() == "PK\u0003\u0004fake-apk-bytes".length.toLong())
    }
}
