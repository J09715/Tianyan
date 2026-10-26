package top.tianyan.app.ui.bot

/** Original room units, before walkway compaction. Renderers consume furniture-local anchors. */
internal data class OfficeSceneSpec(
    val desks: List<OfficeDeskSpec> = defaultOfficeDesks(),
    val seats: List<OfficeSeat> = defaultOfficeSeats(),
    val cats: List<OfficeCatSpec> = defaultOfficeCats(),
    val coffeeMachines: List<OfficeCoffeeMachineSpec> = listOf(OfficeCoffeeMachineSpec(150, 330)),
    val zones: List<OfficeZoneSpec> = defaultOfficeZones(),
    val overflow: OfficeOverflowSpec = OfficeOverflowSpec(),
    val floors: List<OfficeFloorSpec> = defaultOfficeFloors(),
    val furniture: List<OfficeFurnitureSpec> = defaultOfficeFurniture(),
    val boards: OfficeBoardsSpec = OfficeBoardsSpec(),
    val ambient: OfficeAmbientSpec = OfficeAmbientSpec(),
    val waypoints: List<OfficeWaypointSpec> = defaultOfficeWaypoints(),
) {
    val allSeats get() = desks.map { it.seat } + seats
    val interactions get() = cats.map { it.interaction } + coffeeMachines.map { it.interaction }

    fun floorAtRoomY(y: Int): OfficeFloorSpec? = floors.firstOrNull { it.contains(y) }

    /** Actors carry walkway-compacted coordinates; floor specs stay in room space. */
    fun floorAtSceneY(sceneY: Int): OfficeFloorSpec? = floors.firstOrNull { floor ->
        val top = PixelOfficeScene.y(floor.top.toFloat()).toInt()
        val bottom = PixelOfficeScene.y(floor.bottom.toFloat()).toInt()
        sceneY in top until bottom
    }

    init {
        requireValidOfficeScene(this)
    }

    companion object {
        val Default = OfficeSceneSpec()
    }
}

internal data class OfficeAnchor(val x: Int, val y: Int)
internal data class OfficeBounds(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    fun contains(px: Int, py: Int): Boolean = px in x until right && py in y until bottom
}

internal data class OfficeDeskShadow(val dx: Int = -1, val dy: Int = 18, val width: Int = 38)

internal data class OfficeDeskSpec(
    val id: String,
    val x: Int,
    val y: Int,
    val screenColor: Int = 0xFF7EC8E3.toInt(),
    val animationPhase: Int = 0,
    val drawers: Boolean = true,
    val shadow: OfficeDeskShadow? = OfficeDeskShadow(),
) {
    val seat get() = OfficeSeat(id, x + 10, y + 10, OfficePose.TYPE, OfficeSeatGroup.WORK)
    val screen get() = OfficeAnchor(x + 19, y - 18)
}

internal enum class OfficeInteractionAction { PET, BREW }
internal data class OfficeInteractionSpec(val id: String, val bounds: OfficeBounds, val action: OfficeInteractionAction)
internal enum class OfficeCatStyle { SLEEPING, PERCHED }

internal data class OfficeCatSpec(val id: String, val style: OfficeCatStyle, val x: Int, val y: Int) {
    val hitBounds get() = when (style) {
        OfficeCatStyle.SLEEPING -> OfficeBounds(x + 1, y - 3, 16, 10)
        OfficeCatStyle.PERCHED -> OfficeBounds(x, y, 21, 19)
    }
    val heart get() = OfficeAnchor(x + 9, y - 1)
    val interaction get() = OfficeInteractionSpec(id, hitBounds, OfficeInteractionAction.PET)
}

internal data class OfficeCoffeeMachineSpec(val x: Int, val y: Int, val id: String = "office-coffee-machine") {
    val hitBounds get() = OfficeBounds(x - 2, y - 2, 24, 24)
    val steam get() = OfficeAnchor(x + 8, y + 13)
    val wand get() = OfficeAnchor(x + 16, y + 16)
    val indicator get() = OfficeAnchor(x + 17, y + 2)
    val interaction get() = OfficeInteractionSpec(id, hitBounds, OfficeInteractionAction.BREW)
}

internal enum class OfficeZoneKind { THINK, MEET, WORK, COFFEE, REST }
internal data class OfficeZoneSpec(val id: String, val kind: OfficeZoneKind, val anchor: OfficeAnchor)

/** Room-space floor band. Kept separate from walkway compaction so art can move independently. */
internal data class OfficeFloorSpec(val id: String, val top: Int, val bottom: Int) {
    fun contains(y: Int): Boolean = y in top until bottom
    fun sceneTop(): Float = PixelOfficeScene.y(top.toFloat())
    fun sceneBottom(): Float = PixelOfficeScene.y(bottom.toFloat())
}

/** Fixed-art footprint used by validation. Art positions still live in the room painters. */
internal data class OfficeFurnitureSpec(
    val id: String,
    val bounds: OfficeBounds,
    val blocksWalking: Boolean = true,
)

internal data class OfficeBoardSpec(val bounds: OfficeBounds)
internal data class OfficeBoardsSpec(
    val task: OfficeBoardSpec = OfficeBoardSpec(OfficeBounds(10, 86, 74, 32)),
    val objective: OfficeBoardSpec = OfficeBoardSpec(OfficeBounds(59, 20, 46, 28)),
)

internal data class OfficeWaypointSpec(
    val id: String,
    val x: Int,
    val y: Int,
    val links: List<String> = emptyList(),
)

internal data class OfficeWindowLightSpec(val dx: Int, val dy: Int)
internal data class OfficeLampSpec(val anchor: OfficeAnchor, val phase: Int)

/** Fixed ambient anchors. Art still draws the furniture, but particles and the clock follow these. */
internal data class OfficeAmbientSpec(
    val waterCooler: OfficeBounds = OfficeBounds(93, 412, 15, 30),
    val meetingScreen: OfficeBounds = OfficeBounds(119, 86, 57, 20),
    val serverRack: OfficeBounds = OfficeBounds(136, 410, 14, 32),
    val window: OfficeBounds = OfficeBounds(118, 12, 68, 42),
    val windowLights: List<OfficeWindowLightSpec> = listOf(
        OfficeWindowLightSpec(11, 26),
        OfficeWindowLightSpec(27, 25),
        OfficeWindowLightSpec(51, 16),
        OfficeWindowLightSpec(39, 31),
        OfficeWindowLightSpec(59, 12),
    ),
    val lamps: List<OfficeLampSpec> = listOf(
        OfficeLampSpec(OfficeAnchor(157, 93), 0),
        OfficeLampSpec(OfficeAnchor(100, 201), 2),
        OfficeLampSpec(OfficeAnchor(92, 341), 4),
    ),
    val clock: OfficeAnchor = OfficeAnchor(101, 95),
)

/** Legacy overflow placement remains deterministic, with its geometry owned by the scene. */
internal data class OfficeOverflowSpec(
    val rows: List<Int> = listOf(148, 288, 408),
    val startX: Int = 20,
    val columns: Int = 6,
    val spacingX: Int = 29,
    val cycleOffsetY: Int = 26,
) {
    fun anchor(index: Int) = OfficeAnchor(
        startX + index % columns * spacingX,
        rows[(index / columns) % rows.size] + index / (columns * rows.size) * cycleOffsetY,
    )
}
