package top.tianyan.app.core.model

enum class ThemePaletteStyle { TONAL_SPOT, VIBRANT, EXPRESSIVE, NEUTRAL }

data class ThemePalette(
    val colorId: String = "default",
    val style: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
)
