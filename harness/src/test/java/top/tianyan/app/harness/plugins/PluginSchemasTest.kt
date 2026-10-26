package top.tianyan.app.harness.plugins

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PluginSchemasTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun nestedObjectAndArrayItemsAreActuallyValidated() {
        val schema = json("""{"type":"object","properties":{"data":{"type":"object","properties":{"count":{"type":"integer","minimum":1}},"required":["count"]},"items":{"type":"array","items":{"type":"boolean"},"maxItems":2}},"required":["data"]}""")
        PluginSchemas.requireSupported(schema)
        assertTrue(PluginSchemas.valid(schema, json("""{"data":{"count":1},"items":[true,false]}""")))
        listOf("""{"data":{"count":"1"}}""", """{"data":{}}""", """{"data":{"count":0}}""", """{"data":{"count":1,"other":true}}""", """{"data":{"count":1},"items":["true"]}""", """{"data":{"count":1},"items":[true,true,true]}""").forEach { assertFalse(it, PluginSchemas.valid(schema, json(it))) }
    }
    @Test fun unsupportedConstraintsAreRejectedBeforeRegistration() {
        listOf("""{"type":"object","oneOf":[]}""", """{"type":"object","properties":[]}""", """{"type":"object","properties":{"text":{"type":"string","pattern":".*"}}}""").forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) { PluginSchemas.requireSupported(json(raw)) }
        }
    }
    @Test fun exactLiteralNamesStringsAndEnumTypesArePreserved() {
        val schema = json("""{"type":"object","properties":{"input.name":{"type":"string","minLength":2,"maxLength":4,"enum":["true"]}},"required":["input.name"]}""")
        PluginSchemas.requireSupported(schema)
        assertTrue(PluginSchemas.valid(schema, json("""{"input.name":"true"}""")))
        assertFalse(PluginSchemas.valid(schema, json("""{"input":{"name":"true"}}""")))
        assertFalse(PluginSchemas.valid(schema, json("""{"input.name":true}""")))
    }
}
