package top.tianyan.app.ui.components

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.ChatBackgroundStyle
import androidx.core.graphics.scale

/** Same image renderer for the settings preview and the home chat, including Android 10/11 blur. */
@Composable
fun RuntimeChatBackground(style: ChatBackgroundStyle, modifier: Modifier = Modifier) {
    val normalized = style.normalized()
    val directory = File(LocalContext.current.applicationContext.filesDir, ChatBackgroundStyle.IMAGE_DIRECTORY)
    val name = normalized.imageName
    val radius = normalized.blurDp.roundToInt()
    val rendered by produceState<Pair<String, Bitmap>?>(null, name, radius) {
        if (name == null) { value = null; return@produceState }
        try {
            val bitmap = withContext(Dispatchers.IO) { ChatBackgroundBitmaps.load(File(directory, name), radius) }
            value = name to bitmap
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = null }
    }
    val bitmap = rendered?.takeIf { it.first == name }?.second
    Box(modifier.background(MaterialTheme.colorScheme.background)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = normalized.visibility },
            )
        }
    }
}

private object ChatBackgroundBitmaps {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun load(file: File, radius: Int): Bitmap {
        val key = "${file.name}:$radius"
        cache.get(key)?.let { return it }
        val originalKey = "${file.name}:original"
        val original = cache.get(originalKey) ?: ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSampleSize(ceil(max(info.size.width, info.size.height) / 1536f).toInt().coerceAtLeast(1))
        }.also { cache.put(originalKey, it) }
        currentCoroutineContext().ensureActive()
        val result = if (radius == 0) original else blur(original, radius)
        cache.put(key, result)
        return result
    }

    // Two separable box passes on a downsampled image keep slider updates bounded in memory/time.
    private suspend fun blur(original: Bitmap, radius: Int): Bitmap {
        val scale = minOf(1f, 384f / max(original.width, original.height))
        val width = (original.width * scale).roundToInt().coerceAtLeast(1)
        val height = (original.height * scale).roundToInt().coerceAtLeast(1)
        val small = original.scale(width, height)
        val pixels = IntArray(width * height)
        small.getPixels(pixels, 0, width, 0, 0, width, height)
        if (small !== original) small.recycle()
        val scratch = IntArray(pixels.size)
        val context = currentCoroutineContext()
        fun pass(source: IntArray, output: IntArray, horizontal: Boolean) {
            val lines = if (horizontal) height else width
            val length = if (horizontal) width else height
            val count = radius * 2 + 1
            for (line in 0 until lines) {
                context.ensureActive()
                fun index(position: Int): Int {
                    val clamped = position.coerceIn(0, length - 1)
                    return if (horizontal) line * width + clamped else clamped * width + line
                }
                var a = 0; var r = 0; var g = 0; var b = 0
                fun accumulate(pixel: Int, sign: Int) {
                    a += (pixel ushr 24) * sign
                    r += ((pixel ushr 16) and 255) * sign
                    g += ((pixel ushr 8) and 255) * sign
                    b += (pixel and 255) * sign
                }
                for (offset in -radius..radius) accumulate(source[index(offset)], 1)
                for (position in 0 until length) {
                    output[index(position)] = ((a / count) shl 24) or ((r / count) shl 16) or
                        ((g / count) shl 8) or (b / count)
                    accumulate(source[index(position - radius)], -1)
                    accumulate(source[index(position + radius + 1)], 1)
                }
            }
        }
        repeat(2) { pass(pixels, scratch, true); pass(scratch, pixels, false) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
