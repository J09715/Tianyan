package top.tianyan.app.feature.a2uipoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架屏呼吸/扫光曲线的纯函数测试（不依赖 Compose 运行时）。
 * 曲线参数与官方 alpha02 ShimmerIndicator 保持一致，升级库版本时这里能兜住视觉回归。
 */
class TianyanA2uiSkeletonTest {

    @Test
    fun `breathing starts at full opacity and dips to the floor at half phase`() {
        assertEquals(1.0f, skeletonBreathingAlpha(breathingPhase = 0f, index = 0), TOLERANCE)
        assertEquals(0.86f, skeletonBreathingAlpha(breathingPhase = 0.5f, index = 0), TOLERANCE)
        assertEquals(1.0f, skeletonBreathingAlpha(breathingPhase = 1f, index = 0), TOLERANCE)
    }

    @Test
    fun `later siblings breathe out of phase`() {
        val first = skeletonBreathingAlpha(breathingPhase = 0f, index = 0)
        val second = skeletonBreathingAlpha(breathingPhase = 0f, index = 1)
        val third = skeletonBreathingAlpha(breathingPhase = 0f, index = 2)
        assertTrue("index 0 应在呼吸峰值: $first", first > second)
        assertTrue("错峰应继续推进: $second > $third", second > third)
    }

    @Test
    fun `breathing alpha stays inside the shimmer band`() {
        val samples = generateSequence(-0.5f) { it + 0.0137f }.take(512).toList()
        samples.forEach { phase ->
            (0..5).forEach { index ->
                val alpha = skeletonBreathingAlpha(phase, index)
                assertTrue("phase=$phase index=$index alpha=$alpha 低于下限", alpha >= 0.859f)
                assertTrue("phase=$phase index=$index alpha=$alpha 高于上限", alpha <= 1.0f)
            }
        }
    }

    @Test
    fun `highlight radius expands to cover the container and never collapses`() {
        assertEquals(1f, skeletonHighlightRadius(sweepProgress = 0f, maxDimension = 100f), TOLERANCE)
        assertEquals(100f, skeletonHighlightRadius(sweepProgress = 0.5f, maxDimension = 100f), TOLERANCE)
        assertEquals(200f, skeletonHighlightRadius(sweepProgress = 1f, maxDimension = 100f), TOLERANCE)
        // 进度越界也钳在 0..1，避免动画重启瞬间出现负半径
        assertEquals(200f, skeletonHighlightRadius(sweepProgress = 1.7f, maxDimension = 100f), TOLERANCE)
        assertEquals(1f, skeletonHighlightRadius(sweepProgress = -0.3f, maxDimension = 100f), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.001f
    }
}