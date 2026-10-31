package top.tianyan.app.core.datastore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBitmapFactory
import top.tianyan.app.core.model.ChatBackgroundStyle

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ChatBackgroundImageStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun requireValidImageData() { ShadowBitmapFactory.setAllowInvalidImageData(false) }

    @Test fun selectedImageIsCopiedAndOnlyItsOwnedFileCanBeRemoved() = runBlocking {
        val bytes = ByteArrayOutputStream().apply {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, this)
        }.toByteArray()
        val directory = temporary.newFolder("backgrounds")
        val store = ChatBackgroundImageStore(directory, { ByteArrayInputStream(bytes) })
        val name = store.importImage(Uri.parse("content://picker/selected"))
        assertTrue(ChatBackgroundStyle.isValidImageName(name))
        assertArrayEquals(bytes, File(directory, name).readBytes())
        val outside = File(temporary.root, "outside").apply { writeText("keep") }
        store.deleteImage("../outside")
        assertTrue(outside.exists())
        store.deleteImage(name)
        assertFalse(File(directory, name).exists())
    }

    @Test fun invalidOrOversizedImportsLeaveNoPartialFilesAndPreserveExistingImages() = runBlocking {
        val directory = temporary.newFolder("backgrounds")
        val previous = File(directory, "12345678-1234-1234-1234-123456789abc.img").apply { writeText("previous") }
        for (maxBytes in listOf(4L, 100L)) {
            val store = ChatBackgroundImageStore(directory, { ByteArrayInputStream("not an image".toByteArray()) }, maxBytes)
            try { store.importImage(Uri.parse("content://picker/invalid")); fail("Invalid input was accepted") }
            catch (_: IOException) { }
            assertEquals(listOf(previous.name), directory.listFiles()!!.map { it.name })
            assertEquals("previous", previous.readText())
        }
    }

    @Test fun avatarCropSavesASquareWithoutRecyclingThePreview() = runBlocking {
        val directory = temporary.newFolder("avatars")
        val store = ChatBackgroundImageStore(directory, { null })
        val preview = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888)
        val name = store.saveAvatarCrop(preview, left = 4, top = 2, side = 6)
        val saved = BitmapFactory.decodeFile(File(directory, name).absolutePath)!!
        assertEquals(6, saved.width)
        assertEquals(6, saved.height)
        assertFalse(preview.isRecycled)
        try { store.saveAvatarCrop(preview, 11, 0, 4); fail("Out-of-bounds crop was accepted") }
        catch (_: IllegalArgumentException) { }
        assertEquals(listOf(name), directory.listFiles()!!.map { it.name })
    }
}
