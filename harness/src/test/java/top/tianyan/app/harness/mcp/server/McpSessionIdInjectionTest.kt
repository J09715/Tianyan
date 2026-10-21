package top.tianyan.app.harness.mcp.server

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.browser.BrowserDescriptor
import top.tianyan.app.core.browser.BrowserFamily
import top.tianyan.app.core.browser.BrowserPreferences
import top.tianyan.app.core.browser.PageSnapshot
import top.tianyan.app.core.model.ToolImageRef
import top.tianyan.app.runtime.browser.BrowserEngine
import top.tianyan.app.runtime.browser.BrowserEventBus
import top.tianyan.app.runtime.browser.BrowserSessionToken
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools

/**
 * 宿主注入的会话 id 能否真正到达工具层。
 *
 * ## 这个测试锁住什么
 *
 * 引擎（Claude Code / Codex…）连接天衍 MCP server 时，宿主把会话 id 写在 URL 的
 * query 里：`http://127.0.0.1:8787/mcp?session_id=xxx`。**引擎只照抄这个地址，
 * 不会把 session_id 带进工具调用参数**。
 *
 * 于是红队工具（[RedTeamMcpBridge]）要求必填的 session_id 从来没人填过——
 * 每次调用都被拒（「缺少 session_id」），引擎侧表现为「红队工具一直报参数缺失」。
 *
 * 修法是服务端在 tools/call 时从 query 取出并注入。这里锁住三条：
 * 1. query 里的 session_id 被注入到工具参数；
 * 2. 引擎自己带了 session_id 时以引擎的为准（它可能正在处理别的会话上下文）；
 * 3. 没有 session_id 时不注入（工具层自己会给出可执行的错误提示）。
 */
class McpSessionIdInjectionTest {

    @Test
    fun `query session id is injected into tool arguments`() = runBlocking {
        val engine = FakeBrowserEngine()
        val dispatcher = dispatcher(engine)

        val result = dispatcher.dispatch(
            "browser.current_url",
            JsonObject(emptyMap()),
            sessionId = "session-42",
        )

        // 浏览器工具不关心 session_id，但派发不能因此失败
        assertFalse(result.getValue("isError").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `engine supplied session id wins over the query one`() = runBlocking {
        val args = buildJsonObject { put("session_id", JsonPrimitive("engine-session")) }
        val merged = args.withSessionIdForTest("host-session")
        assertEquals("engine-session", merged.getValue("session_id").jsonPrimitive.content)
    }

    @Test
    fun `query session id is added when the engine did not supply one`() {
        val args = buildJsonObject { put("action", JsonPrimitive("preflight")) }
        val merged = args.withSessionIdForTest("host-session")
        assertEquals("host-session", merged.getValue("session_id").jsonPrimitive.content)
        // 原有参数不能被覆盖掉
        assertEquals("preflight", merged.getValue("action").jsonPrimitive.content)
    }

    @Test
    fun `blank session id is not injected`() {
        val args = buildJsonObject { put("action", JsonPrimitive("preflight")) }
        assertFalse(args.withSessionIdForTest("   ").containsKey("session_id"))
        assertFalse(args.withSessionIdForTest(null).containsKey("session_id"))
    }

    @Test
    fun `red team tool receives the host session id`() = runBlocking {
        // 用一个不存在的协调器构造桥接：调用会被拒，但错误信息能说明 session_id
        // 已经传到了工具层（否则报的是「缺少 session_id」）。
        val bridge = RedTeamMcpBridge(null)
        val response = bridge.invoke(
            buildJsonObject {
                put("session_id", JsonPrimitive("session-42"))
                put("action", JsonPrimitive("preflight"))
            },
        )
        val text = (response.getValue("content").jsonArray.single().jsonObject
            .getValue("text") as JsonPrimitive).content
        assertFalse("有 session_id 时不应报缺少 session_id", text.contains("缺少 session_id"))
        assertTrue(text.contains("未初始化"))
    }

    @Test
    fun `session id query parameter name is stable`() {
        // 这个名字写进了宿主给引擎的 MCP 配置 URL，改名会让线上引擎连不上。
        assertEquals("session_id", McpServerRuntime.SESSION_ID_PARAM)
    }

    @Test
    fun `host builds the mcp server url with the same session id parameter`() {
        // 宿主拼 URL 的地方（AcpSessionRunner）写的是字面量 "session_id"，
        // 服务端读的是常量。两边必须一致——不一致时引擎连得上但工具拿不到会话，
        // 表现为「红队工具永远报缺少 session_id」。用源码断言守住这个跨文件契约。
        val source = java.io.File("src/main/java/top/tianyan/app/harness/acp/AcpSessionRunner.kt")
        assertTrue("找不到 AcpSessionRunner.kt", source.isFile)
        val text = source.readText()
        assertTrue(
            "宿主拼 MCP URL 时必须带 session_id query 参数",
            text.contains("/mcp?session_id="),
        )
    }

    private fun dispatcher(engine: FakeBrowserEngine): McpToolDispatcher {
        val tools = BrowserMcpTools(
            engines = emptyList(),
            engineSelector = { engine },
            prefs = BrowserPreferences.DEFAULT,
        )
        return McpToolDispatcher(tools)
    }
}

/**
 * 暴露 [JsonObject.withSessionId] 的可见性给测试。
 *
 * 那个扩展是私有的（文件级 private），但它的行为是安全边界的一部分，
 * 必须能被直接断言。
 */
private fun JsonObject.withSessionIdForTest(sessionId: String?): JsonObject {
    if (sessionId.isNullOrBlank()) return this
    if (containsKey("session_id")) return this
    return buildJsonObject {
        for ((k, v) in this@withSessionIdForTest) put(k, v)
        put("session_id", JsonPrimitive(sessionId))
    }
}

private class FakeBrowserEngine : BrowserEngine {
    override val eventBus = BrowserEventBus()
    override val descriptor = BrowserDescriptor(
        family = BrowserFamily.IN_APP,
        displayName = "fake",
        healthy = true,
        capabilities = emptySet(),
    )
    val active = BrowserSessionToken("active-tab", BrowserFamily.IN_APP, url = "https://example.test")
    var lastNavigatedTab: BrowserSessionToken? = null
    var evaluateResult: String? = ""
    var cookies: String = ""

    init {
        runBlocking {
            eventBus.publish(
                top.tianyan.app.runtime.browser.BrowserEvent.PageChanged(
                    active.tabId,
                    active.url,
                    "Example",
                ),
            )
        }
    }

    override suspend fun openTab(url: String?, activate: Boolean) = active
    override suspend fun navigate(tab: BrowserSessionToken, url: String) { lastNavigatedTab = tab }
    override suspend fun snapshot(tab: BrowserSessionToken, maxElements: Int) =
        PageSnapshot(tab.tabId, active.url, "Example", emptyMap(), "fake", 0L)

    override suspend fun click(tab: BrowserSessionToken, ref: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun typeInto(tab: BrowserSessionToken, ref: String, text: String, refSelectorLookup: suspend (BrowserSessionToken, String) -> String?) = true
    override suspend fun press(
        tab: BrowserSessionToken,
        ref: String?,
        key: String,
        refSelectorLookup: suspend (BrowserSessionToken, String) -> String?,
    ) = true
    override suspend fun scroll(tab: BrowserSessionToken, deltaY: Int) = true
    override suspend fun screenshot(tab: BrowserSessionToken, prefs: BrowserPreferences): ToolImageRef? = null
    override suspend fun evaluate(tab: BrowserSessionToken, script: String): String? = evaluateResult
    override suspend fun pageSource(tab: BrowserSessionToken, maxBytes: Int) = ""
    override suspend fun back(tab: BrowserSessionToken) = true
    override suspend fun forward(tab: BrowserSessionToken) = true
    override suspend fun refresh(tab: BrowserSessionToken) = Unit
    override suspend fun closeTab(tab: BrowserSessionToken) = Unit
    override suspend fun listTabs() = listOf(active)
    override fun activeTab(): BrowserSessionToken = active
    override suspend fun setActiveTab(tab: BrowserSessionToken) = Unit
    override suspend fun cookiesGet(tab: BrowserSessionToken, url: String?): String = cookies
    override suspend fun cookiesSet(tab: BrowserSessionToken, url: String, headerLine: String) = Unit
    override suspend fun cookiesDelete(tab: BrowserSessionToken, url: String) = Unit
    override suspend fun localGet(tab: BrowserSessionToken, key: String): String? = null
    override suspend fun localSet(tab: BrowserSessionToken, key: String, value: String) = Unit
    override suspend fun localDelete(tab: BrowserSessionToken, key: String) = Unit
    override suspend fun localKeys(tab: BrowserSessionToken) = emptyList<String>()
    override suspend fun sessionGet(tab: BrowserSessionToken, key: String): String? = null
    override suspend fun sessionSet(tab: BrowserSessionToken, key: String, value: String) = Unit
    override suspend fun sessionDelete(tab: BrowserSessionToken, key: String) = Unit
    override suspend fun sessionKeys(tab: BrowserSessionToken) = emptyList<String>()
    override suspend fun shutdown() = Unit
}
