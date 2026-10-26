package top.tianyan.app.ui.bot

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.tianyan.app.feature.bot.R

private enum class OfficeCameraGlyph { ZOOM_IN, ZOOM_OUT, FIT, ROUTE }

/** Always-visible camera affordances so pinch and double-tap are not the only way around. */
@Composable
internal fun OfficeCameraControls(
    metrics: OfficeMetrics,
    navigationVisible: Boolean = false,
    onToggleNavigation: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val zoomIn = stringResource(R.string.fbot_pixel_office_zoom_in)
    val zoomOut = stringResource(R.string.fbot_pixel_office_zoom_out)
    val fit = stringResource(R.string.fbot_pixel_office_fit_view)
    val reset = stringResource(R.string.fbot_pixel_office_reset_view)
    val showPaths = stringResource(R.string.fbot_pixel_office_show_paths)
    val hidePaths = stringResource(R.string.fbot_pixel_office_hide_paths)
    val fitted = metrics.isFitted
    Column(
        modifier
            .statusBarsPadding()
            .padding(top = 8.dp, end = 12.dp)
            .testTag("office-camera-controls"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OfficeCameraButton("office-camera-zoom-in", zoomIn, OfficeCameraGlyph.ZOOM_IN) {
            metrics.zoomBy(1.35f)
        }
        OfficeCameraButton("office-camera-zoom-out", zoomOut, OfficeCameraGlyph.ZOOM_OUT) {
            metrics.zoomBy(1f / 1.35f)
        }
        OfficeCameraButton(
            tag = "office-camera-fit",
            label = if (fitted) reset else fit,
            glyph = OfficeCameraGlyph.FIT,
        ) {
            if (metrics.isFitted) metrics.recenter() else metrics.fitToView()
        }
        if (onToggleNavigation != null) {
            OfficeCameraButton(
                tag = "office-camera-navigation",
                label = if (navigationVisible) hidePaths else showPaths,
                glyph = OfficeCameraGlyph.ROUTE,
                active = navigationVisible,
                onClick = onToggleNavigation,
            )
        }
    }
}

@Composable
private fun OfficeCameraButton(
    tag: String,
    label: String,
    glyph: OfficeCameraGlyph,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(48.dp)
            .testTag(tag)
            .semantics { contentDescription = label },
        shape = RectangleShape,
        color = if (active) Color(0xFFD8F4FF) else Color(0xFFF6F0E4),
        border = BorderStroke(2.dp, Color(PixelOfficeRoom.TRIM)),
        shadowElevation = 2.dp,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CameraGlyph(glyph)
        }
    }
}

@Composable
private fun CameraGlyph(glyph: OfficeCameraGlyph) {
    Canvas(Modifier.size(18.dp)) {
        val ink = Color(0xFF3A2A1E)
        val stroke = size.minDimension * 0.11f
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float) {
            drawLine(ink, Offset(x0, y0), Offset(x1, y1), strokeWidth = stroke, cap = StrokeCap.Square)
        }
        when (glyph) {
            OfficeCameraGlyph.ZOOM_IN -> {
                bar(size.width / 2f, size.height * 0.16f, size.width / 2f, size.height * 0.84f)
                bar(size.width * 0.16f, size.height / 2f, size.width * 0.84f, size.height / 2f)
            }
            OfficeCameraGlyph.ZOOM_OUT -> {
                bar(size.width * 0.16f, size.height / 2f, size.width * 0.84f, size.height / 2f)
            }
            OfficeCameraGlyph.FIT -> {
                val inset = size.minDimension * 0.14f
                val arm = size.minDimension * 0.26f
                val right = size.width - inset
                val bottom = size.height - inset
                bar(inset, inset, inset + arm, inset)
                bar(inset, inset, inset, inset + arm)
                bar(right, inset, right - arm, inset)
                bar(right, inset, right, inset + arm)
                bar(inset, bottom, inset + arm, bottom)
                bar(inset, bottom, inset, bottom - arm)
                bar(right, bottom, right - arm, bottom)
                bar(right, bottom, right, bottom - arm)
            }
            OfficeCameraGlyph.ROUTE -> {
                val points = listOf(
                    Offset(size.width * 0.18f, size.height * 0.78f),
                    Offset(size.width * 0.5f, size.height * 0.5f),
                    Offset(size.width * 0.82f, size.height * 0.22f),
                )
                bar(points[0].x, points[0].y, points[1].x, points[1].y)
                bar(points[1].x, points[1].y, points[2].x, points[2].y)
                val node = size.minDimension * 0.15f
                points.forEach { point ->
                    drawRect(ink, Offset(point.x - node / 2f, point.y - node / 2f), androidx.compose.ui.geometry.Size(node, node))
                }
            }
        }
    }
}
