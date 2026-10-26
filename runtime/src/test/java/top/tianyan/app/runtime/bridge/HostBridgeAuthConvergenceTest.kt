package top.tianyan.app.runtime.bridge

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.runtime.ElfInspector
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.bridge.adb.EmbeddedAdbManager
import top.tianyan.app.runtime.privilege.PrivilegeManager
import top.tianyan.app.runtime.privilege.ShizukuHostServiceClient
import top.tianyan.app.runtime.rootfs.RootfsValidator

/**
 * 宿主桥接鉴权收敛测试。
 *
 * 断言所有写入口——宿主端点 (`/api/install-apk` / `/api/shell` / `/api/logcat`) 与插件
 * `/api/plugin/call`——都在同一条 [HostCredentialAuthority] 校验路径上判定，且不存在任何
 * 共享密钥文件回退：旧 `.bridge-key` 时代的字符串、缺失凭据一律 401。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HostBridgeAuthConvergenceTest {
    private val context: Context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
        // Robolectric leaves nativeLibraryDir unset, but RuntimePathManager derives its bundled tool paths from it.
        override fun getApplicationInfo(): ApplicationInfo = baseContext.applicationInfo.apply {
            nativeLibraryDir = File(baseContext.filesDir, "lib").apply { mkdirs() }.absolutePath
        }
    }
    private val logger = AppLogger(context, SensitiveDataRedactor { it })
    private val preferences = RuntimePreferences(SettingsDataStore(context, SecretManager()))
    private val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
    private val authority = HostCredentialAuthority()
    private val bridge = HostBridge(
        context = context,
        logger = logger,
        privilegeManager = PrivilegeManager(context, preferences, logger, ShizukuHostServiceClient(context)),
        embeddedAdbManager = EmbeddedAdbManager(context, preferences, paths),
        pathManager = paths,
        credentialAuthority = authority,
    )

    private fun request(
        path: String,
        token: String? = null,
        method: String = "POST",
        body: String = "{}",
    ) = HostBridgeHttp.HttpRequest(method, path, token?.let { mapOf("authorization" to it) } ?: emptyMap(), body)

    private fun sandboxToken(actions: Set<String> = SandboxCredentialStore.SANDBOX_ACTIONS): String =
        SandboxCredentialStore(authority, grantedActions = { actions }).environment("ubuntu")
            .getValue(SandboxCredentialStore.BRIDGE_TOKEN_ENV)

    @Test fun unauthenticatedHostWritesAreRefusedWith401() = runBlocking {
        listOf("/api/install-apk", "/api/shell", "/api/logcat").forEach { path ->
            assertEquals(401, bridge.route(request(path)).status)
        }
    }

    @Test fun legacySharedKeyCannotAuthenticateAnything() = runBlocking {
        listOf("Bearer legacy-shared-key", "legacy-shared-key", "Bearer ", "Bearer null").forEach { legacy ->
            listOf("/api/install-apk", "/api/shell", "/api/logcat", "/api/other").forEach { path ->
                assertEquals(401, bridge.route(request(path, legacy)).status)
            }
        }
    }

    @Test fun sandboxInjectedCredentialIsExactlyWhatHostEndpointsAccept() = runBlocking {
        val token = sandboxToken()
        // 绑定动作内的端点：已鉴权，畸形正文使其止于 400，绝不触发真实特权执行。
        assertEquals(400, bridge.route(request("/api/install-apk", "Bearer $token", body = "{")).status)
        assertEquals(400, bridge.route(request("/api/logcat", "Bearer $token", body = "{")).status)
        // 未绑定动作：动作集校验先于端点执行，仍是 401。
        assertEquals(401, bridge.route(request("/api/shell", "Bearer $token")).status)
    }

    @Test fun fullAccessCredentialIsTheOnlyWayToReachHostShell() = runBlocking {
        val token = sandboxToken(SandboxCredentialStore.FULL_ACCESS_ACTIONS)
        assertEquals(400, bridge.route(request("/api/shell", "Bearer $token", body = "{")).status)
    }

    @Test fun epochAdvanceInvalidatesOutstandingCredentials() = runBlocking {
        val token = sandboxToken()
        assertNotEquals(401, bridge.route(request("/api/install-apk", "Bearer $token", body = "{")).status)
        // 等价于 HostBridge.stop()：全量撤销并推进代次。
        authority.revokeAll()
        listOf("/api/install-apk", "/api/shell", "/api/logcat").forEach { path ->
            assertEquals(401, bridge.route(request(path, "Bearer $token", body = "{")).status)
        }
    }

    @Test fun unknownPathsAuthenticateBefore404() = runBlocking {
        assertEquals(401, bridge.route(request("/api/other")).status)
        assertEquals(404, bridge.route(request("/api/other", "Bearer ${sandboxToken()}")).status)
    }

    @Test fun healthStaysUnauthenticatedAndLeaksNoCredential() = runBlocking {
        val token = sandboxToken()
        val response = bridge.route(request("/api/health", "Bearer $token", method = "GET", body = ""))
        assertEquals(200, response.status)
        assertFalse(response.body.contains(token))
    }
}
