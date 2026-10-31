package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarArt
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

/** Front-facing stand, think and sofa poses, plus the torso and arms. */
internal object PixelOfficeSpriteFront {
    fun draw(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        pose: OfficePose,
        state: BotAvatarState,
        frame: Int,
        waiting: Boolean,
        yawn: Boolean,
        idleAction: OfficeIdleAction,
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
        val pantsLight = spriteShade(pants, 1.25f)
        val seated = pose == OfficePose.SIT
        val lift = if (frame == 1) 2 else 0

        backHair(p, recipe, hair)

        p.dy = -lift
        if (seated) {
            p.r(13, 53, 23, 8, INK)
            p.r(15, 54, 19, 6, pants)
            p.r(15, 54, 19, 1, pantsLight)
            p.r(17, 60, 8, 9, INK)
            p.r(27, 60, 8, 9, INK)
            p.r(18, 60, 6, 8, pants)
            p.r(28, 60, 6, 8, spriteShade(pants, 0.9f))
            p.r(16, 67, 10, 4, SHOE)
            p.r(26, 67, 10, 4, SHOE)
            p.r(17, 68, 3, 1, CHAIR_LIGHT)
            p.r(27, 68, 3, 1, CHAIR_LIGHT)
            p.r(16, 70, 10, 1, SOLE)
            p.r(26, 70, 10, 1, SOLE)
        } else {
            p.r(14, 53, 21, 14, INK)
            p.r(16, 54, 7, 12, pants)
            p.r(25, 54, 7, 12, pants)
            p.r(16, 54, 2, 12, pantsLight)
            p.r(29, 54, 3, 12, spriteShade(pants, 0.78f))
            p.r(23, 56, 2, 10, spriteShade(pants, 0.66f))
            val leftStep = if (frame == 1) 1 else 0
            val rightStep = if (frame == 2) 1 else 0
            p.r(13, 65 + leftStep, 10, 5, SHOE)
            p.r(25, 65 + rightStep, 10, 5, SHOE)
            p.r(14, 66 + leftStep, 3, 1, CHAIR_LIGHT)
            p.r(26, 66 + rightStep, 3, 1, CHAIR_LIGHT)
            p.r(13, 69 + leftStep, 10, 1, SOLE)
            p.r(25, 69 + rightStep, 10, 1, SOLE)
        }

        if (recipe.outfit == 1) {
            p.r(13, 32, 22, 8, INK)
            p.r(15, 33, 18, 5, shirtShade)
            p.r(18, 36, 12, 2, spriteShade(shirt, 0.82f))
        }
        if (recipe.outfit == 3) {
            p.r(13, 48, 22, 4, INK)
            p.r(14, 48, 20, 2, spriteShade(shirt, 0.84f))
            p.r(24, 49, 2, 2, 0xFF3E8F4A.toInt())
        }

        p.r(11, 36, 26, 19, INK)
        p.r(13, 37, 22, 17, shirt)
        p.r(13, 38, 2, 15, shirtLight)
        p.r(32, 38, 3, 15, shirtShade)
        p.r(17, 40, 14, 1, spriteShade(shirt, 0.88f))
        neckAndCollar(p, recipe, skin, skinShade, shirt, shirtLight, shirtShade)

        armsFront(p, recipe, pose, state, waiting, frame, skin, skinShade, shirt, shirtShade, shirtLight, idleAction)

        if (recipe.accessory == 6) {
            p.r(19, 38, 1, 9, RED)
            p.r(28, 38, 1, 9, RED)
            p.r(21, 46, 7, 6, WHITE)
            p.r(22, 47, 5, 4, 0xFF1976D2.toInt())
            p.r(23, 48, 3, 1, WHITE)
        }

        ears(p, skin, skinShade)
        p.r(20, 33, 9, 4, INK)
        p.r(22, 33, 5, 4, skinShade)
        PixelOfficeSpriteHead.draw(p, recipe, state, frame, waiting, yawn, skin, skinShade, skinDeep, hair, hairShade, hairLight, shirt, shirtShade, shirtLight)
        PixelOfficeSpriteHead.face(p, recipe, state, frame, waiting, yawn, skinShade)
        PixelOfficeSpriteHead.accessories(p, recipe, hair, hairShade, shirt, shirtShade, shirtLight)
        stateDetails(p, state, waiting)
        p.dy = 0
    }

    private fun backHair(p: SpritePen, recipe: PixelAvatarRecipe, hair: Int) {
        if (recipe.kind != 0) return
        when (recipe.hair) {
            1 -> {
                p.r(10, 10, 28, 30, INK)
                p.r(12, 12, 24, 26, hair)
            }
            2 -> {
                p.r(8, 10, 32, 43, INK)
                p.r(10, 12, 28, 39, hair)
            }
            4 -> {
                p.r(11, 8, 26, 31, INK)
                p.r(13, 10, 22, 27, hair)
            }
        }
    }

    private fun neckAndCollar(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        skin: Int,
        skinShade: Int,
        shirt: Int,
        shirtLight: Int,
        shirtShade: Int,
    ) {
        p.r(20, 32, 9, 6, INK)
        p.r(22, 33, 5, 4, skinShade)
        when (recipe.outfit) {
            1 -> {
                p.r(18, 37, 13, 3, shirtShade)
                p.r(21, 38, 7, 2, skinShade)
                p.r(19, 39, 1, 8, WHITE)
                p.r(28, 39, 1, 8, WHITE)
                p.r(19, 47, 2, 1, WHITE)
                p.r(27, 47, 2, 1, WHITE)
            }
            2 -> {
                p.r(20, 37, 9, 12, WHITE)
                p.r(22, 37, 5, 3, skinShade)
                p.r(23, 40, 3, 9, INK)
                p.r(24, 40, 1, 8, RED)
                p.r(15, 37, 5, 8, shirtLight)
                p.r(29, 37, 5, 8, shirtShade)
                p.r(18, 44, 2, 2, shirtShade)
                p.r(29, 44, 2, 2, shirtLight)
            }
            3 -> {
                p.r(18, 37, 3, 8, WHITE)
                p.r(21, 43, 3, 4, WHITE)
                p.r(28, 37, 3, 8, spriteWhiteWithAlpha())
                p.r(25, 43, 3, 4, WHITE)
                p.r(21, 38, 6, 3, spriteShade(shirt, 0.86f))
                p.r(14, 48, 21, 2, shirtShade)
                p.r(18, 50, 14, 3, spriteShade(shirt, 0.92f))
                p.r(25, 50, 2, 3, 0xFF3E8F4A.toInt())
            }
            else -> {
                p.r(19, 37, 11, 3, spriteShade(shirt, 0.88f))
                p.r(22, 38, 5, 2, skinShade)
                p.r(18, 41, 3, 1, shirtLight)
            }
        }
    }

    private fun armsFront(
        p: SpritePen,
        recipe: PixelAvatarRecipe,
        pose: OfficePose,
        state: BotAvatarState,
        waiting: Boolean,
        frame: Int,
        skin: Int,
        skinShade: Int,
        shirt: Int,
        shirtShade: Int,
        shirtLight: Int,
        idleAction: OfficeIdleAction,
    ) {
        val sway = if (frame == 2) 1 else 0
        val success = state == BotAvatarState.SUCCESS
        val upset = state == BotAvatarState.BLOCKED || waiting
        val idle = state == BotAvatarState.IDLE
        when {
            upset -> {
                p.r(3, 35, 8, 8, INK)
                p.r(4, 36, 6, 6, shirt)
                p.r(4, 40, 6, 2, shirtShade)
                p.r(2, 31, 6, 6, INK)
                p.r(3, 32, 4, 4, skin)
                p.r(6, 33, 1, 3, skinShade)
                p.r(37, 35, 8, 8, INK)
                p.r(38, 36, 6, 6, shirt)
                p.r(38, 40, 6, 2, shirtShade)
                p.r(40, 31, 6, 6, INK)
                p.r(41, 32, 4, 4, skin)
                p.r(41, 33, 1, 3, skinShade)
            }
            success -> {
                p.r(7, 38, 7, 13, INK)
                p.r(8, 39, 5, 11, shirt)
                p.r(8, 49, 5, 2, shirtShade)
                p.r(8, 51, 5, 5, INK)
                p.r(9, 52, 3, 3, skin)
                p.r(35, 27, 7, 13, INK)
                p.r(36, 28, 5, 11, shirt)
                p.r(36, 37, 5, 2, shirtShade)
                p.r(36, 22, 6, 8, INK)
                p.r(37, 23, 4, 5, skin)
                p.r(40, 23, 1, 4, skinShade)
                p.r(37, 21, 2, 2, (if (frame == 0) LAMP_YELLOW else WHITE))
            }
            pose == OfficePose.THINK -> {
                p.r(7, 38, 7, 13, INK)
                p.r(8, 39, 5, 11, shirt)
                p.r(8, 49, 5, 2, shirtShade)
                p.r(8, 51, 5, 5, INK)
                p.r(9, 52, 3, 3, skin)
                p.r(33, 36, 7, 10, INK)
                p.r(34, 37, 5, 8, shirt)
                p.r(33, 30, 7, 8, INK)
                p.r(34, 31, 5, 6, shirtLight)
                p.r(32, 26, 7, 7, INK)
                p.r(33, 27, 5, 5, skin)
                p.r(36, 28, 1, 3, skinShade)
                p.r(34, 20, 3, 7, INK)
                p.r(34, 21, 2, 5, RED)
                p.r(35, 20, 1, 2, WHITE)
            }
            idle && idleAction == OfficeIdleAction.SIP -> {
                p.r(6, 38, 7, 12, INK)
                p.r(7, 39, 5, 10, shirt)
                p.r(7, 48, 5, 2, shirtShade)
                p.r(7, 50, 5, 5, INK)
                p.r(8, 51, 3, 3, skin)
                p.r(35, 38, 7, 11, INK)
                p.r(36, 39, 5, 9, shirt)
                p.r(36, 47, 5, 2, shirtShade)
                p.r(34, 46, 8, 6, INK)
                p.r(35, 47, 6, 4, skin)
                p.r(36, 41, 4, 6, INK)
                p.r(37, 42, 3, 4, 0xFFF6F0E4.toInt())
                p.r(37, 43, 3, 1, 0xFF8B5A2B.toInt())
                p.r(38, 39, 1, 2, WHITE)
            }
            idle && idleAction == OfficeIdleAction.READ -> {
                p.r(6, 38, 7, 11, INK)
                p.r(7, 39, 5, 9, shirt)
                p.r(7, 47, 5, 4, INK)
                p.r(8, 48, 3, 2, skin)
                p.r(35, 38, 7, 11, INK)
                p.r(36, 39, 5, 9, shirt)
                p.r(34, 47, 7, 4, INK)
                p.r(35, 48, 5, 2, skin)
                p.r(15, 45, 18, 7, INK)
                p.r(16, 46, 16, 5, 0xFFE8DCC0.toInt())
                p.r(23, 46, 1, 5, 0xFFCDBFA0.toInt())
                p.r(17, 47, 5, 1, 0xFF8AA0A8.toInt())
                p.r(25, 47, 5, 1, 0xFF8AA0A8.toInt())
            }
            idle && idleAction == OfficeIdleAction.STRETCH -> {
                p.r(5, 32, 6, 10, INK)
                p.r(6, 33, 4, 8, shirt)
                p.r(4, 27, 6, 6, INK)
                p.r(5, 28, 4, 4, skin)
                p.r(37, 32, 6, 10, INK)
                p.r(38, 33, 4, 8, shirt)
                p.r(38, 27, 6, 6, INK)
                p.r(39, 28, 4, 4, skin)
                p.r(2, 25, 1, 2, WHITE)
                p.r(45, 25, 1, 2, WHITE)
            }
            idle && idleAction == OfficeIdleAction.PHONE -> {
                p.r(6, 38, 7, 12, INK)
                p.r(7, 39, 5, 10, shirt)
                p.r(7, 48, 5, 2, shirtShade)
                p.r(7, 50, 5, 5, INK)
                p.r(8, 51, 3, 3, skin)
                p.r(35, 38, 7, 12, INK)
                p.r(36, 39, 5, 10, shirt)
                p.r(36, 48, 5, 2, shirtShade)
                p.r(35, 50, 7, 6, INK)
                p.r(36, 51, 5, 4, skin)
                p.r(35, 42, 7, 8, INK)
                p.r(36, 43, 5, 6, KEYBOARD)
                p.r(37, 44, 3, 4, 0xFF4FC3F7.toInt())
                p.r(38, 45, 1, 1, WHITE)
            }
            pose == OfficePose.SIT && idle -> {
                p.r(6, 38, 7, 12, INK)
                p.r(7, 39, 5, 10, shirt)
                p.r(7, 48, 5, 2, shirtShade)
                p.r(7, 50, 5, 5, INK)
                p.r(8, 51, 3, 3, skin)
                p.r(35, 38, 7, 12, INK)
                p.r(36, 39, 5, 10, shirt)
                p.r(36, 48, 5, 2, shirtShade)
                p.r(35, 50, 7, 5, INK)
                p.r(36, 51, 5, 3, skin)
            }
            else -> {
                p.r(7, 38 + sway, 7, 13, INK)
                p.r(8, 39 + sway, 5, 11, shirt)
                p.r(8, 49 + sway, 5, 2, shirtShade)
                p.r(8, 51 + sway, 5, 5, INK)
                p.r(9, 52 + sway, 3, 3, skin)
                p.r(9, 53 + sway, 1, 2, skinShade)
                p.r(35, 38 - sway, 7, 13, INK)
                p.r(36, 39 - sway, 5, 11, shirt)
                p.r(36, 49 - sway, 5, 2, shirtShade)
                p.r(35, 51 - sway, 7, 5, INK)
                p.r(36, 52 - sway, 5, 3, skin)
                p.r(39, 53 - sway, 1, 2, skinShade)
            }
        }
    }

    private fun ears(p: SpritePen, skin: Int, skinShade: Int) {
        p.r(8, 20, 4, 10, INK)
        p.r(9, 22, 2, 7, skin)
        p.r(10, 24, 1, 4, skinShade)
        p.r(36, 20, 4, 10, INK)
        p.r(37, 22, 2, 7, skinShade)
    }


    private fun stateDetails(p: SpritePen, state: BotAvatarState, waiting: Boolean) {
        if (state == BotAvatarState.SUCCESS) {
            p.r(36, 13, 1, 3, LAMP_YELLOW)
            p.r(35, 14, 3, 1, LAMP_YELLOW)
        }
        if (state == BotAvatarState.FAILED) {
            p.r(38, 5, 1, 4, RED)
            p.r(38, 10, 1, 1, RED)
        }
        if (waiting) {
            p.r(7, 29, 1, 2, CYAN)
        }
    }

}
