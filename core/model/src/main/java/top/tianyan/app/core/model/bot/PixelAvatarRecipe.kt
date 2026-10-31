package top.tianyan.app.core.model.bot

import kotlin.random.Random

/** Versioned, portable appearance data. Runtime activity never changes this recipe. */
data class PixelAvatarRecipe(
    val kind: Int = 0,
    val hair: Int = 0,
    val eyes: Int = 0,
    val mouth: Int = 0,
    val outfit: Int = 0,
    val accessory: Int = 0,
    val skinColor: Int = 0,
    val hairColor: Int = 0,
    val outfitColor: Int = 0,
    val backdrop: Int = 0,
) {
    init {
        require(kind in 0..3 && hair in 0..4 && eyes in 0..3 && mouth in 0..3)
        require(outfit in 0..3 && accessory in 0..6)
        require(skinColor in 0..4 && hairColor in 0..7 && outfitColor in 0..7 && backdrop in 0..5)
    }

    fun encode(): String = PREFIX + listOf(kind, hair, eyes, mouth, outfit, accessory,
        skinColor, hairColor, outfitColor, backdrop).joinToString(",")

    companion object {
        private const val PREFIX = "tianyan-pixel:v1:"

        fun decode(value: String): PixelAvatarRecipe? {
            if (!value.startsWith(PREFIX) || value.length > 64) return null
            return runCatching {
                val parts = value.removePrefix(PREFIX).split(',').map { it.toInt() }
                require(parts.size == 10)
                PixelAvatarRecipe(parts[0], parts[1], parts[2], parts[3], parts[4], parts[5],
                    parts[6], parts[7], parts[8], parts[9])
            }.getOrNull()
        }

        fun random(random: Random = Random.Default): PixelAvatarRecipe = PixelAvatarRecipe(
            random.nextInt(4), random.nextInt(5), random.nextInt(4), random.nextInt(4),
            random.nextInt(4), random.nextInt(7), random.nextInt(5), random.nextInt(8),
            random.nextInt(8), random.nextInt(6),
        )

        fun fromName(name: String): PixelAvatarRecipe = random(Random(name.hashCode()))
    }
}
