package top.tianyan.app.harness

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A2uiSurfaceBus 单测：覆盖 render_surface 工具入参校验、载荷发布、
 * 同 surfaceId 覆盖与缓存上限淘汰、双转义自动修复、深度校验钩子、
 * sessionId 记录与用户事件格式化（与 ToolExecutor 的对接契约一致）。
 */
class A2uiSurfaceBusTest {

    @Before
    fun reset() {
        A2uiSurfaceBus.clear()
        A2uiSurfaceBus.installDeepValidator(null)
        A2uiSurfaceBus.userEventSink = null
        A2uiSurfaceBus.errorEventSink = null
        A2uiSurfaceBus.onSurfacesReleased = null
    }

    @Test
    fun `合法参数发布成功并进入总线`() {
        val (success, output) = A2uiSurfaceBus.publishFromTool(validArgs("s1", "看板一"))
        assertTrue(success)
        assertTrue(output.contains("s1"))
        val surfaces = A2uiSurfaceBus.surfaces.value
        assertEquals(1, surfaces.size)
        assertEquals("s1", surfaces.first().surfaceId)
        assertEquals("看板一", surfaces.first().title)
    }

    @Test
    fun `同 surfaceId 再次发布覆盖旧条目`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "旧标题"))
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "新标题"))
        val surfaces = A2uiSurfaceBus.surfaces.value
        assertEquals(1, surfaces.size)
        assertEquals("新标题", surfaces.first().title)
    }

    @Test
    fun `非法 surfaceId 校验失败且不入总线`() {
        val (success, output) = A2uiSurfaceBus.publishFromTool(validArgs("bad id!", "标题"))
        assertFalse(success)
        assertTrue(output.contains("surfaceId"))
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
    }

    @Test
    fun `messages 不是 JSON 数组时校验失败`() {
        val args = buildJsonObject {
            put("surfaceId", "s1")
            put("messages", """{"version":"v0.9"}""")
        }
        val (success, output) = A2uiSurfaceBus.publishFromTool(args)
        assertFalse(success)
        assertTrue(output.contains("数组"))
    }

    @Test
    fun `协议版本不在白名单时校验失败`() {
        val args = buildJsonObject {
            put("surfaceId", "s1")
            put("messages", A2uiSurfaceBus.sampleMessagesJson().replace("\"v0.9\"", "\"0.9\""))
        }
        val (success, output) = A2uiSurfaceBus.publishFromTool(args)
        assertFalse(success)
        assertTrue(output.contains("version"))
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
    }

    @Test
    fun `双重编码字符串自动解包`() {
        // 模型把整个数组包成一层 JSON 字符串：messages 内容是带引号转义的数组字面量
        val wrapped = JsonPrimitive(A2uiSurfaceBus.sampleMessagesJson()).toString()
        val args = buildJsonObject {
            put("surfaceId", "s1")
            put("messages", JsonPrimitive(wrapped))
        }
        val (success, output) = A2uiSurfaceBus.publishFromTool(args, "session-1")
        assertTrue("解包后应发布成功：$output", success)
        assertEquals(1, A2uiSurfaceBus.surfaces.value.size)
        assertTrue(A2uiSurfaceBus.surfaces.value.first().sessionId == "session-1")
    }

    @Test
    fun `引号二次转义自动修复`() {
        // 模型对整个数组做了 \" 转义（真机截图出现的错误模式）
        val escaped = A2uiSurfaceBus.sampleMessagesJson().replace("\"", "\\\"")
        val args = buildJsonObject {
            put("surfaceId", "s1")
            put("messages", JsonPrimitive(escaped))
        }
        val (success, output) = A2uiSurfaceBus.publishFromTool(args)
        assertTrue("修复后应发布成功：$output", success)
        assertEquals(1, A2uiSurfaceBus.surfaces.value.size)
    }

    @Test
    fun `deepValidator 失败原因回传且不入总线`() {
        A2uiSurfaceBus.installDeepValidator { "组件 Foo 不在 Catalog 中" }
        val (success, output) = A2uiSurfaceBus.publishFromTool(validArgs("s1", "标题"))
        assertFalse(success)
        assertTrue(output.contains("组件 Foo 不在 Catalog 中"))
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
    }

    @Test
    fun `deepValidator 通过时正常发布`() {
        A2uiSurfaceBus.installDeepValidator { null }
        val (success, _) = A2uiSurfaceBus.publishFromTool(validArgs("s1", "标题"))
        assertTrue(success)
    }

    @Test
    fun `超过缓存上限丢最旧`() {
        repeat(A2uiSurfaceBus.MAX_CACHED_SURFACES + 2) { index ->
            A2uiSurfaceBus.publishFromTool(validArgs("s$index", "界面$index"))
        }
        val surfaces = A2uiSurfaceBus.surfaces.value
        assertEquals(A2uiSurfaceBus.MAX_CACHED_SURFACES, surfaces.size)
        // 新载荷在前：最后发布的 s{MAX+1} 居首，最旧的 s0/s1 被淘汰
        assertEquals("s${A2uiSurfaceBus.MAX_CACHED_SURFACES + 1}", surfaces.first().surfaceId)
        assertEquals("s2", surfaces.last().surfaceId)
    }

    @Test
    fun `预览列表淘汰后仍能路由回会话，删会话时一并释放`() {
        repeat(A2uiSurfaceBus.MAX_CACHED_SURFACES + 1) { index ->
            A2uiSurfaceBus.publishFromTool(validArgs("s$index", "界面$index"), "session-a")
        }
        assertNull(A2uiSurfaceBus.surfaces.value.firstOrNull { it.surfaceId == "s0" })
        assertEquals("session-a", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s0"))?.sessionId)
        assertEquals("界面0", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s0"))?.title)
        val released = mutableListOf<String>()
        A2uiSurfaceBus.onSurfacesReleased = { released += it }
        A2uiSurfaceBus.releaseSession("session-a")
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
        assertNull(A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s0")))
        assertTrue(released.contains(A2uiSurfaceBus.engineSurfaceId("session-a", "s0")))
        assertEquals(A2uiSurfaceBus.MAX_CACHED_SURFACES + 1, released.distinct().size)
    }

    @Test
    fun `clear 清空总线`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "标题"), "session-a")
        A2uiSurfaceBus.clear()
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
        assertNull(A2uiSurfaceBus.routeFor("s1"))
    }

    @Test
    fun `clearPreview 收起列表并释放示例，聊天路由保留`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "标题"), "session-a")
        A2uiSurfaceBus.publishFromTool(validArgs("demo", "示例"), A2uiSurfaceBus.PREVIEW_SESSION_ID)
        val released = mutableListOf<String>()
        A2uiSurfaceBus.onSurfacesReleased = { released += it }
        A2uiSurfaceBus.clearPreview()
        assertTrue(A2uiSurfaceBus.surfaces.value.isEmpty())
        assertEquals("session-a", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s1"))?.sessionId)
        assertNull(A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId(A2uiSurfaceBus.PREVIEW_SESSION_ID, "demo")))
        assertEquals(listOf(A2uiSurfaceBus.engineSurfaceId(A2uiSurfaceBus.PREVIEW_SESSION_ID, "demo")), released)
    }

    @Test
    fun `不同会话的相同 surfaceId 各自保留路由`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "甲"), "session-a")
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "乙"), "session-b")
        assertEquals("甲", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s1"))?.title)
        assertEquals("乙", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-b", "s1"))?.title)
        assertEquals(listOf("s1", "s1"), A2uiSurfaceBus.surfaces.value.map { it.surfaceId }.sorted())
    }

    @Test
    fun `forgetEngineSurface 丢掉该界面的预览和路由`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "甲"), "session-a")
        A2uiSurfaceBus.publishFromTool(validArgs("s2", "乙"), "session-a")
        A2uiSurfaceBus.forgetEngineSurface(A2uiSurfaceBus.engineSurfaceId("session-a", "s1"))
        assertEquals(listOf("s2"), A2uiSurfaceBus.surfaces.value.map { it.surfaceId })
        assertNull(A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s1")))
        assertEquals("乙", A2uiSurfaceBus.routeFor(A2uiSurfaceBus.engineSurfaceId("session-a", "s2"))?.title)
    }

    @Test
    fun `releaseSession 只移除该会话并通知渲染器`() {
        A2uiSurfaceBus.publishFromTool(validArgs("s1", "甲"), "session-a")
        A2uiSurfaceBus.publishFromTool(validArgs("s2", "乙"), "session-b")
        val released = mutableListOf<String>()
        A2uiSurfaceBus.onSurfacesReleased = { released += it }
        A2uiSurfaceBus.releaseSession("session-a")
        A2uiSurfaceBus.releaseSession("missing")
        assertEquals(listOf("s2"), A2uiSurfaceBus.surfaces.value.map { it.surfaceId })
        assertEquals(listOf(A2uiSurfaceBus.engineSurfaceId("session-a", "s1")), released)
    }

    @Test
    fun `normalizeMessagesJson 归一化非法输入返回 null`() {
        assertNotNull(A2uiSurfaceBus.normalizeMessagesJson(A2uiSurfaceBus.sampleMessagesJson()))
        assertNull(A2uiSurfaceBus.normalizeMessagesJson("not json"))
        assertNull(A2uiSurfaceBus.normalizeMessagesJson(""))
    }

    @Test
    fun `用户事件格式化包含路由字段`() {
        val text = A2uiSurfaceBus.formatUserEvent(
            A2uiSurfaceBus.A2uiUserEvent(
                surfaceId = "s1",
                surfaceTitle = "对比表",
                sessionId = "session-9",
                componentId = "btnConfirm",
                eventName = "on_click",
                context = mapOf("row" to 3),
                timestamp = 123L,
            ),
        )
        assertTrue(text.contains("[A2UI 界面事件]"))
        assertTrue(text.contains("s1"))
        assertTrue(text.contains("btnConfirm"))
        assertTrue(text.contains("on_click"))
        assertTrue(text.contains("row"))
        assertFalse(text.contains("表单数据"))
    }

    @Test
    fun `用户事件格式化包含表单数据且过长时截断`() {
        val text = A2uiSurfaceBus.formatUserEvent(
            A2uiSurfaceBus.A2uiUserEvent(
                surfaceId = "s1",
                surfaceTitle = "报名表",
                sessionId = "session-9",
                componentId = "submit",
                eventName = "click",
                context = emptyMap(),
                timestamp = 1L,
                dataModel = mapOf("__tianyan_inputs" to mapOf("name" to "Ada")),
            ),
        )
        assertTrue(text.contains("，表单数据 dataModel="))
        assertTrue(text.contains("__tianyan_inputs"))
        assertTrue(text.contains("Ada"))
        assertFalse(text.contains("已截断"))

        val blob = "x".repeat(A2uiSurfaceBus.MAX_DATA_MODEL_CHARS + 500)
        val truncated = A2uiSurfaceBus.formatUserEvent(
            A2uiSurfaceBus.A2uiUserEvent(
                surfaceId = "s1",
                surfaceTitle = "报名表",
                sessionId = "session-9",
                componentId = "submit",
                eventName = "click",
                context = emptyMap(),
                timestamp = 1L,
                dataModel = mapOf("blob" to blob),
            ),
        )
        assertTrue(truncated.contains("，表单数据 dataModel="))
        assertTrue(truncated.contains("…(已截断)"))
        assertFalse(truncated.contains(blob))
    }

    @Test
    fun `引擎错误事件格式化包含修复指引`() {
        val text = A2uiSurfaceBus.formatErrorEvent(
            A2uiSurfaceBus.A2uiErrorEvent(
                surfaceId = "s1",
                surfaceTitle = "方块",
                sessionId = "session-9",
                code = "validation_failed",
                message = "组件 box 校验失败",
            ),
        )
        assertTrue(text.contains("[A2UI 界面错误]"))
        assertTrue(text.contains("s1"))
        assertTrue(text.contains("validation_failed"))
        assertTrue(text.contains("组件 box 校验失败"))
        assertTrue(text.contains("id=root"))
    }

    private fun validArgs(surfaceId: String, title: String) = buildJsonObject {
        put("surfaceId", surfaceId)
        put("title", title)
        put("messages", A2uiSurfaceBus.sampleMessagesJson())
    }
}
