package top.tianyan.app.ui.bot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Small self-contained avatar payload; survives picker permission expiry and Bot ZIP export. */
internal object BotAvatarImage {
    private const val PREFIX = "data:image/png;base64,"
    private const val EDGE = 256
    private const val MAX_PAYLOAD = 400_000
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun readFrom(context: Context, uri: Uri): String {
        val original = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val scale = minOf(1.0, EDGE.toDouble() / maxOf(info.size.width, info.size.height))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
        }
        try {
            val edge = minOf(original.width, original.height)
            val square = Bitmap.createBitmap(original, (original.width - edge) / 2, (original.height - edge) / 2, edge, edge)
            try {
                val bytes = ByteArrayOutputStream().use { output ->
                    check(square.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
                return (PREFIX + Base64.encodeToString(bytes, Base64.NO_WRAP)).also { require(it.length <= MAX_PAYLOAD) }
            } finally {
                if (square !== original) square.recycle()
            }
        } finally {
            original.recycle()
        }
    }

    fun decode(seed: String): Bitmap? {
        if (!seed.startsWith(PREFIX) || seed.length > MAX_PAYLOAD) return null
        cache.get(seed)?.let { return it }
        return runCatching {
            val bytes = Base64.decode(seed.substring(PREFIX.length), Base64.NO_WRAP)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth in 1..EDGE && bounds.outHeight in 1..EDGE)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { cache.put(seed, it) }
        }.getOrNull()
    }
}
