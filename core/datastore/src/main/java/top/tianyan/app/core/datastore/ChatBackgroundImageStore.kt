package top.tianyan.app.core.datastore

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.tianyan.app.core.model.ChatBackgroundStyle

/** Copies a user-selected image into private storage; never retains access to the source URI. */
class ChatBackgroundImageStore(
    private val directory: File,
    private val openImage: (Uri) -> InputStream?,
    private val maxBytes: Long = 25L * 1024 * 1024,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, ChatBackgroundStyle.IMAGE_DIRECTORY),
        { context.applicationContext.contentResolver.openInputStream(it) },
    )

    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${UUID.randomUUID()}.img")
        var accepted = false
        try {
            val input = openImage(uri) ?: throw IOException("Image is unavailable")
            input.use { source ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("Image is too large")
                        output.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(target.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L) {
                throw IOException("Unsupported image")
            }
            currentCoroutineContext().ensureActive()
            accepted = true
            target.name
        } finally {
            if (!accepted) target.delete()
        }
    }

    suspend fun deleteImage(name: String?) = withContext(Dispatchers.IO) {
        if (name != null && ChatBackgroundStyle.isValidImageName(name)) File(directory, name).delete()
        Unit
    }

    /** Decode a bounded, orientation-correct preview; the selected original is always discarded. */
    suspend fun loadAvatarForCrop(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val name = importImage(uri)
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(directory, name))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val edge = maxOf(info.size.width, info.size.height)
                if (edge > 2048) decoder.setTargetSampleSize((edge + 2047) / 2048)
            }
        } finally { withContext(NonCancellable) { deleteImage(name) } }
    }

    suspend fun saveAvatarCrop(bitmap: Bitmap, left: Int, top: Int, side: Int): String = withContext(Dispatchers.IO) {
        require(side > 0 && left >= 0 && top >= 0 && left + side <= bitmap.width && top + side <= bitmap.height)
        check(directory.isDirectory || directory.mkdirs())
        val target = File(directory, "${UUID.randomUUID()}.img")
        val square = Bitmap.createBitmap(bitmap, left, top, side, side)
        val output = if (side > 1024) Bitmap.createScaledBitmap(square, 1024, 1024, true) else square
        try {
            target.outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            if (target.length() > maxBytes) throw IOException("Image is too large")
            target.name
        } catch (failure: Exception) {
            target.delete()
            throw failure
        } finally {
            if (output !== square) output.recycle()
            if (square !== bitmap) square.recycle()
        }
    }
}
