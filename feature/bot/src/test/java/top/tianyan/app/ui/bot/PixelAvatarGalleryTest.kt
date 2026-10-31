package top.tianyan.app.ui.bot

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Test
import top.tianyan.app.core.model.bot.PixelAvatarArt
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

/** Render the actual production pixels as a contact sheet for visual review. */
class PixelAvatarGalleryTest {
    @Test fun renderGallery() {
        val gallery = BufferedImage(6 * 128, 3 * 128, BufferedImage.TYPE_INT_ARGB)
        val canvas = gallery.createGraphics()
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            repeat(18) { i ->
                val recipe = PixelAvatarRecipe(kind = i % 3, hair = i % 4, eyes = i % 3, mouth = (i / 3) % 3,
                    outfit = (i / 3) % 3, accessory = (i / 4) % 4, skinColor = i % 5,
                    hairColor = i % 8, outfitColor = (i + 2) % 8, backdrop = i % 6)
                val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
                image.setRGB(0, 0, 32, 32, PixelAvatarArt.cells(recipe), 0, 32)
                canvas.drawImage(image, i % 6 * 128, i / 6 * 128, 128, 128, null)
            }
        } finally { canvas.dispose() }
        val file = File("build/outputs/pixel-avatar-preview.png")
        file.parentFile?.mkdirs()
        check(ImageIO.write(gallery, "png", file))
    }
}
