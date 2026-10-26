package top.tianyan.app.core.model.bot

/** Original 32×32 bust artwork, rendered from data without platform graphics or external assets. */
object PixelAvatarArt {
    const val EDGE = 32
    val skinColors = listOf(0xffffd7b5, 0xffeeb98d, 0xffcb8b63, 0xff925c46, 0xff5a3d35).map { it.toInt() }
    val hairColors = listOf(0xff302c40, 0xff6a4335, 0xffb77338, 0xffe9c36c,
        0xffe6e3ed, 0xff647bd3, 0xffb36eaa, 0xff508f88).map { it.toInt() }
    val outfitColors = listOf(0xff687cce, 0xff489c91, 0xffbf6684, 0xffd19449,
        0xff7770a9, 0xff54627a, 0xffb34f55, 0xffe0d5ba).map { it.toInt() }
    val backdropColors = listOf(0xffe9e4f3, 0xffdceee9, 0xfff5e4df,
        0xffe0e8f5, 0xfff2ebd5, 0xffe8e7eb).map { it.toInt() }
    private val ink = 0xff2d293b.toInt()
    private val white = 0xfffff5e8.toInt()

    fun cells(
        recipe: PixelAvatarRecipe,
        state: BotAvatarState = BotAvatarState.IDLE,
        frame: Int = 0,
    ): IntArray {
        val pixels = IntArray(EDGE * EDGE) { backdropColors[recipe.backdrop] }
        fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) {
            for (row in y.coerceAtLeast(0) until (y + h).coerceAtMost(EDGE)) {
                for (col in x.coerceAtLeast(0) until (x + w).coerceAtMost(EDGE)) pixels[row * EDGE + col] = color
            }
        }
        val skin = skinColors[recipe.skinColor]
        val hair = hairColors[recipe.hairColor]
        val shirt = outfitColors[recipe.outfitColor]
        val shadow = shade(skin, 0.82f)

        // Back hair behind neck and shoulders.
        if (recipe.kind == 0 && recipe.hair in 1..2) {
            rect(7, 8, 18, if (recipe.hair == 2) 20 else 15, ink)
            rect(8, 9, 16, if (recipe.hair == 2) 18 else 13, hair)
        } else if (recipe.kind == 0 && recipe.hair == 4) {
            rect(8, 8, 16, 18, ink)
            rect(9, 9, 14, 16, hair)
        }

        // Torso / Neck base
        rect(8, 25, 16, 7, ink)
        rect(5, 28, 22, 4, ink)
        rect(9, 26, 14, 6, shirt)
        rect(6, 29, 20, 3, shirt)
        rect(6, 30, 3, 2, shade(shirt, 0.8f))
        rect(23, 30, 3, 2, shade(shirt, 0.8f))
        rect(13, 22, 6, 5, ink)
        rect(14, 22, 4, 5, skin)

        // Outfit patterns
        if (recipe.outfit == 1) {
            rect(10, 25, 3, 4, shade(shirt, 0.8f))
            rect(19, 25, 3, 4, shade(shirt, 0.8f))
            rect(12, 28, 1, 3, white)
            rect(19, 28, 1, 3, white)
        } else if (recipe.outfit == 2) {
            rect(12, 26, 8, 3, white)
            rect(13, 29, 6, 3, white)
            rect(15, 27, 2, 5, ink)
        } else if (recipe.outfit == 3) {
            // Traditional Hanfu / Robe (交领右衽 + 领边与佩玉)
            rect(11, 23, 2, 6, white)
            rect(19, 23, 2, 6, white)
            rect(12, 25, 8, 3, shade(shirt, 0.85f))
            rect(13, 28, 4, 4, white)
            rect(16, 28, 4, 4, shade(shirt, 0.85f))
            rect(15, 27, 2, 3, 0xffffd54f.toInt())
        }

        // Stepped face silhouette and ears.
        rect(10, 5, 12, 2, ink)
        rect(8, 7, 16, 14, ink)
        rect(10, 21, 12, 2, ink)
        rect(12, 23, 8, 1, ink)
        rect(9, 8, 14, 12, skin)
        rect(10, 20, 12, 2, skin)
        rect(12, 22, 8, 1, skin)
        rect(6, 13, 3, 5, ink)
        rect(23, 13, 3, 5, ink)
        rect(7, 14, 2, 3, skin)
        rect(23, 14, 2, 3, shadow)
        rect(21, 9, 2, 11, shadow)
        rect(19, 21, 2, 1, shadow)
        rect(15, 17, 2, 2, shadow)

        // Kind features
        if (recipe.kind == 1) {
            // Cat ears
            rect(8, 2, 2, 7, ink); rect(10, 4, 2, 4, ink)
            rect(22, 2, 2, 7, ink); rect(20, 4, 2, 4, ink)
            rect(9, 4, 1, 3, skin); rect(22, 4, 1, 3, skin)
            rect(10, 18, 3, 1, shadow); rect(19, 18, 3, 1, shadow)
        } else if (recipe.kind == 2) {
            // Robot
            rect(15, 2, 2, 4, ink); rect(14, 1, 4, 2, shirt)
            rect(10, 8, 12, 4, hair)
            rect(10, 18, 2, 2, hair); rect(20, 18, 2, 2, hair)
        } else if (recipe.kind == 3) {
            // Cyborg / Mech eye plate
            rect(15, 4, 2, 3, ink); rect(15, 5, 2, 2, 0xffff1744.toInt())
            rect(22, 9, 2, 7, 0xff78909c.toInt())
            rect(9, 5, 14, 4, ink); rect(10, 6, 12, 3, hair)
        } else {
            // Human Hair
            rect(9, 5, 14, 5, ink)
            rect(10, 6, 12, 3, hair)
            rect(8, 9, 3, 5, hair)
            rect(21, 9, 3, 5, hair)
            when (recipe.hair) {
                0 -> { rect(11, 9, 4, 2, hair); rect(15, 9, 3, 1, hair) }
                1 -> { rect(10, 9, 11, 3, hair); rect(15, 11, 2, 2, skin) }
                2 -> { rect(10, 9, 4, 3, hair); rect(20, 10, 2, 8, hair) }
                3 -> {
                    rect(9, 4, 3, 4, hair); rect(14, 3, 3, 5, hair)
                    rect(19, 4, 3, 4, hair); rect(11, 9, 3, 2, hair)
                }
                4 -> {
                    // Topknot / high ponytail
                    rect(14, 1, 4, 4, ink); rect(15, 2, 2, 3, hair)
                    rect(14, 4, 4, 1, shade(shirt, 1.15f))
                    rect(10, 9, 12, 2, hair)
                }
            }
            rect(11, 7, 5, 1, shade(hair, 1.2f))
        }

        // Eyes
        if (recipe.kind == 3) {
            // Cyborg: left eye standard, right eye optic cyan lens
            when (recipe.eyes) {
                1 -> { rect(11, 14, 3, 1, ink); rect(10, 15, 1, 1, ink) }
                else -> { rect(11, 14, 3, 3, ink); rect(11, 14, 1, 1, white) }
            }
            rect(17, 13, 5, 4, ink); rect(18, 14, 3, 2, 0xff00e5ff.toInt()); rect(19, 14, 1, 1, white)
        } else {
            when (recipe.eyes) {
                0 -> {
                    rect(11, 14, 3, 3, ink); rect(18, 14, 3, 3, ink)
                    rect(11, 14, 1, 1, white); rect(18, 14, 1, 1, white)
                }
                1 -> {
                    rect(11, 14, 3, 1, ink); rect(10, 15, 1, 1, ink)
                    rect(18, 14, 3, 1, ink); rect(21, 15, 1, 1, ink)
                }
                2 -> {
                    rect(10, 13, 4, 1, ink); rect(18, 13, 4, 1, ink)
                    rect(12, 15, 2, 2, ink); rect(18, 15, 2, 2, ink)
                }
                3 -> {
                    // Wink
                    rect(11, 15, 3, 1, ink); rect(10, 16, 1, 1, ink)
                    rect(18, 14, 3, 3, ink); rect(18, 14, 1, 1, white)
                }
            }
        }

        // Mouth
        when (recipe.mouth) {
            0 -> { rect(14, 20, 4, 1, ink); rect(13, 19, 1, 1, ink); rect(18, 19, 1, 1, ink) }
            1 -> rect(14, 20, 4, 1, ink)
            2 -> { rect(14, 19, 4, 3, ink); rect(15, 21, 2, 1, 0xffd8838b.toInt()) }
            3 -> {
                // Smirk / cute cat mouth
                rect(13, 20, 2, 1, ink); rect(15, 19, 2, 1, ink); rect(17, 20, 2, 1, ink)
            }
        }

        // Accessories
        when (recipe.accessory) {
            1 -> {
                // Glasses
                rect(10, 13, 5, 1, ink); rect(17, 13, 5, 1, ink)
                rect(10, 17, 5, 1, ink); rect(17, 17, 5, 1, ink)
                rect(10, 14, 1, 3, ink); rect(14, 14, 1, 3, ink)
                rect(17, 14, 1, 3, ink); rect(21, 14, 1, 3, ink); rect(15, 14, 2, 1, ink)
            }
            2 -> {
                // Headphones
                rect(7, 7, 2, 11, ink); rect(23, 7, 2, 11, ink)
                rect(9, 5, 14, 2, shirt); rect(5, 13, 3, 6, shirt); rect(24, 13, 3, 6, shirt)
            }
            3 -> {
                // Cap
                rect(9, 5, 14, 4, ink); rect(10, 6, 12, 3, shirt); rect(8, 9, 17, 2, shirt)
            }
            4 -> {
                // Official Scholar Hat (国风乌纱帽 / 展翅官帽)
                rect(11, 1, 10, 5, ink); rect(12, 2, 8, 3, 0xff1e1a29.toInt())
                rect(4, 4, 7, 2, ink); rect(21, 4, 7, 2, ink)
                rect(15, 3, 2, 2, 0xffe53935.toInt())
            }
            5 -> {
                // Cyber Goggles (赛博护目镜)
                rect(6, 8, 20, 2, ink)
                rect(8, 7, 16, 3, 0xff00e5ff.toInt()); rect(9, 8, 14, 1, white)
            }
            6 -> {
                // ID Badge (工牌挂绳)
                rect(13, 23, 1, 4, 0xffe53935.toInt()); rect(18, 23, 1, 4, 0xffe53935.toInt())
                rect(14, 27, 4, 4, white); rect(15, 28, 2, 2, 0xff1976d2.toInt())
            }
        }

        // State animations & situational overlays
        when (state) {
            BotAvatarState.THINKING -> {
                // Thought bubble / rotating gear in top corner
                if (frame % 2 == 0) {
                    rect(25, 2, 2, 2, white); rect(27, 0, 4, 4, 0xffffd54f.toInt())
                    rect(28, 1, 2, 2, ink)
                } else {
                    rect(25, 2, 2, 2, 0xffffd54f.toInt()); rect(27, 1, 4, 3, 0xffffd54f.toInt())
                    rect(28, 1, 2, 2, white)
                }
            }
            BotAvatarState.EXECUTING -> {
                // Mini green terminal screen & typing hands at bottom
                rect(12, 28, 8, 4, 0xff263238.toInt())
                rect(13, 29, 6, 2, 0xff00e676.toInt())
                if (frame % 2 == 0) {
                    rect(11, 29, 2, 2, skin)
                } else {
                    rect(19, 29, 2, 2, skin)
                }
            }
            BotAvatarState.BLOCKED -> {
                // Sweat drop on right cheek
                rect(23, 11, 2, 3, 0xff0288d1.toInt()); rect(24, 10, 1, 1, 0xff0288d1.toInt())
                rect(23, 12, 1, 1, white)
                rect(3, 2, 2, 4, 0xffe53935.toInt()); rect(3, 7, 2, 2, 0xffe53935.toInt())
            }
            BotAvatarState.SUCCESS -> {
                // Rosy cheeks & sparkle star
                rect(8, 17, 3, 1, 0xffff8a80.toInt()); rect(21, 17, 3, 1, 0xffff8a80.toInt())
                rect(3, 3, 1, 3, 0xffffd54f.toInt()); rect(2, 4, 3, 1, 0xffffd54f.toInt())
                rect(27, 4, 1, 3, 0xffffd54f.toInt()); rect(26, 5, 3, 1, 0xffffd54f.toInt())
            }
            BotAvatarState.FAILED -> {
                // Red cross at top corner
                rect(26, 2, 1, 1, 0xffe53935.toInt()); rect(28, 2, 1, 1, 0xffe53935.toInt())
                rect(27, 3, 1, 1, 0xffe53935.toInt())
                rect(26, 4, 1, 1, 0xffe53935.toInt()); rect(28, 4, 1, 1, 0xffe53935.toInt())
            }
            else -> {}
        }

        return pixels
    }

    private fun shade(color: Int, factor: Float): Int {
        fun channel(shift: Int) = (((color ushr shift) and 255) * factor).toInt().coerceIn(0, 255)
        return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
