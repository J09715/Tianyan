package top.tianyan.app.runtime.service

import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProotWebBootstrapTest {
    private fun auth() = "Bearer ${UUID.randomUUID()}${UUID.randomUUID()}"

    @Test fun framingPreservesBytesAndKeepsCredentialsOutOfCompressedAssets() {
        val bytes = byteArrayOf(0, 1, 127, -1)
        val instance = UUID.randomUUID().toString()
        val authorization = auth()
        val packet = ProotWebBootstrap.encode(mapOf("index.html" to bytes), instance, authorization)
        val fields = packet.toString(Charsets.US_ASCII).trimEnd('\n').split('\t')
        assertEquals(instance, fields[0])
        assertEquals(authorization, "Bearer ${fields[1]}")
        val raw = InflaterInputStream(ByteArrayInputStream(Base64.getDecoder().decode(fields[2]))).readBytes().toString(Charsets.UTF_8)
        assertFalse(raw.contains(instance)); assertFalse(raw.contains(authorization))
        assertArrayEquals(bytes, Base64.getDecoder().decode(Json.parseToJsonElement(raw).jsonObject.getValue("index.html").jsonPrimitive.content))
    }

    @Test fun sizeAndAssetEncodingAreIndependentOfNonceAndInputMapOrder() {
        val a = mapOf("index.html" to "html".toByteArray(), "z.js" to "script".toByteArray())
        ProotWebBootstrap.requireFits(a)
        val first = ProotWebBootstrap.encode(a, UUID.randomUUID().toString(), auth()).toString(Charsets.US_ASCII)
        repeat(20) {
            val next = ProotWebBootstrap.encode(a.entries.reversed().associate { it.toPair() }, UUID.randomUUID().toString(), auth()).toString(Charsets.US_ASCII)
            assertEquals(first.length, next.length)
            assertEquals(first.split('\t')[2], next.split('\t')[2])
        }
    }

    @Test fun preflightAndEncodingAgreeAtCapacityBoundary() {
        val data = ByteArray(5000).also { java.util.Random(7).nextBytes(it) }
        var accepted = 0
        var rejected = 0
        for (size in 2800..3300 step 4) {
            val files = mapOf("index.html" to data.copyOf(size))
            val preflight = runCatching { ProotWebBootstrap.requireFits(files) }
            val launch = runCatching { ProotWebBootstrap.encode(files, UUID.randomUUID().toString(), auth()) }
            assertEquals(preflight.isSuccess, launch.isSuccess)
            if (launch.isSuccess) { accepted++; assertTrue(launch.getOrThrow().size <= 4096) }
            else { rejected++; assertTrue(launch.exceptionOrNull() is ProotWebBootstrapLimitException) }
        }
        assertTrue(accepted > 0 && rejected > 0)
    }

    @Test fun rejectsHeaderInjectionAndOversizedOrUnsafeAssetSets() {
        val files = mapOf("index.html" to "ok".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ProotWebBootstrap.encode(files, "bad\nidentity", auth()) }
        assertThrows(IllegalArgumentException::class.java) { ProotWebBootstrap.encode(files, UUID.randomUUID().toString(), "Bearer short\tvalue") }
        assertThrows(IllegalArgumentException::class.java) { ProotWebBootstrap.requireFits(files + ("../secret" to byteArrayOf())) }
        assertThrows(IllegalArgumentException::class.java) { ProotWebBootstrap.requireFits(mapOf("index.html" to ByteArray(2 * 1024 * 1024 + 1))) }
    }
}
