package top.tianyan.app.ui.bot

import java.util.PriorityQueue
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

internal data class OfficePoint(val x: Int, val y: Int)
internal data class OfficeWalkNode(val id: String, val point: OfficePoint, val floor: String?)
internal data class OfficeWalkGraph(
    val nodes: List<OfficeWalkNode>,
    val links: Map<String, List<String>>,
) {
    val byId: Map<String, OfficeWalkNode> = nodes.associateBy { it.id }
}

private const val OFFICE_DIRECT_WALK_LIMIT = 24f

/** Links are declared per waypoint but traversed in both directions. */
internal fun officeWaypointLinks(waypoints: List<OfficeWaypointSpec>): Map<String, List<String>> {
    val links = waypoints.associate { it.id to mutableSetOf<String>() }
    waypoints.forEach { waypoint ->
        waypoint.links.forEach { target ->
            links[waypoint.id]?.add(target)
            links[target]?.add(waypoint.id)
        }
    }
    return links.mapValues { it.value.toList() }
}

/** Waypoints are authored in room coordinates and converted once for compacted render space. */
internal fun OfficeSceneSpec.walkGraph(): OfficeWalkGraph {
    val nodes = waypoints.map { waypoint ->
        val point = OfficePoint(waypoint.x, PixelOfficeScene.y(waypoint.y.toFloat()).roundToInt())
        OfficeWalkNode(waypoint.id, point, floorAtSceneY(point.y)?.id)
    }
    return OfficeWalkGraph(nodes, officeWaypointLinks(waypoints))
}

/**
 * Routes through the shared walkway graph. Short same-floor moves and missing graphs fall back
 * to a direct line, so seat nudges do not detour through the whole floor.
 */
internal fun officeWalkPath(
    scene: OfficeSceneSpec,
    graph: OfficeWalkGraph,
    start: OfficePoint,
    end: OfficePoint,
): List<OfficePoint> {
    if (start == end) return listOf(start)
    val startFloor = scene.floorAtSceneY(start.y)
    val endFloor = scene.floorAtSceneY(end.y)
    if (startFloor?.id == endFloor?.id && distance(start, end) <= OFFICE_DIRECT_WALK_LIMIT) {
        return listOf(start, end)
    }
    if (startFloor == null || endFloor == null) return listOf(start, end)
    val startNodes = graph.nodesOnFloor(startFloor.id)
    val endNodes = graph.nodesOnFloor(endFloor.id)
    if (startNodes.isEmpty() || endNodes.isEmpty()) return listOf(start, end)
    var bestCost = Float.MAX_VALUE
    var bestMiddle: List<OfficeWalkNode>? = null
    startNodes.forEach { first ->
        endNodes.forEach { last ->
            val middle = graph.shortestPath(first.id, last.id)
            if (middle.isEmpty()) return@forEach
            val cost = distance(start, first.point) + middle.pathLength() + distance(last.point, end)
            if (cost < bestCost) {
                bestCost = cost
                bestMiddle = middle
            }
        }
    }
    val middle = bestMiddle ?: return listOf(start, end)
    val startLeg = officeLocalWalk(scene, start, middle.first().point, startFloor)
    val endLeg = officeLocalWalk(scene, middle.last().point, end, endFloor)
    val graphPath = buildList {
        add(start)
        startLeg.drop(1).forEach { addIfDifferent(it) }
        middle.drop(1).forEach { node -> addIfDifferent(node.point) }
        endLeg.drop(1).forEach { addIfDifferent(it) }
    }
    if (startFloor.id != endFloor.id) return graphPath
    val localPath = officeLocalWalk(scene, start, end, startFloor)
    return if (polylineLength(localPath) <= polylineLength(graphPath) + 2f) localPath else graphPath
}

private fun polylineLength(points: List<OfficePoint>): Float {
    var total = 0f
    for (index in 1 until points.size) {
        total += distance(points[index - 1], points[index])
    }
    return total
}

private fun MutableList<OfficePoint>.addIfDifferent(point: OfficePoint) {
    if (lastOrNull() != point) add(point)
}

private fun OfficeWalkGraph.nodesOnFloor(floor: String): List<OfficeWalkNode> =
    nodes.filter { it.floor == floor }

private fun List<OfficeWalkNode>.pathLength(): Float {
    var total = 0f
    for (index in 1 until size) {
        total += distance(this[index - 1].point, this[index].point)
    }
    return total
}

private const val NAV_CELL = 4f
private const val NAV_OBSTACLE_COST = 12f
private const val NAV_DIAGONAL_COST = 1.4142f

private data class SceneRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

private data class NavNode(val index: Int, val priority: Float)

/** A* only for the short leg between a seat and its waypoint; waypoint edges stay graph-driven. */
internal fun officeLocalWalk(
    scene: OfficeSceneSpec,
    start: OfficePoint,
    end: OfficePoint,
    floor: OfficeFloorSpec,
): List<OfficePoint> {
    if (start == end) return listOf(start)
    val obstacles = scene.localObstacles(floor, start, end)
    if (!segmentBlocked(start, end, obstacles)) return listOf(start, end)
    val top = floor.sceneTop()
    val cols = ceil(PixelOfficeRoom.WIDTH / NAV_CELL).toInt()
    val rows = ((floor.sceneBottom() - top) / NAV_CELL).toInt().coerceAtLeast(1)
    val startIndex = cellIndex(start, top, cols, rows)
    val endIndex = cellIndex(end, top, cols, rows)
    if (startIndex == endIndex) return listOf(start, end)
    val costs = FloatArray(cols * rows) { 1f }
    obstacles.forEach { rect ->
        val leftCol = (rect.left / NAV_CELL).toInt().coerceIn(0, cols - 1)
        val rightCol = ((rect.right - 0.001f) / NAV_CELL).toInt().coerceIn(0, cols - 1)
        val topRow = ((rect.top - top) / NAV_CELL).toInt().coerceIn(0, rows - 1)
        val bottomRow = ((rect.bottom - 0.001f - top) / NAV_CELL).toInt().coerceIn(0, rows - 1)
        if (rightCol < leftCol || bottomRow < topRow) return@forEach
        for (row in topRow..bottomRow) {
            for (col in leftCol..rightCol) {
                costs[row * cols + col] = NAV_OBSTACLE_COST
            }
        }
    }
    val gScore = FloatArray(cols * rows) { Float.MAX_VALUE }
    val previous = IntArray(cols * rows) { -1 }
    val closed = BooleanArray(cols * rows)
    val queue = PriorityQueue(compareBy<NavNode> { it.priority }.thenBy { it.index })
    gScore[startIndex] = 0f
    queue += NavNode(startIndex, heuristic(startIndex, endIndex, cols))
    while (queue.isNotEmpty()) {
        val current = queue.remove().index
        if (closed[current]) continue
        closed[current] = true
        if (current == endIndex) break
        val cx = current % cols
        val cy = current / cols
        for (dy in -1..1) {
            for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until cols || ny !in 0 until rows) continue
                val next = ny * cols + nx
                if (closed[next]) continue
                val step = if (dx != 0 && dy != 0) NAV_DIAGONAL_COST else 1f
                val tentative = gScore[current] + step * costs[next]
                if (tentative < gScore[next]) {
                    gScore[next] = tentative
                    previous[next] = current
                    queue += NavNode(next, tentative + heuristic(next, endIndex, cols))
                }
            }
        }
    }
    if (gScore[endIndex] == Float.MAX_VALUE) return listOf(start, end)
    val cells = ArrayList<OfficePoint>()
    var cursor = endIndex
    while (cursor != -1) {
        cells += cellPoint(cursor, top, cols)
        cursor = previous[cursor]
    }
    cells.reverse()
    val raw = buildList {
        add(start)
        cells.forEach { addIfDifferent(it) }
        addIfDifferent(end)
    }
    return simplifyLocalPath(raw, obstacles)
}

private fun OfficeSceneSpec.localObstacles(
    floor: OfficeFloorSpec,
    start: OfficePoint,
    end: OfficePoint,
): List<SceneRect> {
    val floorTop = floor.sceneTop()
    val floorBottom = floor.sceneBottom()
    val result = mutableListOf<SceneRect>()
    fun keep(rect: SceneRect): Boolean {
        if (rect.bottom <= floorTop || rect.top >= floorBottom) return false
        // Seats intentionally sit inside their own desk/sofa; do not trap the endpoint.
        if (rect.contains(start.x.toFloat(), start.y.toFloat())) return false
        if (rect.contains(end.x.toFloat(), end.y.toFloat())) return false
        return true
    }
    furniture.filter { it.blocksWalking }.forEach { item ->
        val rect = SceneRect(
            item.bounds.x.toFloat(),
            PixelOfficeScene.y(item.bounds.y.toFloat()),
            item.bounds.right.toFloat(),
            PixelOfficeScene.y(item.bounds.bottom.toFloat()),
        )
        if (keep(rect)) result += rect
    }
    desks.forEach { desk ->
        val rect = SceneRect(
            desk.x.toFloat(),
            PixelOfficeScene.y((desk.y - 20).toFloat()),
            (desk.x + 37).toFloat(),
            PixelOfficeScene.y((desk.y + 18).toFloat()),
        )
        if (keep(rect)) result += rect
    }
    return result
}

private fun cellIndex(point: OfficePoint, top: Float, cols: Int, rows: Int): Int {
    val col = (point.x / NAV_CELL).toInt().coerceIn(0, cols - 1)
    val row = ((point.y - top) / NAV_CELL).toInt().coerceIn(0, rows - 1)
    return row * cols + col
}

private fun cellPoint(index: Int, top: Float, cols: Int): OfficePoint {
    val col = index % cols
    val row = index / cols
    return OfficePoint(
        ((col + 0.5f) * NAV_CELL).roundToInt(),
        (top + (row + 0.5f) * NAV_CELL).roundToInt(),
    )
}

private fun heuristic(index: Int, endIndex: Int, cols: Int): Float {
    val x = index % cols
    val y = index / cols
    val endX = endIndex % cols
    val endY = endIndex / cols
    return hypot((x - endX).toDouble(), (y - endY).toDouble()).toFloat()
}

private fun segmentBlocked(a: OfficePoint, b: OfficePoint, obstacles: List<SceneRect>): Boolean {
    val steps = max(1, (distance(a, b) / 2f).roundToInt())
    for (step in 0..steps) {
        val t = step / steps.toFloat()
        val x = a.x + (b.x - a.x) * t
        val y = a.y + (b.y - a.y) * t
        if (obstacles.any { it.contains(x, y) }) return true
    }
    return false
}

private fun simplifyLocalPath(points: List<OfficePoint>, obstacles: List<SceneRect>): List<OfficePoint> {
    if (points.size <= 2) return points
    val result = mutableListOf(points.first())
    var anchor = 0
    for (index in 1 until points.lastIndex) {
        if (segmentBlocked(points[anchor], points[index + 1], obstacles)) {
            result += points[index]
            anchor = index
        }
    }
    result += points.last()
    return result
}

private fun OfficeWalkGraph.shortestPath(startId: String, endId: String): List<OfficeWalkNode> {
    val start = byId[startId] ?: return emptyList()
    val end = byId[endId] ?: return emptyList()
    if (startId == endId) return listOf(start)
    val distances = HashMap<String, Float>()
    val previous = HashMap<String, String>()
    val settled = HashSet<String>()
    val queue = PriorityQueue(compareBy<Pair<String, Float>> { it.second }.thenBy { it.first })
    distances[startId] = 0f
    queue += startId to 0f
    while (queue.isNotEmpty()) {
        val (id, cost) = queue.remove()
        if (!settled.add(id)) continue
        if (id == endId) break
        val node = byId[id] ?: continue
        links[id].orEmpty().forEach { nextId ->
            val next = byId[nextId] ?: return@forEach
            val nextCost = cost + distance(node.point, next.point)
            if (nextCost < (distances[nextId] ?: Float.MAX_VALUE)) {
                distances[nextId] = nextCost
                previous[nextId] = id
                queue += nextId to nextCost
            }
        }
    }
    if (endId !in distances) return emptyList()
    val reversed = mutableListOf<OfficeWalkNode>()
    var cursor: String? = endId
    while (cursor != null) {
        reversed += byId[cursor] ?: break
        cursor = previous[cursor]
    }
    return reversed.asReversed()
}

/** Samples a polyline by normalized distance, used by the handoff courier. */
internal fun officePathPoint(path: List<OfficePoint>, progress: Float): Pair<Float, Float> {
    if (path.isEmpty()) return 0f to 0f
    if (path.size == 1) return path[0].x.toFloat() to path[0].y.toFloat()
    val lengths = FloatArray(path.size - 1) { index ->
        distance(path[index], path[index + 1])
    }
    val total = lengths.sum()
    if (total <= 0f) return path.last().x.toFloat() to path.last().y.toFloat()
    var remaining = progress.coerceIn(0f, 1f) * total
    lengths.forEachIndexed { index, length ->
        if (remaining <= length || index == lengths.lastIndex) {
            val t = if (length <= 0f) 1f else (remaining / length).coerceIn(0f, 1f)
            val from = path[index]
            val to = path[index + 1]
            return (from.x + (to.x - from.x) * t) to (from.y + (to.y - from.y) * t)
        }
        remaining -= length
    }
    val last = path.last()
    return last.x.toFloat() to last.y.toFloat()
}

private fun distance(a: OfficePoint, b: OfficePoint): Float =
    hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
