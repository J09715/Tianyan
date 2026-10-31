package top.tianyan.app.ui.settings

import kotlin.math.roundToInt

internal data class AvatarCropBounds(val left: Int, val top: Int, val side: Int)

data class AvatarCrop(val x: Float = 0.5f, val y: Float = 0.5f, val zoom: Float = 1f) {
    internal fun bounds(width: Int, height: Int): AvatarCropBounds {
        require(width > 0 && height > 0)
        val side = (minOf(width, height) / zoom.coerceIn(1f, 4f)).roundToInt().coerceIn(1, minOf(width, height))
        return AvatarCropBounds(((width - side) * x.coerceIn(0f, 1f)).roundToInt(),
            ((height - side) * y.coerceIn(0f, 1f)).roundToInt(), side)
    }

    internal fun transform(width: Int, height: Int, viewport: Int, dx: Float, dy: Float, scale: Float): AvatarCrop {
        if (viewport <= 0) return this
        val current = bounds(width, height)
        val nextZoom = (zoom * scale).coerceIn(1f, 4f)
        val next = copy(zoom = nextZoom).bounds(width, height)
        // Keep the crop center stationary while zooming, then translate in source-image coordinates.
        val left = current.left + (current.side - next.side) / 2f - dx * current.side / viewport
        val top = current.top + (current.side - next.side) / 2f - dy * current.side / viewport
        return AvatarCrop(
            if (width == next.side) 0.5f else (left / (width - next.side)).coerceIn(0f, 1f),
            if (height == next.side) 0.5f else (top / (height - next.side)).coerceIn(0f, 1f), nextZoom)
    }
}
