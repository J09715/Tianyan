package top.tianyan.app.runtime.uml

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import top.tianyan.app.core.common.logging.AppLogger

/**
 * UML 产物仓库：现拉 manifest，解析出 rootfs 的 url + sha256 + size。
 *
 * 对齐 Stryker 的 `ota/ManifestService` + `ota/RemoteManifest`：
 * - 清单地址固定（Stryker 的 stryker_manifest.json），每次启动现拉；
 * - 解析失败 / 离线 → 降级到内置回退地址（rootless-650），保证可用性不依赖网络；
 * - 解析成功 → 拿到真实 sha256，下载可以做强校验（此前是空校验）。
 */
@Singleton
class UmlArtifactRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val logger: AppLogger,
) {
    /** 解析结果：url + 可选 sha256 + size + 来源（manifest 或内置回退）。 */
    data class ResolvedRootfs(
        val url: String,
        val sha256: String?,
        val size: Long,
        val source: String,
    )

    /** 拉取并解析 manifest；任何失败都返回 null（调用方降级）。 */
    suspend fun fetchManifest(): UmlManifest? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(MANIFEST_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.w("UML manifest HTTP ${response.code}")
                    return@use null
                }
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    logger.w("UML manifest body empty")
                    return@use null
                }
                runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<UmlManifest>(body) }
                    .getOrElse {
                        logger.w("UML manifest parse failed: ${it.message}")
                        null
                    }
            }
        }.getOrElse {
            logger.w("UML manifest fetch failed: ${it.message}")
            null
        }
    }

    /**
     * 解析 rootfs 产物：manifest 优先（带 sha256），失败降级内置回退。
     * 返回的 [ResolvedRootfs.source] 让 UI 能说明「用的是清单版本还是回退」。
     */
    suspend fun resolveRootfsAsset(): ResolvedRootfs {
        val asset = fetchManifest()?.rootfs
        return if (asset != null && asset.hasChecksum) {
            ResolvedRootfs(
                url = asset.url,
                sha256 = asset.sha256,
                size = asset.size,
                source = "manifest (${asset.size / (1024 * 1024)}MB)",
            )
        } else if (asset != null) {
            ResolvedRootfs(asset.url, null, asset.size, "manifest (no checksum)")
        } else {
            ResolvedRootfs(FALLBACK_ROOTFS_URL, null, 0L, "bundled fallback")
        }
    }

    companion object {
        /** Stryker 的产物清单（UML 产物由该清单描述）。 */
        const val MANIFEST_URL =
            "https://raw.githubusercontent.com/zalexdev/strykerapp/main/stryker_manifest.json"

        /** 清单拉不到时的内置回退（immutable release tag，资产不可被替换）。 */
        const val FALLBACK_ROOTFS_URL =
            "https://github.com/zalexdev/strykerapp/releases/download/rootless-650/rootfs.imgz"

        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36"
    }
}
