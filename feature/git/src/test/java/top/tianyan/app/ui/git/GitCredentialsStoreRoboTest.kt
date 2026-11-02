package top.tianyan.app.ui.git

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.Ignore
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import top.tianyan.app.core.security.SecretManager

/**
 * GitCredentialsStore 读写回环（Robolectric Context + SecretManager AndroidKeyStore）。
 *
 * 仅在支持 Robolectric native runtime 的环境执行（CI x86_64 runner，见
 * .github/workflows/unit-test.yml）。本机 Linux aarch64 上 Robolectric native
 * 未提供（"The Robolectric native runtime is not supported on Linux (aarch64)"），
 * 因此标 @Ignore 由本机跳过——host 解析的纯函数部分在 [GitCredentialsStoreTest]
 * 覆盖，不受影响。
 */
@RunWith(RobolectricTestRunner::class)
@Ignore("Robolectric native runtime unavailable on local aarch64; runs in CI x86_64")
class GitCredentialsStoreRoboTest {

    private val store = GitCredentialsStore(
        ApplicationProvider.getApplicationContext(),
        SecretManager(),
    )

    @Test
    fun `saved credentials round trip and removal clears them`() = runTest {
        store.save("github.com", "me", "tok-1")
        assertEquals("me" to "tok-1", store.get("github.com"))

        store.remove("github.com")
        assertNull(store.get("github.com"))
        assertNull(store.get("unknown.host"))
    }
}
