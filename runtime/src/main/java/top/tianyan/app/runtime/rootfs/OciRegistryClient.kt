package top.tianyan.app.runtime.rootfs

import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.DistributionSpec
import top.tianyan.app.runtime.DownloadProgress
import top.tianyan.app.runtime.RegistryRoute
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class OciRegistryClient(
    http: OkHttpClient,
    private val logger: AppLogger,
) {
    private val http = http.forRootfsDownloads()
    private val layerStore = OciLayerStore(this.http)
    private val manifests = OciManifestStore()
    private val json = Json { ignoreUnknownKeys = true }

    data class ImageInfo(val version: String, val digest: String)

    suspend fun resolve(
        distribution: DistributionSpec,
        route: RegistryRoute,
    ): ImageInfo = withContext(Dispatchers.IO) {
        OciImageReference.parse(distribution.imageReference)
        val endpoints = when (route) {
            RegistryRoute.OFFICIAL -> listOf(Endpoint.dockerHub())
            RegistryRoute.CHINA_ACCELERATED -> listOf(Endpoint.daoCloud())
            RegistryRoute.AUTO -> listOf(Endpoint.daoCloud(), Endpoint.dockerHub())
        }
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            try {
                return@withContext resolveFrom(endpoint, distribution)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("OCI manifest check on ${endpoint.name} failed", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 OCI Registry 检查线路")
    }

    suspend fun pull(
        distribution: DistributionSpec,
        route: RegistryRoute,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        resetDestination: suspend () -> Unit,
        applyLayer: suspend (File, String) -> Unit,
    ): ImageInfo = withContext(Dispatchers.IO) {
        OciImageReference.parse(distribution.imageReference)
        val endpoints = when (route) {
            RegistryRoute.OFFICIAL -> listOf(Endpoint.dockerHub())
            RegistryRoute.CHINA_ACCELERATED -> listOf(Endpoint.daoCloud())
            RegistryRoute.AUTO -> listOf(Endpoint.daoCloud(), Endpoint.dockerHub())
        }
        var lastFailure: Throwable? = null
        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                if (index > 0) resetDestination()
                val metadataClient = if (route == RegistryRoute.AUTO && index == 0) {
                    http.newBuilder()
                        .connectTimeout(6, TimeUnit.SECONDS)
                        .readTimeout(12, TimeUnit.SECONDS)
                        .callTimeout(15, TimeUnit.SECONDS)
                        .build()
                } else {
                    http
                }
                return@withContext pullFrom(
                    endpoint,
                    distribution,
                    cacheDir,
                    onProgress,
                    applyLayer,
                    metadataClient,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                lastFailure = failure
                logger.w("OCI registry route ${endpoint.name} failed", failure)
            }
        }
        throw lastFailure ?: IllegalStateException("没有可用的 OCI Registry 下载线路")
    }

    private suspend fun pullFrom(
        endpoint: Endpoint,
        distribution: DistributionSpec,
        cacheDir: File,
        onProgress: suspend (DownloadProgress) -> Unit,
        applyLayer: suspend (File, String) -> Unit,
        metadataClient: OkHttpClient,
    ): ImageInfo {
        cacheDir.mkdirs()
        val parsed = OciImageReference.parse(distribution.imageReference)
        val repo = endpoint.rewriteRepo(parsed.repo)
        val token by lazy { endpoint.token(repo, metadataClient, json) }
        var response = manifests.get(
            "${endpoint.registryBase}/v2/$repo/manifests/${parsed.manifestRef}", { token },
            ACCEPT_MANIFESTS,
            metadataClient, parsed.digest, cacheDir,
        )
        var manifest = response.body
        var digest = response.digest
        if (manifest["manifests"] != null) {
            val target = manifest.getValue("manifests").jsonArray.firstOrNull { entry ->
                val platform = entry.jsonObject["platform"]?.jsonObject ?: return@firstOrNull false
                platform["os"]?.jsonPrimitive?.content == "linux" &&
                    platform["architecture"]?.jsonPrimitive?.content == "arm64"
            }?.jsonObject ?: error("镜像 ${distribution.imageReference} 不提供 linux/arm64")
            digest = target.getValue("digest").jsonPrimitive.content
            response = manifests.get(
                "${endpoint.registryBase}/v2/$repo/manifests/$digest", { token },
                ACCEPT_MANIFESTS,
                metadataClient, digest, cacheDir,
            )
            manifest = response.body
        }
        val layers = manifest["layers"]?.jsonArray ?: error("OCI manifest 没有文件系统层")
        val total = layers.sumOf { it.jsonObject["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }

        // 阶段一：受控并发下载全部层（移动网络单连接常被限速，3 路并发可显著提速；
        //         已校验缓存的层直接命中；进度按各层已下载字节汇总）
        data class LayerDownload(val file: File, val mediaType: String)
        val semaphore = Semaphore(MAX_PARALLEL_LAYER_DOWNLOADS)
        val downloaded = LongArray(layers.size)
        val blobs = coroutineScope {
            layers.mapIndexed { index, element ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val layer = element.jsonObject
                        val layerDigest = layer.getValue("digest").jsonPrimitive.content
                        val mediaType = layer["mediaType"]?.jsonPrimitive?.content.orEmpty()
                        val blob = layerStore.download("${endpoint.registryBase}/v2/$repo/blobs/$layerDigest", layerDigest, { token }, cacheDir) { current ->
                            downloaded[index] = current
                            onProgress(DownloadProgress(downloaded.sum(), total.takeIf { it > 0 }))
                        }
                        LayerDownload(blob, mediaType)
                    }
                }
            }.awaitAll()
        }

        // 阶段二：按层顺序解包（whiteout/硬链接语义依赖层顺序，不能并发）
        blobs.forEachIndexed { index, blob ->
            applyLayer(blob.file, blob.mediaType)
            onProgress(DownloadProgress(total, total.takeIf { it > 0 }))
            logger.i("Applied OCI layer ${index + 1}/${layers.size}: ${blob.file.name.takeLast(16)}")
        }
        return ImageInfo("oci-5.10.0-${distribution.id}-${parsed.manifestRef}", digest)
    }

    private fun resolveFrom(endpoint: Endpoint, distribution: DistributionSpec): ImageInfo {
        val parsed = OciImageReference.parse(distribution.imageReference)
        val repo = endpoint.rewriteRepo(parsed.repo)
        val token = endpoint.token(repo, http, json)
        var response = manifests.get(
            "${endpoint.registryBase}/v2/$repo/manifests/${parsed.manifestRef}", { token },
            ACCEPT_MANIFESTS,
            http, parsed.digest,
        )
        var digest = response.digest
        if (response.body["manifests"] != null) {
            val target = response.body.getValue("manifests").jsonArray.firstOrNull { entry ->
                val platform = entry.jsonObject["platform"]?.jsonObject ?: return@firstOrNull false
                platform["os"]?.jsonPrimitive?.content == "linux" &&
                    platform["architecture"]?.jsonPrimitive?.content == "arm64"
            }?.jsonObject ?: error("镜像 ${distribution.imageReference} 不提供 linux/arm64")
            digest = target.getValue("digest").jsonPrimitive.content
            response = manifests.get(
                "${endpoint.registryBase}/v2/$repo/manifests/$digest", { token },
                ACCEPT_MANIFESTS,
                http, digest,
            )
        }
        check(response.body["layers"]?.jsonArray != null) { "OCI manifest 没有文件系统层" }
        return ImageInfo("oci-5.10.0-${distribution.id}-${parsed.manifestRef}", digest)
    }

    private data class Endpoint(
        val name: String,
        val registryBase: String,
        val rewriteRepo: (String) -> String,
        val tokenProvider: (String, OkHttpClient, Json) -> String,
    ) {
        fun token(repo: String, http: OkHttpClient, json: Json) = tokenProvider(repo, http, json)

        companion object {
            fun dockerHub() = Endpoint("Docker Hub", "https://registry-1.docker.io", { it }) { repo, http, json ->
                val scope = URLEncoder.encode("repository:$repo:pull", Charsets.UTF_8.name())
                val request = Request.Builder().url("https://auth.docker.io/token?service=registry.docker.io&scope=$scope")
                    .header("User-Agent", USER_AGENT).build()
                http.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Docker Hub 鉴权失败 HTTP ${response.code}" }
                    val body = json.parseToJsonElement(response.body.string()).jsonObject
                    body["token"]?.jsonPrimitive?.content ?: body["access_token"]?.jsonPrimitive?.content.orEmpty()
                }
            }

            fun daoCloud() = Endpoint(
                "DaoCloud public mirror",
                "https://docker.m.daocloud.io",
                { it },
                { repo, http, json ->
                    val scope = URLEncoder.encode("repository:$repo:pull", Charsets.UTF_8.name())
                    val request = Request.Builder()
                        .url("https://m.daocloud.io/auth/token?service=docker.m.daocloud.io&scope=$scope")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    http.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "DaoCloud 鉴权失败 HTTP ${response.code}" }
                        json.parseToJsonElement(response.body.string()).jsonObject["token"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    }
                },
            )
        }
    }

    private companion object {
        const val USER_AGENT = "Tianyan/proot-distro-5.10.0"
        const val ACCEPT_MANIFESTS =
            "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"
        /** OCI layer 并行下载并发数（移动网络单连接限速时多路并发可显著提速）。 */
        const val MAX_PARALLEL_LAYER_DOWNLOADS = 3
    }
}
