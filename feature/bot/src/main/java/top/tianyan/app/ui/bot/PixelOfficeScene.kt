package top.tianyan.app.ui.bot

/** Remove only empty walkways; furniture and characters retain the same pixel scale. */
internal object PixelOfficeScene {
    private val gaps = listOf(150 until 172, 290 until 306, 436 until 442)
    const val HEIGHT = PixelOfficeRoom.HEIGHT - 44

    fun y(worldY: Float): Float =
        worldY - gaps.sumOf { (worldY - it.first).coerceIn(0f, it.count().toFloat()).toDouble() }.toFloat()

    fun pixels(scene: OfficeSceneSpec = OfficeSceneSpec.Default): IntArray {
        val source = PixelOfficeDetail.pixels(scene)
        val width = PixelOfficeDetail.WIDTH
        val scale = PixelOfficeDetail.SCALE
        val result = IntArray(width * HEIGHT * scale)
        var dest = 0
        for (row in 0 until PixelOfficeDetail.HEIGHT) {
            if (gaps.any { row / scale in it }) continue
            source.copyInto(result, dest * width, row * width, (row + 1) * width)
            dest++
        }
        return result
    }
}
