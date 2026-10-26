package top.tianyan.app.ui.bot

/** Validate data errors before rendering or hit testing. Art may deliberately clip at edges. */
internal fun requireValidOfficeScene(scene: OfficeSceneSpec) {
    fun unique(ids: List<String>, kind: String) {
        require(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size) { "Duplicate or blank $kind ID" }
    }
    fun inside(x: Int, y: Int) = x in 0 until PixelOfficeRoom.WIDTH && y in 0 until PixelOfficeRoom.HEIGHT
    fun boundsInside(label: String, bounds: OfficeBounds) {
        require(bounds.width > 0 && bounds.height > 0) { "$label has empty bounds" }
        require(
            inside(bounds.x, bounds.y) && inside(bounds.right - 1, bounds.bottom - 1),
        ) { "$label is outside the room" }
    }
    fun requireFloor(label: String, x: Int, y: Int) {
        val roomFloor = scene.floorAtRoomY(y)
        require(roomFloor != null) { "$label at ($x,$y) is outside every floor" }
        val sceneFloor = scene.floorAtSceneY(PixelOfficeScene.y(y.toFloat()).toInt())
        require(sceneFloor?.id == roomFloor.id) {
            "$label at ($x,$y) changes floor after walkway compaction"
        }
    }
    fun requireUnblocked(label: String, x: Int, y: Int) {
        require(scene.furniture.none { it.blocksWalking && it.bounds.contains(x, y) }) {
            "$label at ($x,$y) overlaps fixed furniture"
        }
    }
    unique(
        scene.desks.map { it.id } + scene.cats.map { it.id } + scene.coffeeMachines.map { it.id } +
            scene.furniture.map { it.id },
        "furniture",
    )
    unique(scene.allSeats.map { it.id }, "seat")
    require(scene.allSeats.map { it.x to it.y }.distinct().size == scene.allSeats.size) { "Seats overlap" }
    unique(scene.interactions.map { it.id }, "interaction")
    unique(scene.zones.map { it.id }, "zone")
    unique(scene.floors.map { it.id }, "floor")
    scene.floors.forEach { floor ->
        require(floor.top < floor.bottom && floor.top >= 0 && floor.bottom <= PixelOfficeRoom.HEIGHT) {
            "Floor ${floor.id} is invalid"
        }
    }
    scene.floors.sortedBy { it.top }.zipWithNext().forEach { (first, second) ->
        require(first.bottom <= second.top) { "Floors ${first.id} and ${second.id} overlap" }
    }
    scene.floors.forEach { floor ->
        require(floor.sceneBottom() > floor.sceneTop()) {
            "Floor ${floor.id} collapses after walkway compaction"
        }
    }
    scene.floors.sortedBy { it.top }.zipWithNext().forEach { (first, second) ->
        require(first.sceneBottom() <= second.sceneTop()) {
            "Floors ${first.id} and ${second.id} overlap after walkway compaction"
        }
    }
    unique(scene.waypoints.map { it.id }, "waypoint")
    scene.waypoints.forEach { waypoint ->
        require(inside(waypoint.x, waypoint.y)) { "Waypoint ${waypoint.id} is outside the room" }
        requireFloor("Waypoint ${waypoint.id}", waypoint.x, waypoint.y)
        waypoint.links.forEach { link ->
            require(link != waypoint.id) { "Waypoint ${waypoint.id} links to itself" }
            require(scene.waypoints.any { it.id == link }) { "Waypoint ${waypoint.id} links to missing $link" }
        }
    }
    if (scene.waypoints.isNotEmpty()) {
        val links = officeWaypointLinks(scene.waypoints)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()
        queue.add(scene.waypoints.first().id)
        visited += scene.waypoints.first().id
        while (queue.isNotEmpty()) {
            links[queue.removeFirst()].orEmpty().forEach { link ->
                if (visited.add(link)) queue.add(link)
            }
        }
        require(visited.size == scene.waypoints.size) { "Waypoint graph is disconnected" }
    }
    scene.furniture.forEach { boundsInside("Furniture ${it.id}", it.bounds) }
    boundsInside("Task board", scene.boards.task.bounds)
    boundsInside("Objective board", scene.boards.objective.bounds)
    boundsInside("Water cooler", scene.ambient.waterCooler)
    boundsInside("Meeting screen", scene.ambient.meetingScreen)
    boundsInside("Server rack", scene.ambient.serverRack)
    boundsInside("Window", scene.ambient.window)
    require(inside(scene.ambient.clock.x, scene.ambient.clock.y)) { "Clock is outside the room" }
    scene.ambient.lamps.forEach { lamp ->
        require(inside(lamp.anchor.x, lamp.anchor.y)) { "Lamp anchor is outside the room" }
    }
    scene.ambient.windowLights.forEach { light ->
        require(scene.ambient.window.contains(scene.ambient.window.x + light.dx, scene.ambient.window.y + light.dy)) {
            "Window light is outside the window"
        }
    }
    scene.allSeats.forEach { seat ->
        require(inside(seat.x, seat.y)) { "Seat ${seat.id} is outside the room" }
        requireFloor("Seat ${seat.id}", seat.x, seat.y)
        require(seat.pose in seat.poses) { "Seat ${seat.id} does not support its default pose" }
        seat.roamOffset?.let {
            val roamX = seat.x + it.x
            val roamY = seat.y + it.y
            require(inside(roamX, roamY)) { "Seat ${seat.id} roams outside the room" }
            requireFloor("Seat ${seat.id} roam", roamX, roamY)
            requireUnblocked("Seat ${seat.id} roam", roamX, roamY)
        }
    }
    scene.desks.forEach {
        require(inside(it.screen.x, it.screen.y)) { "Desk ${it.id} has a screen outside the room" }
    }
    scene.zones.forEach { require(inside(it.anchor.x, it.anchor.y)) { "Zone ${it.id} is outside the room" } }
    val interactions = scene.interactions
    interactions.forEach { interaction ->
        val b = interaction.bounds
        require(b.width > 0 && b.height > 0 && inside(b.x, b.y) && inside(b.x + b.width - 1, b.y + b.height - 1)) {
            "Interaction ${interaction.id} is outside the room"
        }
        require(PixelOfficeScene.y(b.y.toFloat()) + b.height <= PixelOfficeScene.HEIGHT) {
            "Interaction ${interaction.id} is clipped by walkway compaction"
        }
    }
    interactions.forEachIndexed { index, first ->
        interactions.drop(index + 1).forEach { second ->
            val a = first.bounds
            val b = second.bounds
            // Compose projects the origin and retains target size; test the same visible bounds.
            val ay = PixelOfficeScene.y(a.y.toFloat())
            val by = PixelOfficeScene.y(b.y.toFloat())
            require(a.x >= b.x + b.width || b.x >= a.x + a.width || ay >= by + b.height || by >= ay + a.height) {
                "Interactions ${first.id} and ${second.id} overlap"
            }
        }
    }
    with(scene.overflow) {
        require(rows.isNotEmpty() && columns > 0 && spacingX > 0 && cycleOffsetY >= 0) { "Invalid overflow geometry" }
        require(rows.all { inside(startX, it) && inside(startX + (columns - 1) * spacingX, it) }) {
            "Overflow row is outside the room"
        }
        repeat(rows.size * columns) { index ->
            val anchor = anchor(index)
            requireFloor("Overflow anchor $index", anchor.x, anchor.y)
            requireUnblocked("Overflow anchor $index", anchor.x, anchor.y)
        }
    }
}
