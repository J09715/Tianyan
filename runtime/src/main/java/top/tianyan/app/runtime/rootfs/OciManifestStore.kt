package top.tianyan.app.runtime.rootfs

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** Cache manifests by verified content digest, allowing a pinned pull to run entirely offline. */
internal class OciManifestStore {
    data class Manifest(val body: JsonObject, val digest: String)

    fun get(
        url: String,
        token: () -> String,
        accept: String,
        client: OkHttpClient,
        expectedDigest: String? = null,
        cacheDir: File? = null,
    ): Manifest {
        val cached = expectedDigest?.let { cacheFile(cacheDir, it) }
        if (cached?.isFile == true) {
            val raw = cached.readBytes()
            if (digest(raw) == expectedDigest) return Manifest(parse(raw), expectedDigest)
            cached.delete()
        }
        val request = Request.Builder().url(url).header("Accept", accept)
            .header("User-Agent", "Tianyan/proot-distro-5.10.0")
            .apply { token().takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }.build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Registry 请求失败 HTTP ${response.code}: $url" }
            val raw = response.body.bytes()
            val actual = digest(raw)
            check(expectedDigest == null || actual == expectedDigest) { "OCI manifest 与固定 digest 不匹配" }
            check(response.header("Docker-Content-Digest")?.let { it == actual } != false) {
                "OCI manifest 与响应摘要不匹配"
            }
            val result = Manifest(parse(raw), actual)
            cacheFile(cacheDir, actual)?.let { target ->
                if (target.isFile && digest(target.readBytes()) == actual) return@let
                if (target.exists()) check(target.delete()) { "无法移除损坏的 OCI manifest 缓存" }
                target.parentFile?.mkdirs()
                val partial = File(target.path + ".part")
                partial.writeBytes(raw)
                check(partial.renameTo(target)) { "无法提交 OCI manifest 缓存" }
            }
            result
        }
    }

    private fun cacheFile(dir: File?, value: String): File? {
        require(value.matches(Regex("sha256:[a-f0-9]{64}"))) { "非法或不支持的 OCI manifest digest" }
        return dir?.let { File(it, "sha256-${value.substringAfter(':')}.manifest.json") }
    }

    private fun parse(raw: ByteArray): JsonObject = Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
    private fun digest(raw: ByteArray): String = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(raw).joinToString("") { "%02x".format(it) }
}
