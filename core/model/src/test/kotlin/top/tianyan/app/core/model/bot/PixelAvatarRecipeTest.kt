package top.tianyan.app.core.model.bot

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class PixelAvatarRecipeTest {
    @Test fun `appearance survives export serialization and preserves hidden hair across character changes`() {
        val original = PixelAvatarRecipe(kind = 0, hair = 3, hairColor = 6, accessory = 2)
        val manifest = BotManifest(name = "Chief", role = "lead", systemPrompt = "persona", avatarSeed = original.encode())
        val restored = Json.decodeFromString(BotManifest.serializer(), Json.encodeToString(BotManifest.serializer(), manifest))
        val recipe = requireNotNull(PixelAvatarRecipe.decode(restored.avatarSeed!!))
        assertEquals(original, recipe)
        assertEquals(original, recipe.copy(kind = 1).copy(kind = 0))
        assertEquals(original, recipe.copy(kind = 2).copy(kind = 0))
        assertEquals(original, recipe.copy(kind = 3).copy(kind = 0))
    }

    @Test fun `malformed unsupported and out of range recipes fail closed`() {
        listOf("Chief", "data:image/png;base64,AAAA", "tianyan-pixel:v2:0,0,0,0,0,0,0,0,0,0",
            "tianyan-pixel:v1:99,0,0,0,0,0,0,0,0,0", "tianyan-pixel:v1:0,0",
            "tianyan-pixel:v1:0,0,0,0,0,0,0,0,0,0,1", "tianyan-pixel:v1:" + "0".repeat(1000))
            .forEach { assertNull(it, PixelAvatarRecipe.decode(it)) }
    }

    @Test fun `every visible setting changes the drawing and random recipes remain bounded`() {
        val base = PixelAvatarRecipe()
        val cells = PixelAvatarArt.cells(base)
        listOf(
            base.copy(kind = 1), base.copy(kind = 2), base.copy(kind = 3),
            base.copy(hair = 1), base.copy(hair = 2), base.copy(hair = 3), base.copy(hair = 4),
            base.copy(eyes = 1), base.copy(eyes = 2), base.copy(eyes = 3),
            base.copy(mouth = 1), base.copy(mouth = 2), base.copy(mouth = 3),
            base.copy(outfit = 1), base.copy(outfit = 2), base.copy(outfit = 3),
            base.copy(accessory = 1), base.copy(accessory = 2), base.copy(accessory = 3),
            base.copy(accessory = 4), base.copy(accessory = 5), base.copy(accessory = 6),
            base.copy(skinColor = 4), base.copy(hairColor = 7), base.copy(outfitColor = 7), base.copy(backdrop = 5),
        ).forEach { assertFalse(it.toString(), cells.contentEquals(PixelAvatarArt.cells(it))) }

        repeat(500) {
            val recipe = PixelAvatarRecipe.random()
            assertEquals(recipe, PixelAvatarRecipe.decode(recipe.encode()))
            val image = PixelAvatarArt.cells(recipe)
            assertEquals(32 * 32, image.size)
            assertTrue(image.all { color -> color ushr 24 == 255 })
        }
        assertEquals(PixelAvatarRecipe.fromName("阿策"), PixelAvatarRecipe.fromName("阿策"))
    }

    @Test fun `agent state and frame change pixel art dynamically`() {
        val base = PixelAvatarRecipe()
        val idle = PixelAvatarArt.cells(base, BotAvatarState.IDLE)
        val thinking = PixelAvatarArt.cells(base, BotAvatarState.THINKING)
        val executing0 = PixelAvatarArt.cells(base, BotAvatarState.EXECUTING, frame = 0)
        val executing1 = PixelAvatarArt.cells(base, BotAvatarState.EXECUTING, frame = 1)
        val blocked = PixelAvatarArt.cells(base, BotAvatarState.BLOCKED)
        val success = PixelAvatarArt.cells(base, BotAvatarState.SUCCESS)
        val failed = PixelAvatarArt.cells(base, BotAvatarState.FAILED)

        assertFalse("thinking modifies drawing", idle.contentEquals(thinking))
        assertFalse("executing modifies drawing", idle.contentEquals(executing0))
        assertFalse("executing frames animate", executing0.contentEquals(executing1))
        assertFalse("blocked modifies drawing", idle.contentEquals(blocked))
        assertFalse("success modifies drawing", idle.contentEquals(success))
        assertFalse("failed modifies drawing", idle.contentEquals(failed))
    }
}
