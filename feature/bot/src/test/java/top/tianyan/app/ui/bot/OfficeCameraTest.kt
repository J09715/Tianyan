package top.tianyan.app.ui.bot

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeCameraTest {
    @Test
    fun `fit zoom reveals the whole room on a wide viewport`() {
        val metrics = metrics(960, 540)
        val fitScale = 540f / PixelOfficeScene.HEIGHT
        assertEquals(fitScale / 5f, officeFitZoom(960, 540, 5f), 0.0001f)
        assertEquals(officeFitZoom(960, 540, 5f), metrics.fitZoom, 0.0001f)

        metrics.fitToView()

        assertEquals(metrics.fitZoom, metrics.zoom.floatValue, 0.0001f)
        assertEquals(metrics.fitZoom, metrics.minZoom, 0.0001f)
        assertTrue(metrics.isFitted)
        val mapW = PixelOfficeRoom.WIDTH * metrics.scale
        val mapH = PixelOfficeScene.HEIGHT * metrics.scale
        assertTrue(mapW <= 960f)
        assertTrue(mapH <= 540f)
        assertTrue(metrics.panX.floatValue >= 0f)
        assertTrue(metrics.panY.floatValue >= 0f)
        assertTrue(metrics.panX.floatValue + mapW <= 960.0001f)
        assertTrue(metrics.panY.floatValue + mapH <= 540.0001f)

        val fittedZoom = metrics.zoom.floatValue
        metrics.zoomAbout(0.5f, Offset(480f, 270f))
        assertEquals(fittedZoom, metrics.zoom.floatValue, 0.0001f)
    }

    @Test
    fun `zoom controls stay within fit and max bounds`() {
        val metrics = metrics(960, 540)
        metrics.fitToView()

        metrics.zoomBy(2f)
        assertFalse(metrics.isFitted)
        assertEquals(metrics.fitZoom * 2f, metrics.zoom.floatValue, 0.0001f)

        repeat(6) { metrics.zoomBy(2f) }
        assertEquals(OFFICE_MAX_ZOOM, metrics.zoom.floatValue, 0.0001f)

        metrics.recenter()
        assertEquals(1f, metrics.zoom.floatValue, 0.0001f)
        assertFalse(metrics.isFitted)
    }

    private fun metrics(viewW: Int, viewH: Int): OfficeMetrics = OfficeMetrics().apply {
        panX = mutableFloatStateOf(0f)
        panY = mutableFloatStateOf(0f)
        zoom = mutableFloatStateOf(1f)
        updateViewport(
            viewW,
            viewH,
            maxOf(
                viewW / PixelOfficeRoom.WIDTH.toFloat(),
                viewH / PixelOfficeScene.HEIGHT.toFloat(),
            ),
        )
    }
}
