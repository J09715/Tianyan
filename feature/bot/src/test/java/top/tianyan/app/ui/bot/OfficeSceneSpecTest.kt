package top.tianyan.app.ui.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAvatarState

class OfficeSceneSpecTest {
    private val original = OfficeSceneSpec.Default

    @Test
    fun `scene migration preserves static pixel fingerprints`() {
        // Captured from the original room, detail and compacted scene before migration.
        assertEquals("1300783592, 732443589, 756414098", listOf(
            PixelOfficeRoom.pixels().contentHashCode(),
            PixelOfficeDetail.pixels().contentHashCode(),
            PixelOfficeScene.pixels().contentHashCode(),
        ).joinToString())
    }

    @Test
    fun `moving desk moves base artwork seat detail shadow and screen together`() {
        val moved = original.copy(desks = original.desks.map {
            if (it.id == "desk") it.copy(x = 22, y = 218) else it
        })
        val before = PixelOfficeRoom.pixels(includeShell = false)
        val after = PixelOfficeRoom.pixels(includeShell = false, scene = moved)
        val width = PixelOfficeRoom.WIDTH
        for (y in 194..232) for (x in 17..54) {
            assertEquals(before[y * width + x], after[(y + 4) * width + x + 4])
        }
        val bot = Bot(
            id = "coder", name = "coder", role = "工程师", systemPrompt = "",
            avatarSeed = "", sessionId = "session", createdAt = 1L, updatedAt = 1L,
        )
        val actor = PixelOfficeLayout.place(
            listOf(bot), mapOf(bot.id to BotAvatarState.EXECUTING), scene = moved,
        ).single()
        assertEquals(32, actor.x)
        assertEquals(228, actor.y)
        assertEquals(OfficePose.TYPE, actor.pose)
        // These pixels belong to the monitor bevel, drawer joint and contact shadow.
        val detail = PixelOfficeDetail.pixels(moved)
        for ((x, y, color) in listOf(
            Triple(39, 198, 0xFF493C39.toInt()),
            Triple(23, 220, 0xFF9E6E43.toInt()),
            Triple(21, 236, 0xFFAD845B.toInt()),
        )) assertEquals(color, detail[y * 3 * PixelOfficeDetail.WIDTH + x * 3])
        assertAmbientTranslation(original, moved, 4f, 4f)
    }

    @Test
    fun `moving coffee machine moves every art pixel hit bounds and brew feedback`() {
        val machine = original.coffeeMachines.single()
        val moved = original.copy(coffeeMachines = listOf(machine.copy(x = 130, y = 324)))
        val before = IntArray(PixelOfficeDetail.WIDTH * PixelOfficeDetail.HEIGHT)
        val after = IntArray(before.size)
        PixelOfficeDetail.Painter(before).coffeeMachine(machine)
        PixelOfficeDetail.Painter(after).coffeeMachine(moved.coffeeMachines.single())
        for (y in 330 * 3 until 348 * 3) for (x in 150 * 3 until 168 * 3) {
            assertEquals(before[y * PixelOfficeDetail.WIDTH + x], after[(y - 18) * PixelOfficeDetail.WIDTH + x - 60])
        }
        assertEquals(OfficeBounds(128, 322, 24, 24), moved.coffeeMachines.single().hitBounds)
        assertAmbientTranslation(original, moved, -20f, -6f)
        // The complete room pipeline uses the same machine instance as the standalone art.
        val rendered = PixelOfficeDetail.pixels(moved)
        assertEquals(0xFF9AA4AE.toInt(), rendered[325 * 3 * PixelOfficeDetail.WIDTH + 131 * 3])
    }

    @Test
    fun `adding a named desk automatically adds a typing seat artwork and monitor animation`() {
        val desk = OfficeDeskSpec("new-workstation", 16, 402, animationPhase = 8)
        val expanded = original.copy(desks = original.desks + desk)
        val bots = (0..8).map { bot("coder-$it") }
        val actors = PixelOfficeLayout.place(bots, bots.associate { it.id to BotAvatarState.EXECUTING }, scene = expanded)
        assertEquals(9, actors.size)
        assertTrue(actors.all { it.pose == OfficePose.TYPE })
        assertEquals(26 to 412, actors.last().let { it.x to it.y })
        val before = PixelOfficeRoom.pixels(includeShell = false)
        val after = PixelOfficeRoom.pixels(includeShell = false, scene = expanded)
        assertNotEquals(before[383 * 192 + 33], after[383 * 192 + 33])
        assertNotEquals(PixelOfficeDetail.pixels().contentHashCode(), PixelOfficeDetail.pixels(expanded).contentHashCode())
        assertTrue(PixelOfficeAmbient.dots(60_000L, emptyList(), scene = expanded).size >
            PixelOfficeAmbient.dots(60_000L, emptyList()).size)
    }

    @Test
    fun `all desk instances move together without identity or position special cases`() {
        original.desks.forEach { desk ->
            val moved = original.copy(desks = original.desks.map { if (it.id == desk.id) it.copy(y = it.y + 4) else it })
            val bots = (0..7).map { bot("coder-$it") }
            val actors = PixelOfficeLayout.place(bots, bots.associate { it.id to BotAvatarState.EXECUTING }, scene = moved)
            assertEquals(original.desks.map { it.seat.x to it.seat.y + if (it.id == desk.id) 4 else 0 }, actors.map { it.x to it.y })
            assertNotEquals(PixelOfficeRoom.pixels().contentHashCode(), PixelOfficeRoom.pixels(scene = moved).contentHashCode())
            assertNotEquals(PixelOfficeDetail.pixels().contentHashCode(), PixelOfficeDetail.pixels(moved).contentHashCode())
            assertAmbientTranslation(original, moved, 0f, 4f)
        }
    }

    @Test
    fun `renaming and moving seats preserves their supported poses and relative roaming`() {
        val renamed = original.copy(
            desks = original.desks.map { it.copy(id = "station-${it.id}") },
            seats = original.seats.map { it.copy(id = "location-${it.id}", x = it.x - 2) },
        )
        val bots = (0..15).map { bot("member-$it") }
        val states = listOf(BotAvatarState.IDLE, BotAvatarState.THINKING, BotAvatarState.EXECUTING, BotAvatarState.FAILED)
        states.forEach { state ->
            for (now in listOf(0L, 15_000L, 30_000L)) {
                val before = PixelOfficeLayout.place(bots, bots.associate { it.id to state }, now = now)
                val after = PixelOfficeLayout.place(bots, bots.associate { it.id to state }, now = now, scene = renamed)
                assertEquals(before.map { it.pose to it.idleAction }, after.map { it.pose to it.idleAction })
                before.zip(after).forEach { (a, b) ->
                    assertEquals(a.y, b.y)
                    assertTrue(b.x == a.x || b.x == a.x - 2)
                }
            }
        }
    }

    @Test
    fun `both cat styles move art click bounds tail and pet feedback with their instance`() {
        original.cats.forEach { cat ->
            val moved = original.copy(cats = original.cats.map {
                if (it.id == cat.id) it.copy(x = it.x + 4, y = it.y + 4) else it
            })
            val next = moved.cats.first { it.id == cat.id }
            assertEquals(cat.hitBounds.copy(x = cat.hitBounds.x + 4, y = cat.hitBounds.y + 4), next.hitBounds)
            val before = IntArray(PixelOfficeDetail.WIDTH * PixelOfficeDetail.HEIGHT)
            val after = IntArray(before.size)
            PixelOfficeDetail.Painter(before).cat(cat)
            PixelOfficeDetail.Painter(after).cat(next)
            var painted = 0
            before.indices.filter { before[it] != 0 }.forEach { index ->
                painted++
                assertEquals(before[index], after[index + 12 * PixelOfficeDetail.WIDTH + 12])
            }
            assertTrue(painted > 0)
            assertNotEquals(PixelOfficeDetail.pixels().contentHashCode(), PixelOfficeDetail.pixels(moved).contentHashCode())
            assertAmbientTranslation(original, moved, 4f, 4f, mapOf(cat.id to 59_600L))
        }
    }

    @Test
    fun `another coffee machine gets independent artwork hit target and brew particles`() {
        val machine = OfficeCoffeeMachineSpec(110, 46, id = "second-coffee-machine")
        val expanded = original.copy(coffeeMachines = original.coffeeMachines + machine)
        assertTrue(expanded.interactions.any { it.id == machine.id && it.bounds == machine.hitBounds })
        val baselineDots = PixelOfficeAmbient.dots(60_000L, emptyList(), scene = expanded)
        val brewingDots = PixelOfficeAmbient.dots(60_000L, emptyList(), scene = expanded,
            interactionAt = mapOf(machine.id to 59_600L))
        val feedback = brewingDots - baselineDots.toSet()
        assertTrue(feedback.isNotEmpty())
        assertTrue(feedback.all { it.x in 108f..130f && it.y in 40f..65f })
        val rendered = PixelOfficeDetail.pixels(expanded)
        assertEquals(0xFF9AA4AE.toInt(), rendered[47 * 3 * PixelOfficeDetail.WIDTH + 111 * 3])
    }

    @Test
    fun `scene rejects duplicate identities overlapping seats invalid anchors and overlapping hotspots`() {
        assertThrows(IllegalArgumentException::class.java) { original.copy(desks = original.desks + original.desks.first()) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(seats = original.seats + original.seats.first().copy(id = "other")) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(desks = listOf(OfficeDeskSpec("outside", -20, 214))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(zones = listOf(OfficeZoneSpec("outside", OfficeZoneKind.WORK, OfficeAnchor(192, 20)))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(cats = listOf(original.cats.first().copy(x = 149, y = 333))) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(cats = listOf(
            OfficeCatSpec("upper", OfficeCatStyle.SLEEPING, 50, 150),
            OfficeCatSpec("lower", OfficeCatStyle.PERCHED, 50, 173),
        )) }
        assertThrows(IllegalArgumentException::class.java) { original.copy(overflow = OfficeOverflowSpec(columns = 0)) }
    }

    @Test
    fun `scene floors map room space onto compacted scene space`() {
        assertEquals("lobby", original.floorAtRoomY(65)?.id)
        assertEquals("think", original.floorAtSceneY(66)?.id)
        assertEquals("work", original.floorAtSceneY(156)?.id)
        assertEquals("rest", original.floorAtSceneY(274)?.id)
        assertEquals(null, original.floorAtSceneY(PixelOfficeScene.HEIGHT))
    }

    @Test
    fun `ambient anchors follow their scene instances`() {
        val moved = original.copy(
            ambient = original.ambient.copy(
                window = original.ambient.window.copy(
                    x = original.ambient.window.x + 4,
                    y = original.ambient.window.y + 4,
                ),
            ),
        )
        assertAmbientTranslation(original, moved, 4f, 4f)
    }

    @Test
    fun `scene rejects invalid floors boards ambient anchors and blocked overflow`() {
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(floors = listOf(OfficeFloorSpec("bad", 100, 100)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(floors = listOf(OfficeFloorSpec("work-only", 178, 306)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(floors = listOf(
                OfficeFloorSpec("first", 0, 300),
                OfficeFloorSpec("second", 200, PixelOfficeRoom.HEIGHT),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(boards = original.boards.copy(
                task = OfficeBoardSpec(OfficeBounds(200, 86, 74, 32)),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(ambient = original.ambient.copy(
                windowLights = listOf(OfficeWindowLightSpec(100, 100)),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(ambient = original.ambient.copy(clock = OfficeAnchor(192, 95)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(
                furniture = original.furniture + OfficeFurnitureSpec(
                    "blocked-overflow",
                    OfficeBounds(20, 148, 10, 10),
                ),
            )
        }
    }

    @Test
    fun `scene rejects floors that collapse or change identity after compaction`() {
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(floors = listOf(OfficeFloorSpec("collapsed", 290, 306)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(
                floors = listOf(
                    OfficeFloorSpec("a", 0, 295),
                    OfficeFloorSpec("b", 295, PixelOfficeRoom.HEIGHT),
                ),
                seats = original.seats + OfficeSeat("mismatch", 150, 294, OfficePose.STAND),
            )
        }
    }

    @Test
    fun `waypoint graph rejects missing links disconnected nodes and outside anchors`() {
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(waypoints = listOf(OfficeWaypointSpec("a", 16, 150, listOf("missing"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(waypoints = listOf(
                OfficeWaypointSpec("a", 16, 150),
                OfficeWaypointSpec("b", 96, 150),
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(waypoints = original.waypoints + OfficeWaypointSpec("outside", 200, 150))
        }
        assertThrows(IllegalArgumentException::class.java) {
            original.copy(waypoints = listOf(OfficeWaypointSpec("a", 16, 150, listOf("a"))))
        }
    }

    private fun bot(id: String) = Bot(
        id = id, name = id, role = "工程师", systemPrompt = "", avatarSeed = "", sessionId = "session-$id",
        createdAt = 1L, updatedAt = 1L,
    )

    private fun assertAmbientTranslation(
        before: OfficeSceneSpec, after: OfficeSceneSpec, dx: Float, dy: Float,
        interactionAt: Map<String, Long> = mapOf(before.coffeeMachines.single().id to 59_600L),
    ) {
        // Includes a blinking machine LED and the extra steam emitted after clicking brew.
        val first = PixelOfficeAmbient.dots(60_000L, emptyList(), interactionAt = interactionAt, scene = before)
        val second = PixelOfficeAmbient.dots(60_000L, emptyList(), interactionAt = interactionAt, scene = after)
        assertEquals(first.size, second.size)
        var translated = 0
        first.zip(second).forEach { (a, b) ->
            if (a != b) {
                translated++
                assertEquals(a.x + dx, b.x, 0.0001f)
                assertEquals(a.y + dy, b.y, 0.0001f)
                assertEquals(a.copy(x = b.x, y = b.y), b)
            }
        }
        assertTrue(translated > 0)
    }
}
