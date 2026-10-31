package top.tianyan.app.harness.groupchat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import top.tianyan.app.core.model.bot.GroupAttachment
import top.tianyan.app.runtime.ElfInspector
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.rootfs.RootfsValidator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotAttachmentPreparerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also {
        it.applicationInfo.nativeLibraryDir = it.cacheDir.absolutePath
    }
    private val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
    private val preparer = BotAttachmentPreparer(context, paths)

    private fun provider(file: File, mime: String): Uri {
        val provider = FileProvider(file, mime)
        provider.attachInfo(context, ProviderInfo().apply { authority = "bot-test" })
        ShadowContentResolver.registerProviderInternal("bot-test", provider)
        return Uri.parse("content://bot-test/input")
    }

    @Test fun `images reach the image parameter and files receive guest readable paths`() = runBlocking {
        val file = File(context.cacheDir, "image.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val uri = provider(file, "image/png")
        val prepared = preparer.prepare(listOf(GroupAttachment("../../image.png", uri.toString())))
        assertTrue(prepared.description.contains("/attachments/bot-input/"))
        assertFalse(prepared.description.contains("content://"))
        assertFalse(prepared.description.contains("../"))
        val image = prepared.imageUrls.single()
        assertTrue(image.startsWith("data:image/png;base64,"))
        assertArrayEquals(file.readBytes(), Base64.decode(image.substringAfter(','), Base64.DEFAULT))
        val path = prepared.description.substringAfter("：/attachments/").trim()
        assertArrayEquals(file.readBytes(), File(paths.attachmentsDir, path).readBytes())
    }

    @Test fun `actual streamed size is bounded even when metadata lies`() = runBlocking {
        val file = File(context.cacheDir, "large.dat")
        RandomAccessFile(file, "rw").use { it.setLength(21L * 1024 * 1024) }
        val uri = provider(file, "application/octet-stream")
        val root = File(paths.attachmentsDir, "bot-input")
        val existing = root.listFiles().orEmpty().map { it.name }.toSet()
        try {
            preparer.prepare(listOf(GroupAttachment("large.dat", uri.toString(), sizeBytes = 1)))
            fail("oversized input must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(existing, root.listFiles().orEmpty().map { it.name }.toSet())
    }

    private class FileProvider(private val file: File, private val mime: String) : ContentProvider() {
        override fun onCreate() = true
        override fun getType(uri: Uri) = mime
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
