package top.tianyan.app.ui.bot

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import kotlin.math.max

/** Debug-only visualization of floor bands, blocking furniture and the walkway graph. */
@Composable
internal fun OfficeNavigationOverlay(
    scene: OfficeSceneSpec,
    graph: OfficeWalkGraph,
    originX: Float,
    originY: Float,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.testTag("office-navigation-overlay")) {
        val floorTopLine = Color(0x6624D1FF)
        scene.floors.forEach { floor ->
            val top = originY + floor.sceneTop() * scale
            val bottom = originY + floor.sceneBottom() * scale
            drawRect(
                Color(0x1824D1FF),
                Offset(originX, top),
                Size(PixelOfficeRoom.WIDTH * scale, max(1f, bottom - top)),
            )
            drawLine(floorTopLine, Offset(originX, top), Offset(originX + PixelOfficeRoom.WIDTH * scale, top), max(1f, 0.35f * scale))
            drawLine(floorTopLine, Offset(originX, bottom), Offset(originX + PixelOfficeRoom.WIDTH * scale, bottom), max(1f, 0.35f * scale))
        }
        val obstacleColor = Color(0xCCFF5A5A)
        scene.furniture.filter { it.blocksWalking }.forEach { item ->
            drawObstacle(item.bounds, obstacleColor, originX, originY, scale)
        }
        scene.desks.forEach { desk ->
            drawObstacle(
                OfficeBounds(desk.x, desk.y - 20, 37, 38),
                obstacleColor,
                originX,
                originY,
                scale,
            )
        }
        val edgeColor = Color(0xCCFFC857)
        scene.waypoints.forEach { waypoint ->
            val from = graph.byId[waypoint.id] ?: return@forEach
            waypoint.links.forEach { target ->
                val to = graph.byId[target] ?: return@forEach
                if (waypoint.id >= target) return@forEach
                drawLine(
                    edgeColor,
                    Offset(originX + from.point.x * scale, originY + from.point.y * scale),
                    Offset(originX + to.point.x * scale, originY + to.point.y * scale),
                    strokeWidth = max(1f, 0.45f * scale),
                )
            }
        }
        val nodeColor = Color(0xFF24D1FF)
        graph.nodes.forEach { node ->
            val nodeSize = max(3f, 1.8f * scale)
            drawRect(
                nodeColor,
                Offset(originX + node.point.x * scale - nodeSize / 2f, originY + node.point.y * scale - nodeSize / 2f),
                Size(nodeSize, nodeSize),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawObstacle(
    bounds: OfficeBounds,
    color: Color,
    originX: Float,
    originY: Float,
    scale: Float,
) {
    val top = originY + PixelOfficeScene.y(bounds.y.toFloat()) * scale
    val bottom = originY + PixelOfficeScene.y(bounds.bottom.toFloat()) * scale
    drawRect(
        color,
        Offset(originX + bounds.x * scale, top),
        Size(bounds.width * scale, max(1f, bottom - top)),
        style = Stroke(width = max(1f, 0.35f * scale)),
    )
}
