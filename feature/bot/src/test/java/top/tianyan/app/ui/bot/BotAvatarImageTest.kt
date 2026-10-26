package top.tianyan.app.ui.bot

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import android.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BotAvatarImageTest {
    @Test fun `selected photo becomes a bounded portable square and survives source deletion`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val photo = java.io.File.createTempFile("avatar", ".png", context.cacheDir)
        try {
            ImageIO.write(BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB), "png", photo)
            val seed = BotAvatarImage.readFrom(context, Uri.fromFile(photo))
            assertTrue(photo.delete())
            val decoded = requireNotNull(BotAvatarImage.decode(seed))
            assertEquals(decoded.width, decoded.height)
            assertTrue(decoded.width in 1..256)
            assertTrue(seed.length <= 400_000)
        } finally {
            photo.delete()
        }
    }

    @Test fun `default seeds malformed images and oversized payloads fall back without crashing`() {
        assertNull(BotAvatarImage.decode("阿策"))
        assertNull(BotAvatarImage.decode("data:image/png;base64,invalid"))
        assertNull(BotAvatarImage.decode("data:image/png;base64," + "A".repeat(400_000)))
        val output = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB), "png", output)
        assertNull(BotAvatarImage.decode("data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)))
    }
}
