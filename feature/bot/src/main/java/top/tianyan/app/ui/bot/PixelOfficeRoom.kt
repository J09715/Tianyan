package top.tianyan.app.ui.bot

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Cutaway pixel office: a lobby window over stacked think, meeting, work, coffee and rest floors.
 * Palette values are artwork, like [top.tianyan.app.core.model.bot.PixelAvatarArt].
 */
internal object PixelOfficeRoom {
    const val WIDTH = 192
    const val HEIGHT = 448
    const val WALL = 0xFFF3E4C8.toInt()
    const val FLOOR = 0xFFE4B56A.toInt()
    const val TRIM = 0xFF5C4030.toInt()
    const val WOOD = 0xFF8B5A2B.toInt()

    private const val WALL_SHADE = 0xFFE7D3B0.toInt()
    private const val FLOOR_B = 0xFFF0C888.toInt()
    private const val FLOOR_LINE = 0xFFC4894A.toInt()
    private const val INK = 0xFF3A2A1E.toInt()
    private const val CREAM = 0xFFFFF8EE.toInt()
    private const val WOOD_DARK = 0xFF6B3E1E.toInt()
    private const val MONITOR = 0xFF243044.toInt()
    private const val PLANT = 0xFF3E8F4A.toInt()
    private const val PLANT_DARK = 0xFF2D6B36.toInt()
    private const val POT = 0xFFC46A3A.toInt()
    private const val SKY = 0xFF8EC8F0.toInt()
    private const val SKY_DEEP = 0xFF5FA4D6.toInt()
    private const val CITY = 0xFF4E7198.toInt()
    private const val CITY_DARK = 0xFF35567A.toInt()
    private const val WINDOW_LIT = 0xFFF6E27A.toInt()
    private const val SOFA = 0xFFE8873A.toInt()
    private const val SOFA_DARK = 0xFFC46A28.toInt()
    private const val BEAN = 0xFF6BBF6A.toInt()
    private const val CUP = 0xFFF7F4EE.toInt()
    private const val STICKY_P = 0xFFF0A3B5.toInt()
    private const val STICKY_B = 0xFF9FD0E8.toInt()
    private const val STICKY_Y = 0xFFF3D36B.toInt()
    private const val BOOK_R = 0xFFC45C4A.toInt()
    private const val BOOK_B = 0xFF3D6B8C.toInt()
    private const val BOOK_Y = 0xFFE0B15A.toInt()
    private const val BOOK_P = 0xFF6B4C7A.toInt()
    private const val CHAIR = 0xFF3E4A5C.toInt()

    fun pixels(includeShell: Boolean = true, scene: OfficeSceneSpec = OfficeSceneSpec.Default): IntArray {
        val pen = PixelPen(IntArray(WIDTH * HEIGHT), WIDTH, HEIGHT, scene)
        if (includeShell) pen.shell()
        pen.lobby()
        pen.thinkFloor()
        pen.workFloor()
        pen.restFloor()
        return pen.pixels
    }

    private class PixelPen(val pixels: IntArray, val width: Int, val height: Int, val scene: OfficeSceneSpec) {
        fun shell() {
            rect(0, 0, width, 8, TRIM)
            rect(0, 8, width, 54, WALL)
            rect(0, 62, width, 6, WOOD)
            level(68, 108)
            rect(0, 176, width, 6, WOOD)
            level(182, 128)
            rect(0, 310, width, 6, WOOD)
            level(316, height - 316)
            rect(0, height - 6, width, 6, TRIM)
        }

        fun lobby() {
            shelf(8, 16)
            plant(36, 28)
            rect(56, 16, 52, 24, INK)
            rect(58, 18, 48, 20, WOOD)
            window(118, 12, 68, 42)
        }

        fun thinkFloor() {
            whiteboard(8, 84)
            meetingTable(108, 108)
            plant(174, 124)
        }

        fun workFloor() {
            scene.desks.forEach { workstation(it.x, it.y, it.screenColor) }
            plant(96, 236)
        }

        fun restFloor() {
            sofa(14, 352)
            table(74, 368)
            bean(108, 360)
            plant(134, 356)
            stairs(8, 400)
        }

        private fun level(top: Int, span: Int) {
            rect(0, top, width, span, WALL)
            val floorTop = top + span - 36
            var y = floorTop
            var band = 0
            while (y < top + span) {
                rect(0, y, width, 8, if (band % 2 == 0) FLOOR else FLOOR_B)
                rect(0, y, width, 1, FLOOR_LINE)
                y += 8
                band++
            }
            rect(0, top, width, 10, WALL_SHADE)
        }

        private fun plaque(x: Int, y: Int) {
            rect(x, y, 34, 10, INK)
            rect(x + 2, y + 2, 30, 6, WOOD)
        }

        private fun shelf(x: Int, y: Int) {
            rect(x, y, 24, 36, WOOD_DARK)
            rect(x + 2, y + 2, 20, 32, WOOD)
            books(x + 3, y + 4)
            books(x + 3, y + 18)
        }

        private fun books(x: Int, y: Int) {
            val spines = intArrayOf(BOOK_R, BOOK_Y, BOOK_B, BOOK_P, BOOK_R, CREAM)
            spines.forEachIndexed { index, color -> rect(x + index * 3, y, 2, 10, color) }
        }

        private fun window(x: Int, y: Int, w: Int, h: Int) {
            rect(x, y, w, h, TRIM)
            rect(x + 2, y + 2, w - 4, h - 4, SKY)
            rect(x + 2, y + h - 16, w - 4, 12, SKY_DEEP)
            rect(x + 6, y + h - 22, 10, 16, CITY_DARK)
            rect(x + 18, y + h - 28, 14, 22, CITY)
            rect(x + 34, y + h - 18, 12, 12, CITY_DARK)
            rect(x + 48, y + h - 26, 12, 20, CITY)
            lit(x + 10, y + h - 16)
            lit(x + 22, y + h - 22)
            lit(x + 38, y + h - 12)
            lit(x + 52, y + h - 20)
            rect(x + w / 2, y + 2, 2, h - 4, TRIM)
            rect(x + 2, y + h / 2, w - 4, 2, TRIM)
        }

        private fun lit(x: Int, y: Int) = rect(x, y, 3, 3, WINDOW_LIT)

        private fun plant(x: Int, y: Int) {
            rect(x + 3, y + 12, 6, 5, POT)
            rect(x, y + 4, 5, 8, PLANT)
            rect(x + 4, y, 5, 10, PLANT_DARK)
            rect(x + 7, y + 5, 5, 7, PLANT)
        }

        private fun whiteboard(x: Int, y: Int) {
            rect(x, y, 78, 36, TRIM)
            rect(x + 2, y + 2, 74, 32, CREAM)
            rect(x + 8, y + 8, 18, 2, STICKY_B)
            rect(x + 8, y + 14, 28, 2, INK)
            rect(x + 8, y + 20, 16, 2, STICKY_P)
            rect(x + 40, y + 8, 22, 14, STICKY_Y)
            rect(x + 44, y + 12, 14, 2, INK)
            rect(x + 44, y + 16, 10, 2, INK)
        }

        private fun meetingTable(x: Int, y: Int) {
            chair(x + 6, y - 8)
            chair(x + 28, y - 8)
            chair(x + 50, y - 8)
            rect(x, y, 70, 14, WOOD)
            rect(x, y, 70, 3, WOOD_DARK)
            rect(x + 8, y + 14, 3, 8, WOOD_DARK)
            rect(x + 58, y + 14, 3, 8, WOOD_DARK)
            rect(x + 30, y + 4, 8, 6, CUP)
            chair(x + 6, y + 18)
            chair(x + 28, y + 18)
            chair(x + 50, y + 18)
        }

        private fun chair(x: Int, y: Int) {
            rect(x, y, 10, 8, CHAIR)
            rect(x + 1, y + 1, 8, 3, 0xFF5C6B80.toInt())
            rect(x + 1, y + 8, 1, 3, INK)
            rect(x + 8, y + 8, 1, 3, INK)
        }

        private fun workstation(x: Int, y: Int, screen: Int) {
            rect(x + 17, y - 19, 17, 14, MONITOR)
            rect(x + 19, y - 17, 13, 9, screen)
            rect(x + 23, y - 5, 3, 4, INK)
            rect(x, y, 36, 3, WOOD)
            rect(x, y + 2, 36, 1, WOOD_DARK)
            rect(x + 2, y + 3, 2, 15, WOOD_DARK)
            rect(x + 31, y + 3, 2, 15, WOOD_DARK)
        }

        private fun coffeeBar(x: Int, y: Int) {
            rect(x, y, 68, 22, WOOD)
            rect(x, y, 68, 3, WOOD_DARK)
            rect(x + 4, y - 20, 16, 20, INK)
            rect(x + 6, y - 16, 12, 8, 0xFF2A2E36.toInt())
            rect(x + 8, y - 14, 3, 3, 0xFFE85D4C.toInt())
            rect(x + 13, y - 14, 3, 3, WINDOW_LIT)
            rect(x + 26, y + 6, 6, 5, CUP)
            rect(x + 36, y + 6, 6, 5, 0xFFC45C4A.toInt())
            rect(x + 46, y + 5, 8, 6, CREAM)
            rect(x + 18, y + 22, 10, 8, CHAIR)
            rect(x + 40, y + 22, 10, 8, CHAIR)
        }

        private fun sofa(x: Int, y: Int) {
            rect(x, y, 52, 22, SOFA)
            rect(x, y, 6, 22, SOFA_DARK)
            rect(x + 46, y, 6, 22, SOFA_DARK)
            rect(x + 8, y + 4, 16, 8, 0xFFF6C07A.toInt())
            rect(x + 28, y + 4, 16, 8, 0xFFF6C07A.toInt())
            rect(x + 4, y + 22, 4, 4, WOOD_DARK)
            rect(x + 44, y + 22, 4, 4, WOOD_DARK)
        }

        private fun table(x: Int, y: Int) {
            rect(x, y, 26, 8, WOOD)
            rect(x + 4, y + 8, 3, 8, WOOD_DARK)
            rect(x + 19, y + 8, 3, 8, WOOD_DARK)
            rect(x + 8, y + 2, 6, 4, CUP)
        }

        private fun bean(x: Int, y: Int) {
            rect(x + 2, y + 6, 16, 12, BEAN)
            rect(x, y + 10, 20, 8, 0xFF4E9A52.toInt())
            rect(x + 6, y + 4, 8, 4, BEAN)
        }

        private fun poster(x: Int, y: Int) {
            rect(x, y, 28, 36, TRIM)
            rect(x + 2, y + 2, 24, 32, CREAM)
            rect(x + 6, y + 8, 16, 2, INK)
            rect(x + 6, y + 14, 12, 2, STICKY_B)
            rect(x + 6, y + 20, 16, 2, STICKY_P)
            rect(x + 6, y + 26, 8, 2, STICKY_Y)
        }

        private fun stairs(x: Int, y: Int) {
            var step = 0
            while (step < 5) {
                rect(x + step * 6, y + step * 6, 28 - step * 4, 6, WOOD)
                rect(x + step * 6, y + step * 6, 28 - step * 4, 2, WOOD_DARK)
                step++
            }
        }

        private fun rect(x0: Int, y0: Int, w: Int, h: Int, color: Int) {
            val left = x0.coerceIn(0, width)
            val top = y0.coerceIn(0, height)
            val right = (x0 + w).coerceIn(0, width)
            val bottom = (y0 + h).coerceIn(0, height)
            var y = top
            while (y < bottom) {
                var x = left
                val row = y * width
                while (x < right) {
                    pixels[row + x] = color
                    x++
                }
                y++
            }
        }
    }
}

internal fun IntArray.toPixelImage(width: Int, height: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(this, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}
