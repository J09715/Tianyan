package top.tianyan.app.harness

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** load_skill 工具契约（借鉴 OpenMinis 的按需技能加载）。 */
class LoadSkillToolContractTest {

    @Test
    fun `load_skill exposes name lookup parameter`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "load_skill" }
        val encoded = tool.function.parameters.toString()
        assertTrue(encoded.contains("\"name\""))
        assertTrue(tool.function.description.contains("按需加载"))
    }

    @Test
    fun `load_skill exposes optional skill relative resource path`() {
        val tool = ProviderClient.TOOLS.single { it.function.name == "load_skill" }
        val properties = tool.function.parameters["properties"]!!.jsonObject
        assertTrue(properties.containsKey("name"))
        assertTrue(properties.containsKey("path"))
        val required = tool.function.parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("name"), required)
        assertTrue(tool.function.description.contains("SKILL.md"))
    }
}
