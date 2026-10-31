package top.tianyan.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class AvatarCropTest {
    @Test fun initialCropIsCenteredAndSquareForBothOrientations() {
        assertEquals(AvatarCropBounds(200, 0, 600), AvatarCrop().bounds(1000, 600))
        assertEquals(AvatarCropBounds(0, 200, 600), AvatarCrop().bounds(600, 1000))
    }

    @Test fun zoomKeepsTheSelectedCenter() {
        val next = AvatarCrop(x = 0f).transform(1000, 600, 300, 0f, 0f, 2f)
        assertEquals(AvatarCropBounds(150, 150, 300), next.bounds(1000, 600))
    }

    @Test fun dragIsScaledFromPreviewPixelsAndStopsAtImageEdges() {
        val moved = AvatarCrop().transform(1000, 600, 300, 50f, 0f, 1f)
        assertEquals(AvatarCropBounds(100, 0, 600), moved.bounds(1000, 600))
        assertEquals(AvatarCropBounds(400, 0, 600),
            moved.transform(1000, 600, 300, -10000f, 10000f, 1f).bounds(1000, 600))
    }

    @Test fun zoomAndTinyImageNeverProduceAnEmptyOrOutOfBoundsCrop() {
        assertEquals(AvatarCropBounds(0, 0, 1), AvatarCrop(1f, 1f, 4f).bounds(1, 1))
        val next = AvatarCrop().transform(400, 400, 300, 10000f, -10000f, 100f)
        assertEquals(AvatarCropBounds(0, 300, 100), next.bounds(400, 400))
    }
}
