package top.tianyan.app.ui.bot

import androidx.compose.ui.graphics.ImageBitmap
import top.tianyan.app.core.model.bot.BotAvatarState

/** 16×16 world-unit mood mark drawn at 3× so it matches the office art density. */
internal object PixelOfficeMood {
    const val EDGE = 16
    const val BITMAP_EDGE = EDGE * 3

    private const val INK = 0xFF3A2A1E.toInt()
    private const val YELLOW = 0xFFFFD54F.toInt()
    private const val GLOW = 0xFFFFF3C4.toInt()
    private const val RED = 0xFFE53935.toInt()
    private const val GREEN = 0xFF43A047.toInt()
    private const val SAND = 0xFFE0B15A.toInt()
    private const val BLUE = 0xFF4FC3F7.toInt()
    private const val WHITE = 0xFFFFF8EE.toInt()

    fun cells(state: BotAvatarState, frame: Int, waiting: Boolean, yawn: Boolean): IntArray {
        val p = Pen()
        when {
            waiting -> hourglass(p, frame)
            state == BotAvatarState.THINKING -> when (frame) {
                0 -> bulb(p)
                1 -> gear(p, frame)
                else -> question(p)
            }
            state == BotAvatarState.EXECUTING -> sparks(p, frame)
            state == BotAvatarState.BLOCKED || state == BotAvatarState.FAILED -> if (frame != 1) bang(p)
            state == BotAvatarState.SUCCESS -> check(p)
            yawn && state == BotAvatarState.IDLE -> zzz(p, frame)
        }
        return p.pixels
    }

    private val images = HashMap<String, ImageBitmap?>()

    fun image(state: BotAvatarState, frame: Int, waiting: Boolean, yawn: Boolean): ImageBitmap? {
        val index = frame.coerceIn(0, 2)
        val key = "$state|$index|$waiting|$yawn"
        if (images.containsKey(key)) return images[key]
        val painted = cells(state, index, waiting, yawn)
        val bitmap = if (painted.any { it != 0 }) painted.toPixelImage(BITMAP_EDGE, BITMAP_EDGE) else null
        images[key] = bitmap
        return bitmap
    }

    private fun bulb(p: Pen) {
        p.r(18, 9, 12, 3, INK)
        p.r(15, 12, 18, 15, INK)
        p.r(18, 13, 12, 13, YELLOW)
        p.r(21, 14, 6, 8, GLOW)
        p.r(22, 15, 3, 2, WHITE)
        p.r(18, 27, 12, 2, INK)
        p.r(20, 29, 8, 3, INK)
        p.r(20, 32, 8, 2, 0xFF8D6E63.toInt())
        p.r(24, 5, 3, 4, INK)
        p.r(25, 6, 1, 2, YELLOW)
        p.r(12, 15, 3, 3, YELLOW)
        p.r(9, 12, 2, 6, INK)
        p.r(34, 15, 4, 2, YELLOW)
        p.r(38, 12, 2, 6, INK)
        p.r(16, 24, 2, 2, 0xFFF6C558.toInt())
        p.r(30, 24, 2, 2, 0xFFF6C558.toInt())
    }

    private fun gear(p: Pen, frame: Int) {
        val shift = if (frame % 2 == 0) 0 else 1
        val cx = 24
        val cy = 24 + shift
        p.r(cx - 4, cy - 14, 8, 6, INK)
        p.r(cx - 4, cy + 8, 8, 6, INK)
        p.r(cx - 14, cy - 4, 6, 8, INK)
        p.r(cx + 8, cy - 4, 6, 8, INK)
        p.r(cx - 11, cy - 11, 6, 6, INK)
        p.r(cx + 5, cy - 11, 6, 6, INK)
        p.r(cx - 11, cy + 5, 6, 6, INK)
        p.r(cx + 5, cy + 5, 6, 6, INK)
        p.r(cx - 10, cy - 10, 20, 20, INK)
        p.r(cx - 7, cy - 7, 14, 14, YELLOW)
        p.r(cx - 3, cy - 3, 6, 6, INK)
        p.r(cx - 1, cy - 1, 2, 2, 0xFF8D6E63.toInt())
        p.r(cx - 6, cy - 6, 4, 2, GLOW)
    }

    private fun question(p: Pen) {
        p.r(15, 6, 18, 6, INK)
        p.r(17, 8, 14, 3, BLUE)
        p.r(30, 9, 6, 12, INK)
        p.r(32, 10, 3, 9, BLUE)
        p.r(24, 18, 9, 6, INK)
        p.r(26, 19, 6, 3, BLUE)
        p.r(21, 21, 6, 9, INK)
        p.r(23, 22, 3, 6, BLUE)
        p.r(21, 33, 6, 6, INK)
        p.r(23, 34, 3, 3, BLUE)
        p.r(18, 8, 5, 1, WHITE)
    }

    private fun sparks(p: Pen, frame: Int) {
        p.r(18, 15, 6, 21, INK)
        p.r(12, 12, 18, 6, INK)
        p.r(20, 15, 3, 17, 0xFFB0BEC5.toInt())
        p.r(14, 13, 12, 3, 0xFFCFD8DC.toInt())
        val left = 6 + frame * 9
        p.r(left, 8, 3, 3, YELLOW)
        p.r(left + 1, 6, 1, 2, WHITE)
        p.r(left + 4, 11, 2, 2, WHITE)
        val right = 37 - frame * 8
        p.r(right, 22, 3, 3, YELLOW)
        p.r(right + 1, 20, 1, 2, WHITE)
        p.r(33, 6, 2, 2, 0xFFFFF176.toInt())
    }

    private fun bang(p: Pen) {
        p.r(20, 4, 9, 27, INK)
        p.r(22, 6, 5, 22, RED)
        p.r(24, 7, 1, 12, 0xFFFF8A80.toInt())
        p.r(20, 34, 9, 9, INK)
        p.r(22, 35, 5, 6, RED)
    }

    private fun check(p: Pen) {
        p.r(8, 24, 6, 7, INK)
        p.r(13, 29, 7, 7, INK)
        p.r(19, 23, 7, 7, INK)
        p.r(25, 16, 7, 7, INK)
        p.r(31, 9, 7, 7, INK)
        p.r(10, 24, 4, 4, GREEN)
        p.r(14, 28, 4, 4, GREEN)
        p.r(20, 22, 4, 4, GREEN)
        p.r(26, 15, 4, 4, GREEN)
        p.r(32, 9, 4, 4, GREEN)
        p.r(34, 9, 2, 2, 0xFFA5D6A7.toInt())
    }

    private fun hourglass(p: Pen, frame: Int) {
        p.r(9, 4, 30, 5, INK)
        p.r(9, 39, 30, 5, INK)
        p.r(15, 9, 18, 8, INK)
        p.r(20, 17, 8, 12, INK)
        p.r(15, 31, 18, 8, INK)
        p.r(18, 10, 12, 6, SAND)
        p.r(20, 18, 8, 4, SAND)
        if (frame == 0) {
            p.r(18, 31, 12, 6, SAND)
        } else {
            p.r(21, 32, 6, 4, SAND)
        }
        if (frame == 2) p.r(23, 28, 2, 2, SAND) else p.r(22, 25, 2, 2, SAND)
        p.r(12, 6, 2, 2, WHITE)
    }

    private fun zzz(p: Pen, frame: Int) {
        val rise = frame * 5
        p.r(24, 24 - rise, 12, 3, INK)
        p.r(26, 26 - rise, 8, 2, BLUE)
        p.r(31, 20 - rise, 3, 3, INK)
        p.r(31, 22 - rise, 8, 2, INK)
        p.r(32, 23 - rise, 6, 1, BLUE)
        p.r(32, 27 - rise, 9, 2, INK)
        p.r(33, 28 - rise, 7, 1, BLUE)
        p.r(15, 32 - rise, 8, 2, INK)
        p.r(16, 33 - rise, 6, 1, BLUE)
    }

    private class Pen {
        val pixels = IntArray(BITMAP_EDGE * BITMAP_EDGE)

        fun r(x: Int, y: Int, w: Int, h: Int, color: Int) {
            for (row in y.coerceAtLeast(0) until (y + h).coerceAtMost(BITMAP_EDGE)) {
                for (col in x.coerceAtLeast(0) until (x + w).coerceAtMost(BITMAP_EDGE)) {
                    pixels[row * BITMAP_EDGE + col] = color
                }
            }
        }
    }
}
