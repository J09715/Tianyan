package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficePathfindingTest {
    private val scene = OfficeSceneSpec.Default
    private val graph = scene.walkGraph()

    @Test
    fun `cross floor path follows the left stair column`() {
        val path = officeWalkPath(scene, graph, OfficePoint(96, 150), OfficePoint(96, 266))

        assertEquals(OfficePoint(96, 150), path.first())
        assertEquals(OfficePoint(96, 266), path.last())
        assertTrue(path.contains(OfficePoint(16, 150)))
        assertTrue(path.contains(OfficePoint(16, 266)))
    }

    @Test
    fun `short same floor moves stay direct`() {
        val path = officeWalkPath(scene, graph, OfficePoint(100, 150), OfficePoint(110, 152))

        assertEquals(listOf(OfficePoint(100, 150), OfficePoint(110, 152)), path)
    }

    @Test
    fun `long same floor moves stay direct when the floor is clear`() {
        val path = officeWalkPath(scene, graph, OfficePoint(40, 150), OfficePoint(170, 150))

        assertEquals(listOf(OfficePoint(40, 150), OfficePoint(170, 150)), path)
    }

    @Test
    fun `same floor move does not take a single waypoint U-turn`() {
        val path = officeWalkPath(scene, graph, OfficePoint(40, 150), OfficePoint(80, 150))

        assertEquals(listOf(OfficePoint(40, 150), OfficePoint(80, 150)), path)
    }

    @Test
    fun `local walk leaves a seat that sits inside its own furniture`() {
        val floor = scene.floorAtRoomY(376)!!
        val start = OfficePoint(40, 338)
        val end = OfficePoint(40, 362)

        assertEquals(listOf(start, end), officeLocalWalk(scene, start, end, floor))
    }

    @Test
    fun `path sampling interpolates by distance`() {
        val path = listOf(OfficePoint(0, 0), OfficePoint(10, 0), OfficePoint(10, 10))

        val half = officePathPoint(path, 0.5f)
        assertEquals(10f, half.first, 0.001f)
        assertEquals(0f, half.second, 0.001f)

        val threeQuarters = officePathPoint(path, 0.75f)
        assertEquals(10f, threeQuarters.first, 0.001f)
        assertEquals(5f, threeQuarters.second, 0.001f)
    }

    @Test
    fun `local walk detours around fixed furniture`() {
        val scene = OfficeSceneSpec.Default.copy(
            furniture = OfficeSceneSpec.Default.furniture + OfficeFurnitureSpec(
                "test-block",
                OfficeBounds(60, 95, 20, 10),
            ),
        )
        val floor = scene.floorAtRoomY(100)!!
        val path = officeLocalWalk(
            scene,
            OfficePoint(40, 100),
            OfficePoint(100, 100),
            floor,
        )

        assertTrue(path.size > 2)
        assertTrue(path.none { it.x in 60..79 && it.y in 95..104 })
        val length = path.zipWithNext().sumOf { (a, b) ->
            kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
        }
        assertTrue(length > 60.0)
    }

    @Test
    fun `local walk keeps clear legs direct`() {
        val floor = scene.floorAtRoomY(100)!!

        assertEquals(
            listOf(OfficePoint(40, 100), OfficePoint(100, 100)),
            officeLocalWalk(scene, OfficePoint(40, 100), OfficePoint(100, 100), floor),
        )
    }

    @Test
    fun `missing graph falls back to a direct path`() {
        val noWaypoints = scene.copy(waypoints = emptyList())
        val path = officeWalkPath(
            noWaypoints,
            noWaypoints.walkGraph(),
            OfficePoint(20, 150),
            OfficePoint(160, 380),
        )

        assertEquals(listOf(OfficePoint(20, 150), OfficePoint(160, 380)), path)
    }
}
