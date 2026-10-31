package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarArt
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

/** Head, hair, face, accessories and per-state facial details. */
internal object PixelOfficeSpriteHead {
    fun draw(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        state: BotAvatarState,
        frame: Int,
        waiting: Boolean,
        yawn: Boolean,
        skin: Int,
        skinShade: Int,
        skinDeep: Int,
        hair: Int,
        hairShade: Int,
        hairLight: Int,
        shirt: Int,
        shirtShade: Int,
        shirtLight: Int,
    ) {
        p.r(15, 9, 18, 1, INK)
        p.r(13, 10, 22, 1, INK)
        p.r(12, 11, 24, 20, INK)
        p.r(13, 31, 22, 2, INK)
        p.r(15, 33, 18, 1, INK)
        p.r(15, 10, 18, 1, skin)
        p.r(14, 11, 20, 20, skin)
        p.r(15, 31, 18, 2, skin)
        p.r(31, 14, 3, 15, skinShade)
        p.r(16, 31, 16, 2, skinDeep)
        p.r(33, 20, 2, 7, skinShade)

        if (recipe.kind == 1) {
            p.r(8, 0, 4, 9, INK)
            p.r(10, 2, 3, 7, INK)
            p.r(9, 2, 2, 6, hair)
            p.r(11, 4, 1, 4, BLUSH)
            p.r(36, 0, 4, 9, INK)
            p.r(36, 2, 3, 7, INK)
            p.r(37, 2, 2, 6, hairShade)
            p.r(37, 4, 1, 4, BLUSH)
        } else if (recipe.kind == 2) {
            p.r(23, 0, 3, 7, INK)
            p.r(24, 1, 1, 5, hair)
            p.r(23, 0, 3, 2, RED)
            p.r(9, 16, 2, 5, INK)
            p.r(37, 16, 2, 5, INK)
            p.r(10, 17, 1, 3, hairLight)
            p.r(37, 17, 1, 3, hairShade)
            p.r(13, 5, 22, 4, INK)
            p.r(15, 6, 18, 3, hair)
            p.r(14, 9, 20, 5, hairShade)
            p.r(16, 7, 6, 1, hairLight)
        } else if (recipe.kind == 3) {
            p.r(12, 4, 24, 4, INK)
            p.r(14, 5, 20, 3, hair)
            p.r(27, 8, 10, 11, INK)
            p.r(28, 9, 8, 9, METAL)
            p.r(29, 10, 6, 1, spriteShade(METAL, 1.25f))
            p.r(34, 12, 1, 4, RED)
        } else {
            hairCap(p, recipe, hair, hairShade, hairLight, shirtLight)
        }

        p.r(19, 16, 3, 2, hairShade)
        p.r(18, 18, 2, 1, hairLight)
    }

    private fun hairCap(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        hair: Int,
        hairShade: Int,
        hairLight: Int,
        shirtLight: Int,
    ) {
        when (recipe.hair) {
            3 -> {
                p.r(14, 2, 3, 4, hair)
                p.r(19, 1, 3, 5, hair)
                p.r(24, 2, 3, 4, hair)
                p.r(29, 3, 3, 3, hair)
                p.r(13, 5, 5, 3, hair)
                p.r(18, 5, 4, 3, hair)
                p.r(23, 5, 4, 3, hair)
                p.r(28, 5, 5, 3, hair)
                p.r(13, 8, 22, 7, hair)
                p.r(15, 6, 3, 1, hairLight)
                p.r(20, 4, 2, 1, hairLight)
                p.r(30, 7, 3, 4, hairShade)
            }
            4 -> {
                p.r(22, 0, 5, 6, INK)
                p.r(23, 1, 3, 4, hair)
                p.r(24, 1, 1, 2, hairLight)
                p.r(20, 5, 9, 2, shirtLight)
                p.r(13, 6, 22, 3, INK)
                p.r(15, 7, 18, 2, hair)
                p.r(13, 8, 22, 7, hair)
            }
            else -> {
                p.r(13, 5, 22, 4, INK)
                p.r(12, 8, 24, 7, INK)
                p.r(15, 6, 18, 3, hair)
                p.r(14, 9, 20, 5, hair)
                p.r(16, 6, 7, 1, hairLight)
                p.r(30, 10, 3, 4, hairShade)
            }
        }
        when (recipe.hair) {
            0 -> {
                p.r(14, 14, 7, 3, hair)
                p.r(21, 14, 4, 2, hair)
                p.r(25, 14, 7, 3, hair)
                p.r(22, 16, 3, 1, skinTone(recipe))
                p.r(15, 15, 2, 1, hairLight)
            }
            1 -> {
                p.r(13, 13, 22, 4, hair)
                p.r(15, 17, 5, 2, hair)
                p.r(26, 17, 5, 2, hair)
                p.r(20, 18, 5, 1, skinTone(recipe))
                p.r(14, 14, 3, 1, hairLight)
            }
            2 -> {
                p.r(10, 10, 5, 30, hair)
                p.r(33, 10, 5, 30, hair)
                p.r(11, 10, 2, 26, hairLight)
                p.r(35, 12, 2, 24, hairShade)
                p.r(14, 13, 20, 4, hair)
                p.r(16, 14, 6, 2, hair)
                p.r(27, 14, 6, 2, hair)
            }
            else -> {
                p.r(14, 13, 20, 3, hair)
                p.r(16, 14, 5, 1, hairLight)
            }
        }
    }

    private fun skinTone(recipe: PixelAvatarRecipe): Int = PixelAvatarArt.skinColors[recipe.skinColor]

    fun accessories(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        hair: Int,
        hairShade: Int,
        shirt: Int,
        shirtShade: Int,
        shirtLight: Int,
    ) {
        when (recipe.accessory) {
            1 -> {
                p.r(15, 19, 8, 1, INK)
                p.r(25, 19, 8, 1, INK)
                p.r(15, 26, 8, 1, INK)
                p.r(25, 26, 8, 1, INK)
                p.r(15, 20, 1, 6, INK)
                p.r(22, 20, 1, 6, INK)
                p.r(25, 20, 1, 6, INK)
                p.r(32, 20, 1, 6, INK)
                p.r(23, 21, 2, 1, INK)
                p.r(17, 21, 2, 1, spriteShade(METAL, 1.4f))
                p.r(27, 21, 2, 1, spriteShade(METAL, 1.4f))
            }
            2 -> {
                p.r(8, 7, 3, 18, INK)
                p.r(37, 7, 3, 18, INK)
                p.r(13, 3, 22, 3, shirt)
                p.r(14, 3, 20, 1, shirtLight)
                p.r(5, 14, 5, 11, INK)
                p.r(7, 15, 3, 8, shirtShade)
                p.r(6, 23, 4, 2, spriteShade(shirtShade, 0.7f))
                p.r(38, 14, 5, 11, INK)
                p.r(38, 15, 3, 8, shirtShade)
                p.r(38, 23, 4, 2, spriteShade(shirtShade, 0.7f))
            }
            3 -> {
                p.r(12, 4, 24, 4, INK)
                p.r(14, 5, 20, 3, shirt)
                p.r(10, 8, 28, 3, shirt)
                p.r(10, 10, 28, 1, shirtShade)
                p.r(14, 5, 6, 1, shirtLight)
            }
            4 -> {
                p.r(15, 0, 18, 7, INK)
                p.r(17, 1, 14, 5, 0xFF1E1A29.toInt())
                p.r(4, 5, 13, 3, INK)
                p.r(31, 5, 13, 3, INK)
                p.r(5, 5, 11, 1, 0xFF3A3448.toInt())
                p.r(32, 5, 10, 1, 0xFF3A3448.toInt())
                p.r(23, 2, 2, 2, RED)
            }
            5 -> {
                p.r(9, 13, 31, 3, INK)
                p.r(11, 13, 26, 2, CYAN)
                p.r(13, 13, 20, 1, WHITE)
                p.r(35, 13, 3, 2, spriteShade(CYAN, 0.7f))
            }
            else -> {}
        }
    }

    fun face(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        state: BotAvatarState,
        frame: Int,
        waiting: Boolean,
        yawn: Boolean,
        skinShade: Int,
    ) {
        val blink = (state == BotAvatarState.THINKING && frame != 0) || yawn
        if (recipe.kind == 3) {
            p.r(14, 19, 20, 8, INK)
            p.r(15, 20, 18, 6, 0xFF102A3A.toInt())
            p.r(17, 22, 14, 2, CYAN)
            p.r(18, 22, 4, 1, WHITE)
            p.r(30, 21, 3, 4, RED)
            return
        }
        if (state == BotAvatarState.FAILED) {
            crossEye(p, 15, 20)
            crossEye(p, 25, 20)
        } else if (blink || waiting) {
            p.r(15, 22, 7, 2, INK)
            p.r(26, 22, 7, 2, INK)
            p.r(16, 22, 2, 1, spriteShade(INK, 1.5f))
            p.r(27, 22, 2, 1, spriteShade(INK, 1.5f))
        } else if (recipe.kind == 2) {
            p.r(15, 20, 7, 5, INK)
            p.r(26, 20, 7, 5, INK)
            p.r(16, 21, 5, 3, CYAN)
            p.r(27, 21, 5, 3, CYAN)
            p.r(17, 21, 2, 1, WHITE)
            p.r(28, 21, 2, 1, WHITE)
        } else {
            when (recipe.eyes) {
                1 -> {
                    p.r(15, 22, 6, 1, INK)
                    p.r(14, 21, 1, 1, INK)
                    p.r(26, 22, 6, 1, INK)
                    p.r(31, 21, 1, 1, INK)
                }
                2 -> {
                    p.r(15, 19, 7, 1, INK)
                    p.r(26, 19, 7, 1, INK)
                    p.r(17, 21, 4, 4, INK)
                    p.r(27, 21, 4, 4, INK)
                    p.r(17, 21, 2, 1, WHITE)
                    p.r(27, 21, 2, 1, WHITE)
                }
                3 -> {
                    p.r(15, 22, 6, 1, INK)
                    p.r(14, 21, 1, 1, INK)
                    p.r(26, 20, 6, 6, INK)
                    p.r(27, 21, 2, 2, WHITE)
                    p.r(30, 24, 1, 1, spriteShade(INK, 1.5f))
                }
                else -> {
                    p.r(15, 20, 7, 6, INK)
                    p.r(26, 20, 7, 6, INK)
                    p.r(16, 21, 2, 2, WHITE)
                    p.r(27, 21, 2, 2, WHITE)
                    p.r(19, 24, 2, 1, spriteShade(INK, 1.55f))
                    p.r(30, 24, 2, 1, spriteShade(INK, 1.55f))
                }
            }
        }
        if (state == BotAvatarState.SUCCESS) {
            p.r(13, 27, 5, 2, BLUSH)
            p.r(30, 27, 5, 2, BLUSH)
        }
        if (state == BotAvatarState.BLOCKED) {
            p.r(34, 16, 2, 5, CYAN)
            p.r(35, 14, 1, 2, CYAN)
            p.r(34, 19, 1, 1, WHITE)
        }
        when {
            yawn -> {
                p.r(19, 28, 10, 5, INK)
                p.r(21, 30, 6, 2, YAWNC)
                p.r(22, 30, 2, 1, MOUTH)
            }
            state == BotAvatarState.SUCCESS -> {
                p.r(19, 29, 10, 1, INK)
                p.r(21, 30, 6, 2, INK)
                p.r(22, 31, 4, 1, MOUTH)
            }
            state == BotAvatarState.BLOCKED || state == BotAvatarState.FAILED -> {
                p.r(22, 30, 4, 1, INK)
            }
            recipe.mouth == 0 -> {
                p.r(20, 29, 2, 1, INK)
                p.r(23, 30, 2, 1, INK)
                p.r(26, 29, 2, 1, INK)
                p.r(21, 30, 2, 1, spriteShade(INK, 1.5f))
            }
            recipe.mouth == 1 -> p.r(21, 30, 6, 1, INK)
            recipe.mouth == 2 -> {
                p.r(20, 28, 8, 4, INK)
                p.r(22, 30, 4, 1, MOUTH)
            }
            else -> {
                p.r(19, 30, 2, 1, INK)
                p.r(22, 29, 2, 1, INK)
                p.r(25, 30, 2, 1, INK)
            }
        }
    }

    private fun crossEye(p: SpritePen, x: Int, y: Int) {
        p.r(x, y, 1, 1, INK)
        p.r(x + 5, y, 1, 1, INK)
        p.r(x + 1, y + 1, 1, 1, INK)
        p.r(x + 4, y + 1, 1, 1, INK)
        p.r(x + 2, y + 2, 2, 1, INK)
        p.r(x + 1, y + 3, 1, 1, INK)
        p.r(x + 4, y + 3, 1, 1, INK)
        p.r(x, y + 4, 1, 1, INK)
        p.r(x + 5, y + 4, 1, 1, INK)
    }

}
