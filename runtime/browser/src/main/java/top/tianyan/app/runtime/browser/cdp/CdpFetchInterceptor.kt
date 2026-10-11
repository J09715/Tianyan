package top.tianyan.app.runtime.browser.cdp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.tianyan.app.runtime.browser.BrowserEvent
import top.tianyan.app.runtime.browser.BrowserEventBus
import top.tianyan.app.runtime.browser.CapturedRequest
import top.tianyan.app.runtime.browser.hook.HookHitRecord
import top.tianyan.app.runtime.browser.hook.HookRuleStore
import top.tianyan.app.runtime.browser.hook.HookType
import top.tianyan.app.runtime.browser.hook.NetworkBodyStore

/**
 * Fetch 域拦截器：CDP 引擎级网络改写（覆盖 Worker 与子资源——注入层够不到的范围）。
 *
 * - 规则复用阶段 1 [HookRuleStore]：同一规则双层生效（页内 JS 层 + CDP 引擎层）；
 * - patterns = 网络类规则 target 去重透传（CDP urlPattern 同为 `*`/`?` 语义）；
 *   规则空时 Fetch.disable 零开销；
 * - 每条 requestPaused 必须终结（continue/fail/fulfill），绝不挂起请求；
 * - Worker 子会话只挂 Fetch 不挂 Debugger（避免 worker 冻结与断点归因复杂化）。
 *
 * 动作映射：
 * - log → NetworkCaptured(source="cdp") + HookHit(phase="cdp_request") + continue 放行；
 *   captureBody=true 时 Fetch.getRequestPostData 存 bodyStore；
 * - block → Fetch.failRequest("BlockedByClient")；
 * - redirect → Fetch.continueRequest(url=…)；
 * - mock → Fetch.fulfillRequest(status, headers, body=Base64)；
 * - modify_headers → 请求头合并（`"!"`=删除）后 continue（**响应头改写 CDP 层不支持**，仍由注入层承担）。
 */
class CdpFetchInterceptor(
    private val tabId: String,
    private val session: CdpCommandApi,
    private val store: HookRuleStore,
    private val bodyStore: NetworkBodyStore,
    private val eventBus: BrowserEventBus,
) {

    /** 请求阶段 captureBody=true 的 requestId，响应阶段据此决定是否 Fetch.getResponseBody。 */
    private val pendingResponseBody = ConcurrentHashMap.newKeySet<String>()

    /**
     * 启用（或按当前规则重载）拦截；sessionId 非空时作用于 worker 子会话。
     *
     * 每个网络 pattern 同时注册 Request 与 Response 两个 stage：
     * - Request 阶段承担改写动作（block/mock/redirect/modify_headers/log）；
     * - Response 阶段只读回填（statusCode/responseHeaders/body），立即放行、绝不改写。
     * 两者靠 CDP 的 requestId 关联（同一请求跨阶段 requestId 不变）。
     */
    suspend fun enable(sessionId: String? = null) {
        val patterns = CdpFetchDecision.fetchPatterns(store.rulesFor(tabId))
        if (patterns.isEmpty()) {
            // 无网络类规则：disable，不再产生 requestPaused（零开销）
            session.send("Fetch.disable", sessionId = sessionId)
            return
        }
        session.send(
            "Fetch.enable",
            buildJsonObject {
                put(
                    "patterns",
                    buildJsonArray {
                        patterns.forEach { p ->
                            add(
                                buildJsonObject {
                                    put("urlPattern", p)
                                    put("requestStage", "Request")
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("urlPattern", p)
                                    put("requestStage", "Response")
                                }
                            )
                        }
                    },
                )
            },
            sessionId = sessionId,
        )
    }

    suspend fun disable(sessionId: String? = null) {
        runCatching { session.send("Fetch.disable", sessionId = sessionId) }
    }

    /** Fetch.requestPaused 事件入口（CdpTabConnection 转发；sessionId 非空 = worker 会话）。 */
    suspend fun onRequestPaused(params: JsonObject, sessionId: String?) {
        val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull ?: return
        // 响应阶段：params 带 responseStatusCode（请求阶段无此字段）→ 只读回填后立即放行
        if (params.containsKey("responseStatusCode")) {
            onResponsePaused(requestId, params, sessionId)
            return
        }
        val request = params["request"] as? JsonObject ?: JsonObject(emptyMap())
        val url = request["url"]?.jsonPrimitive?.contentOrNull ?: ""
        val method = request["method"]?.jsonPrimitive?.contentOrNull ?: "GET"
        val headers = (request["headers"] as? JsonObject)
            ?.mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }
            ?.toMap()
            ?: emptyMap()
        val startedAt = System.currentTimeMillis()

        val decision = CdpFetchDecision.decide(store.rulesFor(tabId), url, method)
        when (decision) {
            is FetchDecision.Pass -> continueRequest(requestId, sessionId)

            is FetchDecision.Log -> {
                val body = if (decision.captureBody) fetchPostData(requestId, sessionId) else null
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "log $method $url")
                publishCapture(requestId, url, method, headers, body, startedAt, decision.ruleId, "log", decision.captureBody)
                continueRequest(requestId, sessionId)
            }

            is FetchDecision.Block -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "block $method $url")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "block", false)
                session.send(
                    "Fetch.failRequest",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("errorReason", "BlockedByClient")
                    },
                    sessionId = sessionId,
                )
            }

            is FetchDecision.Redirect -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "redirect $method $url -> ${decision.url}")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "redirect", false)
                continueRequest(requestId, sessionId, url = decision.url)
            }

            is FetchDecision.Mock -> {
                store.recordHit(decision.ruleId)
                publishHit(decision.ruleId, url, "cdp_request", "mock $method $url -> ${decision.status}")
                publishCapture(requestId, url, method, headers, null, startedAt, decision.ruleId, "mock", false)
                val responseHeaders = decision.headers.toMutableMap()
                responseHeaders.putIfAbsent("Content-Type", "text/plain; charset=utf-8")
                session.send(
                    "Fetch.fulfillRequest",
                    buildJsonObject {
                        put("requestId", requestId)
                        put("responseCode", decision.status)
                        put(
                            "responseHeaders",
                            headersToJsonArray(responseHeaders),
                        )
                        put("body", encodeBase64(decision.body))
                    },
                    sessionId = sessionId,
                )
            }

            is FetchDecision.ModifyRequestHeaders -> {
                store.recordHit(decision.ruleId)
                val merged = mergeHeaders(headers, decision.headers)
                publishHit(decision.ruleId, url, "cdp_request", "modify_headers $method $url")
                publishCapture(
                    requestId, url, method, merged,
                    if (decision.captureBody) fetchPostData(requestId, sessionId) else null,
                    startedAt, decision.ruleId, "modify_headers", decision.captureBody,
                )
                continueRequest(requestId, sessionId, headers = merged)
            }
        }
    }

    // ===== CDP 命令封装 =====

    private suspend fun continueRequest(
        requestId: String,
        sessionId: String?,
        url: String? = null,
        headers: Map<String, String>? = null,
    ) {
        val params = buildJsonObject {
            put("requestId", requestId)
            if (url != null) put("url", url)
            if (headers != null) put("headers", headersToJsonArray(headers))
        }
        session.send("Fetch.continueRequest", params, sessionId = sessionId)
    }

    /** 读请求体（存在时）；无 body 或读取失败返回 null。 */
    private suspend fun fetchPostData(requestId: String, sessionId: String?): String? = runCatching {
        val resp = session.send(
            "Fetch.getRequestPostData",
            buildJsonObject { put("requestId", requestId) },
            sessionId = sessionId,
        )
        (resp["result"] as? JsonObject)?.get("postData")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    // ===== 事件发布 =====

    private suspend fun publishCapture(
        id: String,
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        startedAt: Long,
        ruleId: String,
        actionTaken: String,
        captureBody: Boolean,
    ) {
        val at = System.currentTimeMillis()
        eventBus.publish(
            BrowserEvent.NetworkCaptured(
                tabId,
                CapturedRequest(
                    id = id,
                    tabId = tabId,
                    url = url,
                    method = method,
                    requestHeaders = headers,
                    startedAt = startedAt,
                    finishedAt = at,
                    source = "cdp",
                    ruleId = ruleId,
                    actionTaken = actionTaken,
                    requestSize = body?.length?.toLong() ?: 0L,
                    hasRequestBody = body != null,
                ),
            )
        )
        if (captureBody) {
            // 请求阶段先建 entry（请求体可能为空，如 GET），响应阶段回填 responseBody
            pendingResponseBody.add(id)
            bodyStore.put(
                NetworkBodyStore.NetworkBody(
                    id = id,
                    tabId = tabId,
                    requestBody = body ?: "",
                    responseBody = "",
                    at = at,
                )
            )
        }
    }

    /**
     * 响应阶段回填：只读 statusCode / responseHeaders / body，更新同 id 的请求记录后立即放行。
     * - 无请求阶段记录（Pass 且未发布）时仅放行，不产生空记录；
     * - 响应体捕获只在请求阶段 captureBody=true 时进行（[pendingResponseBody]），避免全量抓 body 的开销。
     */
    private suspend fun onResponsePaused(requestId: String, params: JsonObject, sessionId: String?) {
        val statusCode = params["responseStatusCode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val responseHeaders = parseResponseHeaders(params["responseHeaders"])
        val shouldCapture = pendingResponseBody.remove(requestId)
        val responseBody = if (shouldCapture) fetchResponseBody(requestId, sessionId) else null

        if (shouldCapture) {
            bodyStore.get(requestId)?.let { existing ->
                bodyStore.put(existing.copy(responseBody = responseBody ?: ""))
            }
        }

        val prev = eventBus.network.value.lastOrNull { it.id == requestId && it.tabId == tabId }
        if (prev != null) {
            val now = System.currentTimeMillis()
            eventBus.publish(
                BrowserEvent.NetworkCaptured(
                    tabId,
                    prev.copy(
                        statusCode = statusCode,
                        responseHeaders = responseHeaders,
                        finishedAt = now,
                        durationMs = if (prev.startedAt > 0) now - prev.startedAt else 0L,
                        responseSize = responseBody?.length?.toLong() ?: 0L,
                        hasResponseBody = responseBody != null,
                    ),
                )
            )
        }
        // 响应阶段只读放行：不改请求头/URL，仅 requestId（continueRequest 在 Response stage 同样合法）
        continueRequest(requestId, sessionId)
    }

    /** 读响应体（存在时）；无 body / base64 解码失败 / 读取失败均返回 null。 */
    private suspend fun fetchResponseBody(requestId: String, sessionId: String?): String? = runCatching {
        val resp = session.send(
            "Fetch.getResponseBody",
            buildJsonObject { put("requestId", requestId) },
            sessionId = sessionId,
        )
        val result = resp["result"] as? JsonObject ?: return@runCatching null
        val body = result["body"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
        val base64Encoded = result["base64Encoded"]?.jsonPrimitive?.contentOrNull
            ?.toBooleanStrictOrNull() ?: false
        if (base64Encoded) {
            runCatching {
                java.util.Base64.getDecoder().decode(body).toString(Charsets.UTF_8)
            }.getOrNull()
        } else {
            body
        }
    }.getOrNull()

    /** 解析 CDP 响应头的 `HeaderEntry[]`（name/value）为有序 Map。 */
    private fun parseResponseHeaders(node: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        (node as? JsonArray)?.mapNotNull { entry ->
            val obj = entry as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val value = obj["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            name to value
        }?.toMap() ?: emptyMap()

    private suspend fun publishHit(ruleId: String, url: String, phase: String, summary: String) {
        val rule = store.list().firstOrNull { it.id == ruleId }
        eventBus.publish(
            BrowserEvent.HookHit(
                tabId,
                HookHitRecord(
                    tabId = tabId,
                    hookId = ruleId,
                    type = rule?.type ?: HookType.FETCH,
                    target = rule?.target ?: url,
                    phase = phase,
                    summary = summary,
                ),
            )
        )
    }

    // ===== 纯函数 =====

    private fun headersToJsonArray(headers: Map<String, String>): JsonArray = buildJsonArray {
        headers.forEach { (k, v) ->
            add(
                buildJsonObject {
                    put("name", k)
                    put("value", v)
                }
            )
        }
    }

    /** 合并请求头：改写值覆盖；值 `"!"` 表示删除。 */
    private fun mergeHeaders(
        original: Map<String, String>,
        modifications: Map<String, String>,
    ): Map<String, String> {
        // header 名大小写不敏感：改写/删除需与原始键对齐
        val result = original.toMutableMap()
        for ((name, value) in modifications) {
            val existingKey = result.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            when {
                value == "!" -> existingKey?.let { result.remove(it) }
                existingKey != null -> result[existingKey] = value
                else -> result[name] = value
            }
        }
        return result
    }

    private fun encodeBase64(s: String): String =
        java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
}
