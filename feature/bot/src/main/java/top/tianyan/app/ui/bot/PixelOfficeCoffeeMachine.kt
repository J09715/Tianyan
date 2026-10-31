package top.tianyan.app.ui.bot

/** Machine artwork in local coordinates; the counter and menu remain room fixtures. */
internal fun PixelOfficeDetail.Painter.coffeeMachine(machine: OfficeCoffeeMachineSpec) {
    val x = machine.x
    val y = machine.y
    r(x, y, 18, 18, ink)
    r(x + 1, y + 1, 16, 16, 0x9AA4AE)
    r(x + 2, y + 2, 14, 2, 0xC7D0D6)
    r(x + 2, y + 5, 14, 8, 0x5A6672)
    r(x + 3, y + 6, 5, 5, 0x3A4652)
    r(x + 4, y + 7, 3, 3, 0x7EC8E3)
    r(x + 9, y + 6, 5, 5, 0x2E3A46)
    r(x + 5, y + 12, 7, 3, ink); r(x + 6, y + 12, 5, 2, 0xB0BEC5)
    r(x + 2, y + 15, 14, 2, 0xADB2AA); r(x + 3, y + 16, 12, 1, 0x7A8790)
    r(x + 16, y + 9, 1, 7, 0xB0BEC5); r(x + 15, y + 16, 2, 1, 0x8A959E)
    cup(x + 6, y + 13)
    r(machine.indicator.x, machine.indicator.y, 1, 1, 0xE85D4C)
}
