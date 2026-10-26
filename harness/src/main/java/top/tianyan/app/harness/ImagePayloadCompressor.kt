package top.tianyan.app.harness

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 发给 Provider 之前把 data URL 图片压到移动端可传输的体积。
 *
 * 相册/截屏常见 1080P+ PNG，Base64 后单张 2–6MB；两三张就会在 Nginx/中转站触发 HTTP 413。
 * 等比缩到长边 [MAX_EDGE_PX] 并转 80% JPEG 后通常落到一两百 KB。解码失败时原样返回，
 * 由 [ContextWindowPolicy.enforceRequestByteBudget] 做体积兜底剥离。
 */
object ImagePayloadCompressor {
    const val MAX_EDGE_PX = 1280
    const val JPEG_QUALITY = 80
    /** 已足够小的图不再二次解码，避免每轮请求都咬 CPU。 */
    const val SKIP_UNDER_BYTES = 150_000

    fun downscaleHarness(messages: List<HarnessMessage>): List<HarnessMessage> {
        var changed = false
        val out = messages.map { message ->
            if (message !is UserMessage || message.imageUrls.isEmpty()) return@map message
            val shrunk = message.imageUrls.map(::downscaleDataUrl)
            if (shrunk == message.imageUrls) return@map message
            changed = true
            message.copy(imageUrls = shrunk)
        }
        return if (changed) out else messages
    }

    fun downscale(messages: List<ApiMessage>): List<ApiMessage> {
        var changed = false
        val out = messages.map { message ->
            if (message.imageUrls.isEmpty()) return@map message
            val shrunk = message.imageUrls.map(::downscaleDataUrl)
            if (shrunk == message.imageUrls) return@map message
            changed = true
            message.copy(imageUrls = shrunk)
        }
        return if (changed) out else messages
    }

    fun downscaleDataUrl(url: String): String {
        if (!url.startsWith("data:image/", ignoreCase = true)) return url
        val comma = url.indexOf(',')
        if (comma <= 0) return url
        val meta = url.substring(5, comma) // skip "data:"
        if (!meta.contains("base64", ignoreCase = true)) return url
        val payload = url.substring(comma + 1)
        val approxBytes = payload.length * 3 / 4
        if (approxBytes <= SKIP_UNDER_BYTES) return url
        return runCatching { compressBase64(payload) }.getOrNull()?.takeIf { it.length < url.length } ?: url
    }

    private fun compressBase64(payload: String): String {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcWidth = bounds.outWidth
        val srcHeight = bounds.outHeight
        val decode = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(srcWidth, srcHeight, MAX_EDGE_PX)
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode) ?: error("decode failed")
        val scaled = scaleToMaxEdge(bitmap, MAX_EDGE_PX)
        try {
            val out = ByteArrayOutputStream()
            if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                error("jpeg compress failed")
            }
            val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            return "data:image/jpeg;base64,$encoded"
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun sampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / sample > maxEdge * 2) {
            sample *= 2
        }
        return sample
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }
}
