package top.tianyan.app.ui.bot

/** List order is seat allocation priority. Animation phases stay stable when instances are added. */
internal fun defaultOfficeDesks() = listOf(
    OfficeDeskSpec("desk", 18, 214, 0xFF7EC8E3.toInt(), 0),
    OfficeDeskSpec("desk-b", 62, 214, 0xFF8FD18A.toInt(), 1),
    OfficeDeskSpec("desk-c", 18, 262, 0xFFC9A6E8.toInt(), 4),
    OfficeDeskSpec("desk-d", 62, 262, 0xFF7EC8E3.toInt(), 5),
    // The existing right-hand row has a shared shadow. Two local segments preserve its pixels.
    OfficeDeskSpec("desk-e", 116, 214, 0xFFC9A6E8.toInt(), 2, false, OfficeDeskShadow(2, 21, 38)),
    OfficeDeskSpec("desk-f", 156, 214, 0xFF7EC8E3.toInt(), 3, false, OfficeDeskShadow(0, 21, 26)),
    OfficeDeskSpec("desk-g", 116, 262, 0xFF8FD18A.toInt(), 6, false, null),
    OfficeDeskSpec("desk-h", 156, 262, 0xFFC9A6E8.toInt(), 7, false, null),
)

internal fun defaultOfficeSeats(): List<OfficeSeat> {
    val meetingPoses = setOf(OfficePose.SIT, OfficePose.TYPE)
    return listOf(
        OfficeSeat("meet-a", 114, 138, OfficePose.SIT, OfficeSeatGroup.MEET, meetingPoses, roamOffset = OfficeAnchor(-18, 12)),
        OfficeSeat("meet-b", 136, 138, OfficePose.SIT, OfficeSeatGroup.MEET, meetingPoses, roamOffset = OfficeAnchor(-18, 14)),
        OfficeSeat("meet-c", 158, 138, OfficePose.SIT, OfficeSeatGroup.MEET, meetingPoses, roamOffset = OfficeAnchor(18, 12)),
        OfficeSeat("meet-d", 96, 138, OfficePose.SIT, OfficeSeatGroup.MEET, meetingPoses, roamOffset = OfficeAnchor(80, 12)),
        OfficeSeat("board", 92, 164, OfficePose.THINK, OfficeSeatGroup.THINK),
        OfficeSeat("sofa-l", 40, 376, OfficePose.SIT, OfficeSeatGroup.REST, roamOffset = OfficeAnchor(-16, 10)),
        OfficeSeat("sofa-r", 116, 378, OfficePose.SIT, OfficeSeatGroup.REST, roamOffset = OfficeAnchor(-16, 10)),
        OfficeSeat("rug", 84, 400, OfficePose.SIT, OfficeSeatGroup.REST, roamOffset = OfficeAnchor(-16, -8)),
        OfficeSeat("coffee-a", 156, 380, OfficePose.SIT, OfficeSeatGroup.COFFEE,
            idleAction = OfficeIdleAction.SIP, roamOffset = OfficeAnchor(-18, 10)),
        OfficeSeat("coffee-b", 178, 380, OfficePose.SIT, OfficeSeatGroup.COFFEE,
            idleAction = OfficeIdleAction.SIP, roamOffset = OfficeAnchor(-12, 10)),
        OfficeSeat("window", 174, 144, OfficePose.STAND),
        OfficeSeat("poster", 164, 380, OfficePose.STAND),
        OfficeSeat("hall", 70, 410, OfficePose.STAND),
    )
}

internal fun defaultOfficeCats() = listOf(
    OfficeCatSpec("office-cat-lobby", OfficeCatStyle.SLEEPING, 73, 54),
    OfficeCatSpec("office-cat-sofa", OfficeCatStyle.PERCHED, 48, 339),
)

internal fun defaultOfficeZones() = listOf(
    OfficeZoneSpec("think", OfficeZoneKind.THINK, OfficeAnchor(8, 69)),
    OfficeZoneSpec("meet", OfficeZoneKind.MEET, OfficeAnchor(112, 69)),
    OfficeZoneSpec("work", OfficeZoneKind.WORK, OfficeAnchor(104, 183)),
    OfficeZoneSpec("coffee", OfficeZoneKind.COFFEE, OfficeAnchor(146, 318)),
    OfficeZoneSpec("rest", OfficeZoneKind.REST, OfficeAnchor(6, 318)),
)

/** Room-space bands that mirror the four cutaway levels after the walkways are compacted. */
internal fun defaultOfficeFloors() = listOf(
    OfficeFloorSpec("lobby", 0, 66),
    OfficeFloorSpec("think", 66, 178),
    OfficeFloorSpec("work", 178, 306),
    OfficeFloorSpec("rest", 306, PixelOfficeRoom.HEIGHT),
)

/** Walkway graph in room coordinates. Vertical links run along the left stair column. */
internal fun defaultOfficeWaypoints() = listOf(
    OfficeWaypointSpec("think-left", 16, 150, listOf("think-mid", "work-left")),
    OfficeWaypointSpec("think-mid", 96, 150, listOf("think-left", "think-right")),
    OfficeWaypointSpec("think-right", 176, 150, listOf("think-mid")),
    OfficeWaypointSpec("work-left", 16, 288, listOf("work-mid", "think-left", "rest-left")),
    OfficeWaypointSpec("work-mid", 96, 288, listOf("work-left", "work-right")),
    OfficeWaypointSpec("work-right", 176, 288, listOf("work-mid")),
    OfficeWaypointSpec("rest-left", 16, 420, listOf("rest-mid", "work-left")),
    OfficeWaypointSpec("rest-mid", 96, 420, listOf("rest-left", "rest-right")),
    OfficeWaypointSpec("rest-right", 176, 420, listOf("rest-mid")),
)

/** Floor-standing footprints used for anchor coverage checks; wall art is non-blocking. */
internal fun defaultOfficeFurniture() = listOf(
    OfficeFurnitureSpec("shelf-lobby", OfficeBounds(8, 16, 24, 36)),
    OfficeFurnitureSpec("plant-lobby", OfficeBounds(36, 28, 12, 17)),
    OfficeFurnitureSpec("lobby-frame", OfficeBounds(56, 16, 52, 36), blocksWalking = false),
    OfficeFurnitureSpec("window", OfficeBounds(118, 12, 68, 42), blocksWalking = false),
    OfficeFurnitureSpec("whiteboard", OfficeBounds(8, 84, 78, 36), blocksWalking = false),
    OfficeFurnitureSpec("meeting-table", OfficeBounds(108, 108, 70, 22)),
    OfficeFurnitureSpec("plant-think", OfficeBounds(174, 124, 12, 17)),
    OfficeFurnitureSpec("plant-work", OfficeBounds(96, 236, 12, 17)),
    OfficeFurnitureSpec("sofa", OfficeBounds(14, 352, 52, 26)),
    OfficeFurnitureSpec("lounge-table", OfficeBounds(74, 368, 26, 16)),
    OfficeFurnitureSpec("beanbag", OfficeBounds(108, 360, 20, 18)),
    OfficeFurnitureSpec("plant-rest", OfficeBounds(134, 356, 12, 17)),
    OfficeFurnitureSpec("stairs", OfficeBounds(8, 400, 28, 30), blocksWalking = false),
    OfficeFurnitureSpec("coffee-bar", OfficeBounds(146, 348, 42, 18)),
    OfficeFurnitureSpec("server-rack", OfficeBounds(136, 410, 14, 32)),
    OfficeFurnitureSpec("water-cooler", OfficeBounds(93, 412, 15, 30)),
    OfficeFurnitureSpec("coat-rack", OfficeBounds(116, 409, 13, 32)),
    OfficeFurnitureSpec("doormat", OfficeBounds(43, 431, 32, 10), blocksWalking = false),
)
