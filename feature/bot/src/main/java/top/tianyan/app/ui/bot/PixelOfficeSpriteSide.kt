package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarArt
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

/** Desk-side profile: chair, monitor stance and keyboard hand. */
internal object PixelOfficeSpriteSide {
    fun draw(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        state: BotAvatarState,
        frame: Int,
        yawn: Boolean,
    ) {
        val skin = PixelAvatarArt.skinColors[recipe.skinColor]
        val skinShade = spriteShade(skin, 0.86f)
        val skinDeep = spriteShade(skin, 0.72f)
        val hair = PixelAvatarArt.hairColors[recipe.hairColor]
        val hairShade = spriteShade(hair, 0.72f)
        val hairLight = spriteShade(hair, 1.32f)
        val shirt = PixelAvatarArt.outfitColors[recipe.outfitColor]
        val shirtShade = spriteShade(shirt, 0.70f)
        val shirtLight = spriteShade(shirt, 1.20f)
        val pants = spriteShade(shirt, 0.56f)
        val lift = if (frame == 1) 2 else 0

        p.r(4, 22, 11, 35, INK)
        p.r(6, 24, 7, 31, CHAIR)
        p.r(7, 25, 2, 28, CHAIR_LIGHT)
        p.r(9, 51, 31, 5, INK)
        p.r(10, 52, 29, 3, CHAIR)
        p.r(11, 52, 26, 1, CHAIR_LIGHT)
        p.r(21, 56, 4, 11, INK)
        p.r(22, 57, 2, 9, CHAIR)
        p.r(12, 67, 26, 3, INK)
        p.r(14, 67, 22, 1, CHAIR_LIGHT)

        p.dy = -lift
        if (recipe.kind == 0 && recipe.hair == 2) {
            p.r(10, 9, 10, 34, INK)
            p.r(12, 11, 7, 30, hair)
        } else if (recipe.kind == 0 && recipe.hair == 1) {
            p.r(12, 9, 9, 24, INK)
            p.r(14, 11, 6, 20, hair)
        }

        p.r(17, 34, 20, 19, INK)
        p.r(19, 35, 16, 17, shirt)
        p.r(19, 36, 2, 15, shirtLight)
        p.r(32, 37, 3, 15, shirtShade)
        p.r(21, 35, 10, 2, spriteShade(shirt, 0.88f))
        p.r(20, 52, 13, 11, INK)
        p.r(21, 52, 10, 10, pants)
        p.r(22, 52, 2, 9, spriteShade(pants, 1.2f))
        p.r(28, 61, 9, 4, SHOE)
        p.r(29, 62, 3, 1, CHAIR_LIGHT)
        p.r(28, 64, 9, 1, SOLE)

        p.r(18, 9, 21, 2, INK)
        p.r(16, 11, 24, 21, INK)
        p.r(18, 31, 21, 3, INK)
        p.r(22, 34, 11, 1, INK)
        p.r(19, 11, 18, 20, skin)
        p.r(21, 31, 15, 2, skin)
        p.r(33, 15, 4, 15, skinShade)
        p.r(22, 31, 13, 2, skinDeep)
        p.r(22, 20, 3, 6, skinShade)

        if (recipe.kind == 1) {
            p.r(15, 0, 4, 9, INK)
            p.r(17, 2, 3, 7, INK)
            p.r(16, 2, 2, 6, hair)
            p.r(18, 4, 1, 4, BLUSH)
        } else if (recipe.kind == 2) {
            p.r(23, 0, 3, 7, INK)
            p.r(24, 1, 1, 5, hair)
            p.r(23, 0, 3, 2, RED)
            p.r(37, 16, 2, 5, INK)
        } else if (recipe.kind == 3) {
            p.r(17, 4, 22, 4, INK)
            p.r(19, 5, 18, 3, hair)
            p.r(30, 8, 8, 10, INK)
            p.r(31, 9, 6, 8, METAL)
            p.r(35, 11, 1, 4, RED)
        } else {
            p.r(18, 5, 18, 4, INK)
            p.r(20, 6, 14, 3, hair)
            p.r(14, 8, 8, 20, INK)
            p.r(16, 10, 5, 17, hair)
            p.r(20, 6, 6, 1, hairLight)
            when (recipe.hair) {
                1 -> {
                    p.r(14, 13, 20, 4, hair)
                    p.r(18, 17, 6, 2, hair)
                }
                2 -> {
                    p.r(8, 9, 8, 32, INK)
                    p.r(10, 11, 5, 28, hair)
                    p.r(11, 11, 2, 24, hairLight)
                }
                3 -> {
                    p.r(18, 2, 3, 4, hair)
                    p.r(23, 1, 3, 5, hair)
                    p.r(28, 2, 3, 4, hair)
                    p.r(20, 5, 16, 3, hair)
                }
                4 -> {
                    p.r(22, 0, 5, 6, INK)
                    p.r(23, 1, 3, 4, hair)
                    p.r(20, 5, 10, 2, shirtLight)
                }
                else -> {
                    p.r(14, 13, 18, 3, hair)
                    p.r(17, 6, 7, 1, hairLight)
                }
            }
            p.r(30, 9, 4, 5, hairShade)
        }

        val blink = (state == BotAvatarState.THINKING && frame != 0) || yawn
        if (recipe.kind == 3) {
            p.r(29, 19, 11, 8, INK)
            p.r(30, 20, 9, 6, 0xFF102A3A.toInt())
            p.r(31, 22, 7, 2, CYAN)
            p.r(32, 22, 3, 1, WHITE)
        } else if (state == BotAvatarState.FAILED) {
            p.r(31, 20, 1, 1, INK)
            p.r(35, 20, 1, 1, INK)
            p.r(32, 21, 1, 1, INK)
            p.r(34, 21, 1, 1, INK)
            p.r(33, 22, 1, 1, INK)
            p.r(32, 23, 1, 1, INK)
            p.r(34, 23, 1, 1, INK)
            p.r(31, 24, 1, 1, INK)
            p.r(35, 24, 1, 1, INK)
        } else if (blink) {
            p.r(30, 22, 7, 2, INK)
        } else if (recipe.kind == 2) {
            p.r(30, 20, 7, 5, INK)
            p.r(31, 21, 5, 3, CYAN)
            p.r(32, 21, 2, 1, WHITE)
        } else {
            p.r(30, 20, 7, 6, INK)
            p.r(31, 21, 2, 2, WHITE)
            p.r(35, 24, 1, 1, spriteShade(INK, 1.55f))
        }
        p.r(38, 24, 2, 2, skinShade)
        if (state == BotAvatarState.SUCCESS) {
            p.r(30, 27, 5, 2, BLUSH)
            p.r(26, 17, 1, 3, LAMP_YELLOW)
            p.r(25, 18, 3, 1, LAMP_YELLOW)
        }
        if (state == BotAvatarState.BLOCKED) {
            p.r(35, 15, 2, 5, CYAN)
            p.r(36, 13, 1, 2, CYAN)
        }
        when {
            yawn -> {
                p.r(34, 28, 5, 5, INK)
                p.r(35, 30, 3, 2, YAWNC)
            }
            state == BotAvatarState.SUCCESS -> {
                p.r(34, 29, 5, 1, INK)
                p.r(35, 30, 3, 1, MOUTH)
            }
            state == BotAvatarState.BLOCKED || state == BotAvatarState.FAILED -> p.r(35, 29, 3, 1, INK)
            recipe.mouth == 2 -> {
                p.r(34, 28, 5, 4, INK)
                p.r(35, 30, 2, 1, MOUTH)
            }
            else -> p.r(34, 29, 4, 1, INK)
        }

        if (recipe.accessory == 1) {
            p.r(29, 19, 9, 1, INK)
            p.r(29, 26, 9, 1, INK)
            p.r(29, 20, 1, 6, INK)
            p.r(37, 20, 1, 6, INK)
            p.r(31, 21, 1, 1, spriteShade(METAL, 1.4f))
        } else if (recipe.accessory == 2) {
            p.r(25, 6, 3, 17, INK)
            p.r(17, 3, 14, 3, shirt)
            p.r(20, 14, 5, 10, INK)
            p.r(21, 15, 3, 8, shirtShade)
        } else if (recipe.accessory == 3) {
            p.r(20, 5, 16, 3, shirt)
            p.r(19, 8, 22, 2, shirt)
            p.r(19, 9, 22, 1, shirtShade)
            p.r(36, 8, 6, 2, shirt)
        } else if (recipe.accessory == 4) {
            p.r(20, 0, 15, 6, INK)
            p.r(22, 1, 11, 4, 0xFF1E1A29.toInt())
            p.r(36, 4, 5, 2, INK)
            p.r(27, 2, 2, 2, RED)
        } else if (recipe.accessory == 5) {
            p.r(25, 17, 15, 3, INK)
            p.r(26, 17, 13, 2, CYAN)
            p.r(28, 17, 9, 1, WHITE)
        }

        p.r(31, 40, 12, 7, INK)
        p.r(33, 41, 9, 4, shirtLight)
        p.r(33, 44, 8, 2, skin)
        p.r(40, 44, 3, 2, skinShade)
        p.r(39, 47, 9, 2, INK)
        p.r(40, 47, 7, 1, KEYBOARD)
        p.r(41, 48, 1, 1, CYAN)
        p.r(44, 48, 1, 1, CYAN)
        p.dy = 0
    }

}
