package top.tianyan.app.ui.bot

/** Cat artwork is local to the same instance that owns its hit target and animation anchors. */
internal fun PixelOfficeDetail.Painter.cat(cat: OfficeCatSpec) {
    val x = cat.x
    val y = cat.y
    when (cat.style) {
        OfficeCatStyle.SLEEPING -> {
            r(x, y + 2, 17, 3, 0xB9564A)
            r(x + 1, y + 2, 15, 1, 0xE07A62)
            r(x, y + 4, 17, 1, 0x8F3E36)
            r(x + 5, y + 3, 12, 3, 0xB97642)
            r(x + 7, y + 1, 8, 3, 0xD69E5A)
            r(x + 4, y + 1, 5, 4, paper)
            r(x + 4, y, 1, 2, 0xB97642); r(x + 8, y, 1, 2, 0xB97642)
            r(x + 5, y + 3, 1, 0.4, ink); r(x + 7, y + 3, 1, 0.4, ink)
            r(x + 12, y + 2, 1, 2, 0x8D5A36)
        }
        OfficeCatStyle.PERCHED -> {
            r(x + 2, y + 5, 15, 11, ink)
            r(x + 3, y + 6, 13, 9, 0xE0A45C)
            r(x + 4, y + 6, 10, 2, 0xF2C07E)
            r(x + 7, y + 9, 4, 1, 0xC9873E)
            r(x + 10, y + 11, 3, 1, 0xC9873E)
            r(x + 13, y + 8, 3, 1, 0xC9873E)
            r(x + 1, y + 1, 9, 9, ink)
            r(x + 2, y + 2, 7, 7, 0xF0B86A)
            r(x + 1, y, 2, 3, ink)
            r(x + 7, y, 2, 3, ink)
            r(x + 1, y + 1, 2, 2, 0xD99A52)
            r(x + 7, y + 1, 2, 2, 0xD99A52)
            r(x + 3, y + 5, 2, 1, ink)
            r(x + 6, y + 5, 2, 1, ink)
            r(x + 5, y + 7, 1, 1, 0xC9873E)
            r(x + 2, y + 2, 3, 1, 0xF6D4A0)
            r(x + 4, y + 8, 4, 2, 0xF6E2C0)
            r(x + 16, y + 10, 3, 1, 0xD99A52)
            r(x + 18, y + 11, 2, 1, 0xC9873E)
            r(x + 19, y + 12, 2, 4, 0xC9873E)
            r(x + 19, y + 15, 2, 2, 0xD99A52)
        }
    }
}
