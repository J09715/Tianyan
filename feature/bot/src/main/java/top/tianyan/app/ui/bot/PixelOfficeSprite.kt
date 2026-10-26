package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.BotAvatarState
import top.tianyan.app.core.model.bot.PixelAvatarRecipe

/**
 * 16×24 world-unit chibi drawn on a 3× canvas, matching the room's art density.
 * Layout, hit targets and camera math keep using [WORLD_WIDTH] / [WORLD_HEIGHT].
 */
internal object PixelOfficeSprite {
    const val WORLD_WIDTH = 16
    const val WORLD_HEIGHT = 24
    const val ART = 3
    const val WIDTH = WORLD_WIDTH * ART
    const val HEIGHT = WORLD_HEIGHT * ART

    fun cells(
        recipe: PixelAvatarRecipe,
        pose: OfficePose,
        state: BotAvatarState,
        frame: Int,
        waiting: Boolean = false,
        yawn: Boolean = false,
        idleAction: OfficeIdleAction = OfficeIdleAction.NONE,
    ): IntArray {
        val pen = SpritePen()
        val drawn = if (waiting) OfficePose.STAND else pose
        if (drawn == OfficePose.TYPE) PixelOfficeSpriteSide.draw(pen, recipe, state, frame, yawn)
        else PixelOfficeSpriteFront.draw(pen, recipe, drawn, state, frame, waiting, yawn, idleAction)
        return pen.outlined()
    }
}

/** Three cached frames per pose so the office does not allocate a bitmap on every tick. */
internal object PixelOfficeFrames {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap>()

    fun bitmap(
        recipe: PixelAvatarRecipe,
        pose: OfficePose,
        state: BotAvatarState,
        frame: Int,
        waiting: Boolean,
        yawn: Boolean,
        idleAction: OfficeIdleAction = OfficeIdleAction.NONE,
    ): androidx.compose.ui.graphics.ImageBitmap {
        val index = frame.coerceIn(0, 2)
        val key = "${recipe.hashCode()}|$pose|$state|$waiting|$yawn|$idleAction|$index"
        return cache.getOrPut(key) {
            PixelOfficeSprite.cells(recipe, pose, state, index, waiting, yawn, idleAction)
                .toPixelImage(PixelOfficeSprite.WIDTH, PixelOfficeSprite.HEIGHT)
        }
    }
}

