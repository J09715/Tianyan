package top.tianyan.app.runtime.service

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared preflight/launch encoder. Fixed-width identity fields never affect resource compression. */
object ProotWebBootstrap {
    const val MAX_PACKET_BYTES = 4096
    private const val HEADER_BYTES = 36 + 1 + 72 + 1 + 1 // UUID, tab, two UUIDs, tab, newline
    private val uuid = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")

    fun encode(assets: Map<String, ByteArray>, instance: String, authorization: String): ByteArray {
        require(uuid.matches(instance))
        val nonce = authorization.removePrefix("Bearer ")
        require(authorization.startsWith("Bearer ") && nonce.length == 72 &&
            uuid.matches(nonce.take(36)) && uuid.matches(nonce.drop(36)))
        val payload = compressedAssets(assets)
        return "$instance\t$nonce\t$payload\n".toByteArray(Charsets.US_ASCII)
    }

    /** No process, random identities, credentials or Linux setup are needed for this check. */
    fun requireFits(assets: Map<String, ByteArray>) { compressedAssets(assets) }

    private fun compressedAssets(assets: Map<String, ByteArray>): String {
        require(assets.size in 1..63 && "index.html" in assets && assets.keys.all(ManagedWebAssets::safePath))
        require(assets.values.sumOf { it.size.toLong() } <= 2 * 1024 * 1024)
        val json = buildJsonObject {
            assets.toSortedMap().forEach { (name, bytes) -> put(name, Base64.getEncoder().encodeToString(bytes)) }
        }.toString().toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(json) }
        }.toByteArray()
        val payload = Base64.getEncoder().encodeToString(compressed)
        if (payload.length + HEADER_BYTES > MAX_PACKET_BYTES) throw ProotWebBootstrapLimitException()
        return payload
    }
}

/** A typed, content-free failure that may be safely mapped to a localized import message. */
class ProotWebBootstrapLimitException : IllegalArgumentException("PRoot Web bootstrap exceeds 4 KiB")
