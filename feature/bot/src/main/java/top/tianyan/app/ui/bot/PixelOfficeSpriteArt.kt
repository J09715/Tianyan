package top.tianyan.app.ui.bot

// Shared palette and lower-level pen for the three-times-density office sprite.

internal const val INK = 0xFF2A2438.toInt()
internal const val WHITE = 0xFFFFF8EE.toInt()
internal const val SHOE = 0xFF3A322C.toInt()
internal const val SOLE = 0xFF231F1C.toInt()
internal const val BLUSH = 0xFFFF958A.toInt()
internal const val MOUTH = 0xFFC46A6A.toInt()
internal const val YAWNC = 0xFF5C3A3A.toInt()
internal const val CYAN = 0xFF00E5FF.toInt()
internal const val RED = 0xFFE53935.toInt()
internal const val CHAIR = 0xFF37414F.toInt()
internal const val CHAIR_LIGHT = 0xFF5C6B80.toInt()
internal const val KEYBOARD = 0xFF26313C.toInt()
internal const val LAMP_YELLOW = 0xFFFFD54F.toInt()
internal const val METAL = 0xFF78909C.toInt()

/** One art pixel of silhouette separation at three times the old resolution. */
internal class SpritePen {
    var dy = 0
    val pixels = IntArray(PixelOfficeSprite.WIDTH * PixelOfficeSprite.HEIGHT)

    fun r(x: Int, y: Int, w: Int, h: Int, color: Int) {
        val top = y + dy
        for (row in top.coerceAtLeast(0) until (top + h).coerceAtMost(PixelOfficeSprite.HEIGHT)) {
            for (col in x.coerceAtLeast(0) until (x + w).coerceAtMost(PixelOfficeSprite.WIDTH)) {
                pixels[row * PixelOfficeSprite.WIDTH + col] = color
            }
        }
    }

    fun outlined(): IntArray {
        val result = pixels.copyOf()
        for (y in 1 until PixelOfficeSprite.HEIGHT - 1) for (x in 1 until PixelOfficeSprite.WIDTH - 1) {
            val i = y * PixelOfficeSprite.WIDTH + x
            if (pixels[i] == 0 && (pixels[i - 1] != 0 || pixels[i + 1] != 0 ||
                    pixels[i - PixelOfficeSprite.WIDTH] != 0 || pixels[i + PixelOfficeSprite.WIDTH] != 0)) {
                result[i] = INK
            }
        }
        return result
    }
}

internal fun spriteWhiteWithAlpha(): Int = 0xFFF4EBDD.toInt()

internal fun spriteShade(color: Int, factor: Float): Int {
    fun channel(shift: Int) = (((color ushr shift) and 255) * factor).toInt().coerceIn(0, 255)
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

