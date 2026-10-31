package top.tianyan.app.feature.a2uipoc

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TianyanA2uiInputNormalizerTest {

    private fun payload(component: String, valueJson: String, id: String = "c1") =
        "[{\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"catalog.json\"}}," +
            "{\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"$id\"]}," +
            "{\"id\":\"$id\",\"component\":\"$component\",\"label\":\"L\",\"value\":$valueJson}]}}]"

    /** 与 [payload] 相同，但组件**不带 value 字段**。 */
    private fun missingValuePayload(component: String, id: String = "c1") =
        "[{\"createSurface\":{\"surfaceId\":\"s1\",\"catalogId\":\"catalog.json\"}}," +
            "{\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"$id\"]}," +
            "{\"id\":\"$id\",\"component\":\"$component\",\"label\":\"L\"}]}}]"

    /** 取「非 root」的那个组件（与消息条数无关，避免用固定下标）。 */
    private fun component(json: String) =
        Json.parseToJsonElement(json).jsonArray
            .first { it.jsonObject.containsKey("updateComponents") }
            .jsonObject["updateComponents"]!!.jsonObject["components"]!!.jsonArray
            .first { it.jsonObject["id"]!!.jsonPrimitive.content != "root" }
            .jsonObject

    private fun seed(json: String) =
        Json.parseToJsonElement(json).jsonArray.last().jsonObject["updateDataModel"]!!.jsonObject

    @Test
    fun `constant value is rewritten to a proxy path and seeded into the data model`() {
        val result = TianyanA2uiInputNormalizer.normalize(payload("TextField", "\"hello\""))

        assertEquals(1, result.fixedCount)
        assertEquals(
            "/__tianyan_inputs/c1",
            component(result.messagesJson)["value"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
        assertEquals(setOf("c1"), result.paths["s1"]!!.keys)
        assertEquals("s1", seed(result.messagesJson)["surfaceId"]!!.jsonPrimitive.content)
        assertEquals("hello", seed(result.messagesJson)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `all five value component types are normalized`() {
        listOf("TextField", "CheckBox", "ChoicePicker", "Slider", "DateTimeInput").forEach { type ->
            val result = TianyanA2uiInputNormalizer.normalize(payload(type, "\"v\""))
            assertEquals("$type 应被归一化", 1, result.fixedCount)
        }
    }

    @Test
    fun `already data bound value is left untouched and normalize is idempotent`() {
        val result = TianyanA2uiInputNormalizer.normalize(payload("TextField", "{\"path\":\"/form/name\"}"))

        assertEquals(0, result.fixedCount)
        assertTrue(result.paths.isEmpty())
        assertEquals(
            "/form/name",
            component(result.messagesJson)["value"]!!.jsonObject["path"]!!.jsonPrimitive.content,
        )
        // 幂等：重复归一化结果一致
        assertEquals(result.messagesJson, TianyanA2uiInputNormalizer.normalize(result.messagesJson).messagesJson)
    }

    @Test
    fun `createSurface has sendDataModel forced on so values return with events`() {
        val result = TianyanA2uiInputNormalizer.normalize(payload("TextField", "\"v\""))

        assertTrue(result.dataModelForced)
        val createSurface =
            Json.parseToJsonElement(result.messagesJson).jsonArray.first()
                .jsonObject["createSurface"]!!.jsonObject
        assertTrue(createSurface["sendDataModel"]!!.jsonPrimitive.content.toBoolean())
        // 已显式打开时不再重复标记
        assertFalse(TianyanA2uiInputNormalizer.normalize(result.messagesJson).dataModelForced)
    }

    @Test
    fun `non value components and unrelated messages are untouched`() {
        val text = "[{\"updateComponents\":{\"surfaceId\":\"s1\",\"components\":[" +
            "{\"id\":\"root\",\"component\":\"Column\",\"children\":[\"t1\"]}," +
            "{\"id\":\"t1\",\"component\":\"Text\",\"text\":\"hi\",\"value\":\"keep\"}]}}]"
        val result = TianyanA2uiInputNormalizer.normalize(text)

        assertEquals(0, result.fixedCount)
        assertEquals("keep", component(result.messagesJson)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `component id is escaped per RFC 6901 in the proxy path`() {
        val result = TianyanA2uiInputNormalizer.normalize(payload("TextField", "\"v\"", id = "a/b~c"))
        // `~` 先于 `/`：a/b~c → a/b~0c → a~1b~0c。先替换 `/` 会得到 a~01b~0c。
        val path = "/__tianyan_inputs/a~1b~0c"

        assertEquals(path, component(result.messagesJson)["value"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals(path, seed(result.messagesJson)["path"]!!.jsonPrimitive.content)
        assertEquals("a/b~c", component(result.messagesJson)["id"]!!.jsonPrimitive.content)
        assertEquals(path, result.paths["s1"]!!["a/b~c"])
    }

    @Test
    fun `Text field without value gets a proxied empty seed so it stays interactive`() {
        val result = TianyanA2uiInputNormalizer.normalize(missingValuePayload("TextField"))

        assertEquals(1, result.fixedCount)
        val value = component(result.messagesJson)["value"]!!.jsonObject
        assertEquals("/__tianyan_inputs/c1", value["path"]!!.jsonPrimitive.content)
        assertEquals("", seed(result.messagesJson)["value"]!!.jsonPrimitive.content)
        // 幂等：补种后再次归一化不再改动
        assertEquals(0, TianyanA2uiInputNormalizer.normalize(result.messagesJson).fixedCount)
    }

    @Test
    fun `required-value components missing value are left for render-time validation`() {
        // 这四个的 value 在官方 Catalog 里是必填，缺了属于模型漏写，应交给渲染期校验如实报错，
        // 归一化器不替它猜默认值。
        listOf("CheckBox", "ChoicePicker", "Slider", "DateTimeInput").forEach { type ->
            val result = TianyanA2uiInputNormalizer.normalize(missingValuePayload(type))
            assertEquals("$type 缺 value 不应被静默补默认", 0, result.fixedCount)
            assertFalse(component(result.messagesJson).containsKey("value"))
        }
    }

    @Test
    fun `malformed payload is returned unchanged and does not throw`() {
        listOf(
            "not json",
            """{"not":"array"}""",
            "[1]",
            """[{"updateComponents":{"surfaceId":{"nested":true},"components":[]}}]""",
        ).forEach { raw ->
            val result = TianyanA2uiInputNormalizer.normalize(raw)
            assertEquals(raw, result.messagesJson)
            assertEquals(0, result.fixedCount)
            assertTrue(result.paths.isEmpty())
            assertFalse(result.dataModelForced)
        }
    }
}
