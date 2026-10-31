package top.tianyan.app.ui.bot

/** Rest floor and entry hall props, split out to keep the room painter compact. */
internal fun PixelOfficeDetail.Painter.lounge() {
    // Wall print sits higher so the sofa cat can perch below it.
    r(50, 325, 33, 13, ink); r(51, 326, 31, 11, 0x87BCD0)
    r(51, 326, 31, 0.5, 0xAAD8E9)
    r(52, 333, 29, 3, 0x779565); r(62, 329, 8, 2, paper)
    r(16, 353, 48, 1, 0xFFD18B)
    for (x in listOf(22, 42)) {
        r(x, 355, 18, 7, 0xF1B15C); r(x, 355, 18, 0.7, 0xFFD18B)
        r(x + 2, 360, 14, 0.5, 0xC98543)
    }
    // A second, larger cat perches on the sofa back, silhouetted against the wall.
    scene.cats.filter { it.style == OfficeCatStyle.PERCHED }.forEach { cat(it) }
    // Slippers by the sofa.
    r(55, 375, 5, 2, 0x8A6A4F)
    r(61, 376, 5, 2, 0x8A6A4F)
    r(56, 375, 3, 1, 0xC9A57A)
    r(62, 376, 3, 1, 0xC9A57A)
    r(110, 367, 16, 1, 0x97C879)
    r(74, 368, 26, 1, light); cup(78, 364)
    r(87, 365, 9, 3, 0x608B8D); r(88, 366, 7, 0.7, paper)
    // Framed print between the lamp and the poster.
    r(101, 321, 28, 21, ink)
    r(102, 322, 26, 19, 0x8D6849)
    r(104, 324, 22, 15, 0xEAF1E4)
    r(104, 332, 22, 7, 0x9FC6A0)
    r(106, 330, 6, 5, 0x6F8FA8)
    r(112, 327, 8, 8, 0x557A96)
    r(120, 331, 5, 4, 0x6F8FA8)
    r(113, 327, 5, 2, 0xF6F0E4)
    r(114, 329, 3, 1, 0xD9E6EA)
    r(121, 326, 3, 3, 0xF3D36B)
    r(104, 324, 22, 0.4, 0xFFF8EE)
    r(102, 322, 0.5, 19, 0xB99B76)
    shelf(146, 386, 35, 22)
    for (i in 0..6) {
        r(6 + i * 5, 398 + i * 5, 26, 2, 0x7A6C60)
        r(6 + i * 5, 400 + i * 5, 26, 3, 0xC0AC8E)
        r(7 + i * 5, 388 + i * 5, 0.7, 12, ink)
        r(7 + i * 5, 387 + i * 5, 6, 1, ink)
    }
}

internal fun PixelOfficeDetail.Painter.hallway() {
    // Water cooler with a blue bottle.
    r(93, 412, 15, 30, ink)
    r(94, 413, 13, 28, 0xD9DEE2)
    r(94, 413, 13, 0.7, 0xF4F7F8)
    r(95, 414, 11, 12, 0xBFD8E8)
    r(96, 415, 9, 10, 0x8EC8F0)
    r(97, 416, 7, 8, 0x5FA4D6)
    r(100, 417, 1, 7, 0x8EC8F0)
    r(94, 427, 13, 2, 0xADB2AA)
    r(96, 430, 3, 2, 0x3A4652)
    r(102, 430, 3, 2, 0x3A4652)
    r(94, 441, 13, 1, 0x8A959E)
    // Coat rack with two jackets and a hat.
    r(121, 412, 2, 29, ink)
    r(122, 413, 1, 27, wood)
    r(117, 414, 9, 1, ink)
    r(126, 416, 6, 1, ink)
    r(116, 416, 7, 14, ink)
    r(117, 417, 5, 12, 0x8FCFC2)
    r(118, 418, 1, 9, 0xB7E3D8)
    r(124, 418, 7, 12, ink)
    r(125, 419, 5, 10, 0xE08AA0)
    r(126, 420, 1, 7, 0xF2B7C4)
    r(118, 410, 8, 2, ink)
    r(119, 409, 6, 2, 0x54627A)
    // Woven doormat at the stair landing.
    r(43, 431, 32, 10, ink)
    r(44, 432, 30, 8, 0xB19069)
    r(46, 434, 26, 4, 0xD6C7A3)
    for (x in 47..70 step 3) r(x, 433, 0.5, 6, 0x9E7E5A)
    r(45, 432, 28, 0.4, 0xE3D4B2)
}

