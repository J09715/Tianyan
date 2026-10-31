package top.tianyan.app.ui.bot

/**
 * Three art pixels per world unit. World coordinates stay stable for seats, gestures and hit targets.
 * Opaque, deterministic pixel art is rendered once, never in the animation loop.
 */
internal object PixelOfficeDetail {
    const val SCALE = 3
    const val WIDTH = PixelOfficeRoom.WIDTH * SCALE
    const val HEIGHT = PixelOfficeRoom.HEIGHT * SCALE

    fun pixels(scene: OfficeSceneSpec = OfficeSceneSpec.Default): IntArray {
        val base = PixelOfficeRoom.pixels(scene = scene)
        val pixels = IntArray(WIDTH * HEIGHT) { i ->
            base[(i / WIDTH / SCALE) * PixelOfficeRoom.WIDTH + i % WIDTH / SCALE]
        }
        Painter(pixels, scene).paint()
        return pixels
    }

    internal class Painter(val pixels: IntArray, val scene: OfficeSceneSpec = OfficeSceneSpec.Default) {
        val ink = 0x493C39
        val wood = 0xB47B46
        val light = 0xE6B674
        val paper = 0xF8F0DC
        val green = 0x37714B

        fun r(x: Number, y: Number, w: Number, h: Number, color: Int) {
            val left = (x.toFloat() * SCALE).toInt().coerceIn(0, WIDTH)
            val top = (y.toFloat() * SCALE).toInt().coerceIn(0, HEIGHT)
            val right = ((x.toFloat() + w.toFloat()) * SCALE).toInt().coerceIn(0, WIDTH)
            val bottom = ((y.toFloat() + h.toFloat()) * SCALE).toInt().coerceIn(0, HEIGHT)
            for (row in top until bottom) for (col in left until right) {
                pixels[row * WIDTH + col] = color or 0xFF000000.toInt()
            }
        }

        fun paint() {
            architecture()
            val furniture = PixelOfficeRoom.pixels(includeShell = false, scene = scene)
            for (i in pixels.indices) {
                val color = furniture[(i / WIDTH / SCALE) * PixelOfficeRoom.WIDTH + i % WIDTH / SCALE]
                if (color != 0) pixels[i] = color
            }
            skyline()
            board()
            scene.desks.forEach { desk(it.x, it.y) }
            meeting()
            restCoffee()
            workWall()
            lounge()
            for ((x, y) in listOf(36 to 28, 174 to 124,
                96 to 236, 134 to 356, 4 to 122, 4 to 270, 172 to 412)) plant(x, y)
            shelf(8, 16, 24, 36)
            shelf(8, 340, 17, 10)
            lobbyArt()
            lamp(157, 68, 20)
            lamp(100, 182, 16)
            lamp(92, 316, 20)
            r(56, 16, 52, 36, ink)
            r(57, 17, 50, 0.7, wood)
            r(58, 50, 46, 0.5, wood)
            r(62, 49, 3, 0.5, paper)
            clock(94, 88)
            hallway()
            serverRack(136, 410)
            accessories()
        }

        fun architecture() {
            for ((top, bottom) in listOf(8 to 62, 68 to 126, 182 to 220, 316 to 372)) {
                for (y in top until bottom) {
                    val shade = if (y - top < 7) 0xDDC7A5 else if (y - top < 14) 0xE7D3B2 else 0xEFDFC2
                    r(0, y, 192, 1, shade)
                }
                for (x in listOf(1, 103, 190)) {
                    r(x, top, 1.3, bottom - top, 0x98785B)
                    r(x + 1.3, top, 0.7, bottom - top, 0xF7EACD)
                }
                r(0, top + 9, 192, 0.5, 0xDCC6A2)
                r(0, top + 9.5, 192, 0.5, 0xF8EED8)
                for (x in listOf(52, 148)) r(x, top + 10, 0.4, bottom - top - 12, 0xD9C3A0)
                r(0, bottom - 3, 192, 3, 0x9A724E)
                r(0, bottom - 3, 192, 0.7, light)
                r(0, bottom - 2.5, 192, 0.5, 0xC9A87E)
            }
            for ((top, bottom) in listOf(126 to 176, 220 to 310, 372 to 442)) {
                for (y in top until bottom step 6) {
                    r(0, y, 192, 6, if (y % 12 == 0) 0xD5A269 else 0xDCAC73)
                    r(0, y, 192, 0.5, 0xAC794E)
                    for (x in -20..192 step 24) {
                        val offset = if ((y - top) % 12 == 0) 0 else 12
                        r(x + offset, y, 0.5, 6, 0xB58456)
                        r(x + offset + 3, y + 2, 13, 0.35, 0xE9BD85)
                        r(x + offset + 9, y + 4, 10, 0.35, 0xC79560)
                    }
                }
            }
            for (y in listOf(62, 176, 310, 442)) {
                r(0, y, 192, 1, 0xE4B47C)
                r(0, y + 1, 192, 2, 0x94603E)
                r(0, y + 3, 192, 3, 0x604637)
            }
        }

        fun skyline() {
            r(118, 12, 68, 45, ink)
            for (y in 14..53) r(120, y, 64, 1, if (y < 34) 0x80BEE5 else 0xAAD8E9)
            for ((x, y) in listOf(124 to 22, 153 to 18, 171 to 29)) {
                r(x, y, 10, 2, 0xEEF6E9); r(x + 2, y - 2, 5, 3, 0xEEF6E9)
            }
            for (i in 0..7) {
                val x = 121 + i * 8
                val top = 33 - (i * 7 % 15)
                r(x, top, 7, 54 - top, if (i % 2 == 0) 0x648DB5 else 0x759EC2)
                r(x + 2, top - 2, 3, 2, 0x759EC2)
                for (y in top + 3..51 step 5) for (dx in listOf(1, 4)) {
                    r(x + dx, y, 1.5, 2, if ((y + i) % 3 == 0) 0xF4DB9A else 0xB7D8DF)
                }
            }
            r(142, 14, 1.3, 40, ink); r(165, 14, 1.3, 40, ink)
            r(120, 38, 64, 1, ink)
            r(119, 55, 66, 2, light); r(117, 57, 70, 2, wood)
            plant(173, 41)
        }

        fun board() {
            r(9, 85, 78, 37, 0xC4B294)
            r(8, 84, 78, 36, ink); r(9, 85, 76, 34, 0xB7B9AC)
            r(10, 86, 74, 31, 0xFAF5E8)
            for ((x, y) in listOf(17 to 91, 35 to 102, 57 to 102)) {
                r(x, y, 17, 7, 0x67839C); r(x + 0.7, y + 0.7, 15.6, 5.6, 0xEEF1E9)
                r(x + 3, y + 2, 9, 0.7, 0x71889A); r(x + 4, y + 4, 6, 0.7, 0x71889A)
            }
            r(25, 98, 0.7, 3, 0x67839C); r(25, 100, 41, 0.7, 0x67839C)
            r(43, 100, 0.7, 2, 0x67839C); r(65, 100, 0.7, 2, 0x67839C)
            for ((x, y) in listOf(13 to 105, 72 to 90, 27 to 111, 74 to 111)) {
                r(x + 0.5, y + 0.5, 4, 4, 0xDDC9A7)
                r(x, y, 4, 4, if (x % 2 == 0) 0xE9A5AE else 0xEFCE72)
            }
            r(10, 118, 74, 1, wood); r(16, 117, 7, 1, 0x46657F)
            r(76, 116, 3, 2, 0xAA6956)
        }

        fun desk(x: Int, y: Int) {
            r(x, y + 17, 37, 2, 0xBB915F)
            r(x + 1, y, 34, 2, light)
            r(x + 2, y + 3, 31, 0.5, wood)
            r(x + 3, y + 9, 1, 8, light); r(x + 32, y + 9, 1, 8, light)
            r(x + 17, y - 20, 18, 15, ink); r(x + 18, y - 19, 16, 13, 0x77818C)
            r(x + 19, y - 18, 14, 11, 0x253E57)
            r(x + 19, y - 18, 14, 1, 0xB0CDD6)
            for (i in 0..4) {
                r(x + 20 + i % 2, y - 15 + i * 1.5, 4 + i % 3, 0.7, if (i % 2 == 0) 0x8FCEB2 else 0xDAAF82)
            }
            r(x + 29, y - 15, 3, 7, 0x597A9A)
            r(x + 30, y - 14, 1, 2, 0x92BDD1)
            r(x + 24, y - 5, 3, 3, ink); r(x + 21, y - 2, 11, 1, ink)
            r(x + 12, y - 1.7, 9, 1.3, 0xD2CDBB)
            for (i in 0..3) r(x + 13 + i * 2, y - 1.3, 0.7, 0.5, 0x757E84)
            r(x + 1, y - 10, 4, 10, 0x596470); r(x + 1.5, y - 9, 3, 7, 0xA9B2B3)
            r(x + 2, y - 8, 2, 0.5, ink); r(x + 2, y - 3, 0.7, 0.7, 0x97D9C0)
            cup(x + 31, y - 4)
        }

        fun lobbyArt() {
            r(35, 9, 18, 17, ink)
            r(36, 10, 16, 15, 0x8D6849)
            r(37, 11, 14, 13, 0xEAF1E4)
            r(37, 12, 14, 5, 0x8EC8F0)
            r(38, 18, 12, 5, 0x9FC6A0)
            r(40, 16, 4, 3, 0x6F8FA8)
            r(44, 15, 2, 2, 0xF6F0E4)
            r(46, 13, 3, 3, 0xF3D36B)
            r(37, 11, 14, 0.4, 0xFFF8EE)
            r(36, 10, 0.5, 15, 0xC4A47C)
        }

        fun clock(x: Int, y: Int) {
            r(x, y, 14, 14, ink)
            r(x + 1, y + 1, 12, 12, 0x604637)
            r(x + 2, y + 2, 10, 10, 0xF6EFD8)
            r(x + 2, y + 2, 10, 0.5, 0xFDF6E6)
            r(x + 3, y + 7, 1, 0.4, ink)
            r(x + 7, y + 3, 0.4, 1, ink)
            r(x + 11, y + 7, 1, 0.4, ink)
            r(x + 7, y + 10, 0.4, 1, ink)
            r(x + 5, y + 8, 4, 0.4, 0x9E8A70)
            r(x + 1, y + 1, 0.5, 0.5, 0xD8C7A6)
            r(x + 12.5, y + 1, 0.5, 0.5, 0xD8C7A6)
            r(x + 1, y + 12.5, 0.5, 0.5, 0xD8C7A6)
            r(x + 12.5, y + 12.5, 0.5, 0.5, 0xD8C7A6)
        }

        fun plant(x: Int, y: Int) {
            r(x + 2, y + 16, 9, 1, 0xB69970)
            r(x + 3, y + 12, 6, 5, 0xA9673E); r(x + 4, y + 12, 2, 4, 0xD69754)
            r(x + 2.5, y + 12, 7, 1, 0xE6AE65)
            r(x + 5.5, y + 2, 1, 10, 0x456444)
            for (i in 0..5) {
                val lx = x + if (i % 2 == 0) 1 else 6
                val ly = y + 2 + i * 1.5
                r(lx, ly, 4, 2.5, green); r(lx + 1, ly - 1, 2, 1, green)
                r(lx + 0.7, ly, 2, 0.7, 0x75A365)
            }
            r(x + 5, y, 2, 5, 0x4D8A52)
        }

        fun books(x: Int, y: Int, count: Int) {
            val colors = listOf(0x648997, 0xC57C61, 0xD8B879, 0x7B9270, 0x8A7994)
            for (i in 0 until count) {
                val h = 5 + i % 3
                r(x + i * 2.5, y - h, 2, h, colors[i % colors.size])
                r(x + i * 2.5 + 0.4, y - h + 1, 0.4, h - 2, 0xE8D4AC)
            }
        }

        fun shelf(x: Int, y: Int, w: Int, h: Int) {
            r(x, y, w, h, ink); r(x + 1, y + 1, w - 2, h - 2, 0x8D6849)
            for (sy in y + 9 until y + h step 11) {
                books(x + 2, sy, (w - 4) / 3)
                r(x, sy, w, 1.5, light); r(x, sy + 1.5, w, 1, 0x694833)
            }
            r(x, y, 1, h, light)
        }

        fun lamp(x: Int, y: Int, drop: Int) {
            r(x, y, 0.7, drop, ink); r(x - 2, y + drop, 5, 2, ink)
            r(x - 4, y + drop + 2, 9, 2, ink)
            r(x - 3, y + drop + 4, 7, 1, 0xFFE2A0)
            r(x - 2, y + drop + 5, 5, 1, 0xF6DBA7)
        }

        fun cup(x: Int, y: Int) {
            r(x, y, 3, 4, paper); r(x + 2, y + 1, 2, 2, paper)
            r(x + 3, y + 1.5, 0.6, 0.8, wood); r(x + 0.5, y + 0.3, 2, 0.7, 0x765242)
        }

        fun meeting() {
            r(119, 86, 57, 20, ink); r(120, 87, 55, 18, 0xB4C6CA)
            r(121, 88, 53, 16, 0xF2F0E2)
            for (i in 0..3) r(124, 91 + i * 3, 20 - i * 2, 0.7, 0x7494A6)
            for (i in 0..3) r(149 + i * 5, 99 - i * 2, 3, 3 + i * 2, 0x7EACA9)
            r(108, 108, 70, 2, light); r(110, 110, 66, 0.7, wood)
            for (x in listOf(114, 157)) {
                r(x, 105, 11, 7, ink); r(x + 1, 106, 9, 5, 0x8EACB5)
                r(x - 1, 112, 13, 1, 0xCCC8B7)
            }
            // Hot-desk laptops on the meeting table's near edge.
            for ((x, screen) in listOf(112 to 0x7EC8E3, 134 to 0x8FD18A, 156 to 0xC9A6E8)) {
                r(x, 111, 11, 2, ink)
                r(x + 1, 107, 9, 4, ink)
                r(x + 2, 107.7, 7, 2.6, screen)
                r(x + 3, 108.2, 3, 0.4, 0xE6F7FF)
            }
            cup(105, 110); r(147, 110, 6, 3, paper)
        }

        fun restCoffee() {
            // Lounge coffee bar on the right wall.
            r(146, 348, 42, 18, ink)
            r(147, 349, 40, 16, wood)
            r(147, 349, 40, 1, light)
            for (x in listOf(150, 164, 178)) {
                r(x, 352, 12, 12, 0xA3764E); r(x + 1, 353, 10, 10, 0xB88B5E)
                r(x + 6, 357, 2, 1, ink)
            }
            r(146, 346, 42, 2, light)
            scene.coffeeMachines.forEach { coffeeMachine(it) }
            // Chalk menu board.
            r(170, 330, 20, 14, ink)
            r(171, 331, 18, 12, 0x243B33)
            r(172, 333, 14, 0.7, 0xE8DCC0)
            r(172, 336, 16, 0.7, 0xCFE3D2)
            r(172, 339, 10, 0.7, 0xE8DCC0)
            r(180, 333, 6, 5, 0xE8DCC0); r(181, 334, 4, 3, 0x8B5A2B)
            for (x in listOf(148, 158, 168)) cup(x, 344)
            for (x in listOf(154, 176)) {
                r(x, 370, 12, 2, ink); r(x + 1, 372, 1, 9, ink); r(x + 10, 372, 1, 9, ink)
                r(x + 1, 378, 10, 0.7, ink)
            }
        }

        fun workWall() {
            val notes = listOf(0xF3D36B, 0xF0A3B5, 0x9FD0E8, 0xBFE3A8)
            notes.forEachIndexed { i, color ->
                val x = 48 + i * 12
                val y = 184 + (i % 2)
                r(x + 0.6, y + 0.8, 8, 8, 0xB99B76)
                r(x, y, 8, 8, color)
                r(x + 1, y + 1, 5, 0.5, 0xFFFFFF)
                r(x + 1, y + 3.5, 4, 0.5, 0x5C4030)
                r(x + 1, y + 5, 5, 0.5, 0x5C4030)
                r(x + 3.5, y + 0.3, 1, 1, 0xC94F4F)
            }
            r(100, 185, 8, 7, 0xD8C7A6)
            r(100, 185, 8, 0.5, 0xF6EBD2)
            r(101.5, 187, 5, 0.5, 0x8A7A64)
            r(101.5, 188.5, 4, 0.5, 0x8A7A64)
        }

        fun serverRack(x: Int, y: Int) {
            r(x, y, 14, 32, ink)
            r(x + 1, y + 1, 12, 29, 0x2C3440)
            r(x + 2, y + 2, 10, 3, 0x101820)
            r(x + 3, y + 3, 8, 1, 0x7EC8E3)
            for (i in 0..4) {
                val row = y + 7 + i * 4
                r(x + 2, row, 10, 2, 0x1E2630)
                r(x + 3, row + 0.5, 5, 1, 0x4E5B66)
                r(x + 9, row + 0.5, 2, 1, 0x5C6B80)
            }
            r(x + 2, y + 28, 2, 2, 0x1A2028)
            r(x + 10, y + 28, 2, 2, 0x1A2028)
            r(x + 13, y + 14, 1, 16, 0x1A2028)
            r(x + 13, y + 25, 2, 0.5, 0x1A2028)
        }

        fun accessories() {
            // Contact shadows follow the feet, not the desktop: every object sits on the floor.
            for ((x, y, w) in listOf(Triple(109, 138, 66), Triple(15, 377, 50),
                Triple(75, 384, 25), Triple(108, 378, 21),
                Triple(173, 306, 15), Triple(116, 441, 12))) {
                r(x, y, w, 0.7, 0xAD845B)
            }
            scene.desks.forEach { desk ->
                desk.shadow?.let { r(desk.x + it.dx, desk.y + it.dy, it.width, 0.7, 0xAD845B) }
            }
            // Wainscot panels, drawer joints and bevels give the large furniture volume.
            for (x in listOf(111, 133, 155)) {
                r(x, 114, 20, 6, 0x9E6E43)
                r(x + 1, 115, 18, 0.5, 0xC99459)
            }
            for (desk in scene.desks.filter { it.drawers }) {
                val x = desk.x
                val y = desk.y
                r(x + 1, y + 2, 34, 0.7, 0x9E6E43)
                r(x + 3, y + 4, 0.7, 13, 0xC99459)
                r(x + 32, y + 4, 0.7, 13, 0xC99459)
                r(x + 4, y + 15, 27, 0.7, 0x987652)
            }
            r(15, 372, 50, 1, 0xFFC477)
            r(20, 365, 39, 1, 0xFFC477)
            r(39, 354, 0.7, 18, 0xC88741)
            r(15, 354, 4, 17, 0xDA8C3E)
            r(61, 354, 4, 17, 0xDA8C3E)
            r(16, 354, 2, 1, 0xFFD18B)
            r(62, 354, 2, 1, 0xFFD18B)
            r(110, 374, 16, 1, 0x427344)
            r(112, 366, 12, 1, 0x94C477)
            r(114, 365, 8, 1, 0x94C477)
            // A woven rug and low book trolley occupy the otherwise empty lounge.
            r(68, 388, 66, 15, 0xB19069)
            r(69, 389, 64, 13, 0xD6C7A3)
            for (y in 390..401 step 2) r(70, y, 62, 0.35, 0xB7AB8C)
            for (x in 70..132 step 3) r(x, 389, 0.35, 13, 0xEEE0BD)
            r(80, 389, 29, 2, wood)
            r(82, 391, 1, 6, ink); r(106, 391, 1, 6, ink)
            r(81, 397, 27, 1, ink)
            books(85, 389, 8)
            // A small sleeping cat on a cushion by the entry sill.
            scene.cats.filter { it.style == OfficeCatStyle.SLEEPING }.forEach { cat(it) }
        }
    }
}
