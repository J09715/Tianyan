package top.tianyan.app.feature.a2uipoc

import androidx.a2ui.compose.runtime.A2uiMessageParser
import androidx.a2ui.compose.ui.A2uiCatalog
import androidx.a2ui.compose.ui.A2uiMessageProcessor
import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider
import androidx.a2ui.model.protocol.A2uiClientErrorMessage
import androidx.a2ui.model.protocol.A2uiClientEventMessage
import androidx.a2ui.model.protocol.A2uiDeleteSurfaceMessage
import androidx.compose.material3.a2ui.A2uiSurface
import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.harness.A2uiSurfaceBus

/**
 * 天衍 A2UI 渲染器包装：把 androidx.a2ui 官方渲染器收敛为单例入口。
 *
 * - Catalog 在官方 Material3 Basic Catalog 上加天衍 Table，catalogId 与
 *   harness 契约（A2uiSurfaceContract.CATALOG_ID）保持一致：智能体只能使用目录内
 *   声明的组件，与工具白名单同一套安全哲学；
 * - processor 单例持有全部活动 surface：聊天流滚动导致组合销毁重建时，
 *   界面状态不丢失（滚动回来即恢复），因此同一载荷只投喂一次（见 [processMessages] 去重）；
 * - 用户交互事件（Button 点击等）经 [processor].outboundEvents 收集后转发到
 *   A2uiSurfaceBus，由装配层路由回原会话的 agent 循环。
 *
 * 注意：A2UI 库当前为 1.0.0-alpha01，API 可能随版本变动；本文件是唯一的对接点，
 * 升级库版本时只需调整这里（关键符号：materialA2uiBasicCatalogV1 /
 * A2uiMessageProcessor / A2uiMessageParser / A2uiSurface / outboundEvents）。
 */
object TianyanA2uiRenderer {

    /**
     * Basic Catalog 加 Table。image/video/audioPlayer 与 urlOpener/messageFormatter
     * 由天衍实现（Coil、Media3、http/https 白名单、ICU plural）；其余走官方默认。
     */
    private val catalog: A2uiCatalog = TianyanA2uiCatalog.extend(
        materialA2uiBasicCatalogV1(
            image = TianyanImageComponent(),
            video = TianyanVideoComponent(),
            audioPlayer = TianyanAudioPlayerComponent(),
            urlOpener = TianyanUrlOpener,
            // 覆写 List：官方纵向 LazyColumn 在聊天流里拿到无限高会直接崩溃。
            // 短列表展平，超长列表限高后再懒加载，见 TianyanNonLazyList.kt。
            list = TianyanNonLazyList,
            messageFormatter = TianyanMessageFormatter,
            localeProvider = A2uiLocaleProvider.Default,
        ),
    )

    private val processor = A2uiMessageProcessor(catalogs = listOf(catalog))

    private val parser = A2uiMessageParser()

    private val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var eventForwardingStarted = false

    /** 每个 surface 最近一次错误回传时间（冷却窗口内不重复回传，防刷出一堆排队任务）。 */
    private val errorReportedAt = mutableMapOf<String, Long>()
    private val errorReportLock = Any()

    /**
     * 已成功投喂的载荷指纹 → surfaceId（LRU 上限 [MAX_PROCESSED_PAYLOADS]）。
     *
     * 组合销毁重建（滚动出屏再回看）会再次触发投喂：processor 仍持有 surface 状态，重复投喂不但
     * 白做一遍主线程解析，还会撞出 `[RUNTIME_ERROR] Surface already exists`。指纹命中即直接放行。
     * 容量必须覆盖「一次会话里出现过的 A2UI 卡片数」——实测单会话可产生 80+ 张卡片，旧上限 32
     * 会在滚动回看时被淘汰，导致重复投喂与假报错。投喂失败不记指纹，模型修正后可重试。
     */
    private val processedPayloads = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > MAX_PROCESSED_PAYLOADS
    }
    private val replay = TianyanA2uiReplay()

    /**
     * 逐条投喂 A2UI 协议消息（JSON Lines 数组字符串）：
     * 每条先经 parser.parse 反序列化为协议对象，再交给 processor.processMessage。
     * 返回 null 表示全部消息受理成功，否则返回错误文案（可直接展示给用户）。
     * 同一张卡片（[replayKey]）只投喂一次，避免滚回时 createSurface 冲突。
     * 没有 replayKey 的预览页在该界面已被 deleteSurface 拆掉后不再建回来。
     * 新的工具调用使用另一把 replayKey，可以再次创建。
     */
    fun processMessages(messagesJson: String, replayKey: String? = null): String? {
        val contentKey = sha256(messagesJson)
        val replayFingerprint = replayKey?.takeIf { it.isNotBlank() }?.let { sha256(it) }
        val createdId = extractSurfaceId(messagesJson)
        val deletedIds = extractSurfaceIds(messagesJson, "deleteSurface")
        if (replay.shouldSkip(replayFingerprint, contentKey, createdId)) return null
        // 归一化放在 runCatching 内：畸形载荷（非 JSON / 非数组 / 元素非对象 / surfaceId 非原始值）
        // 必须仍返回错误文案。归一化器自身也会吞掉异常并原样退回，这里再兜一层。
        val error = runCatching {
            // 值型组件（TextField/CheckBox/ChoicePicker/Slider/DateTimeInput）写常量 value 时，
            // 官方 bindUpdater 判定非 {"path"} 即返回 null → isEnabled=false → 组件被静默禁用
            // （能渲染、不能输入、不回传、无提示）。这里统一归一化为数据绑定并种值，
            // 使这五类组件真正可用；已是数据绑定的载荷幂等不变。见 TianyanA2uiInputNormalizer。
            val normalized = TianyanA2uiInputNormalizer.normalize(
                TianyanA2uiCatalog.alignCatalogId(messagesJson),
            )
            Json.parseToJsonElement(normalized.messagesJson).jsonArray.forEach { element ->
                processor.processMessage(parser.parse(element.toString()))
            }
        }.fold(
            onSuccess = { null },
            onFailure = { failure ->
                val msg = failure.message?.takeIf { it.isNotBlank() } ?: "A2UI 消息处理失败"
                if (msg.contains("already exists", ignoreCase = true)) null else "A2UI 消息处理失败：$msg"
            },
        )
        if (error == null) {
            synchronized(processedPayloads) { processedPayloads[contentKey] = createdId.orEmpty() }
            replay.record(replayFingerprint, contentKey, createdId, deletedIds)
            deletedIds.forEach { A2uiSurfaceBus.forgetEngineSurface(it) }
        }
        return error
    }

    /**
     * 用官方 parser 对每条协议消息做真解析（版本、结构校验），并校验组件类型
     * 必须命中 Catalog（parser 不查组件名，杜撰的组件只会在渲染期静默失败）。
     * 供工具执行期的深度校验使用（A2uiPocInstaller 注册进 A2uiSurfaceBus）：
     * 失败原因在工具结果里回传给模型自我纠正，而不是等 UI 渲染时才默默回退。
     */
    fun validateMessages(messagesJson: String): String? = runCatching {
        val array = Json.parseToJsonElement(TianyanA2uiCatalog.alignCatalogId(messagesJson)).jsonArray
        var rootCount = 0
        array.forEach { element ->
            parser.parse(element.toString())
            val components = element.jsonObject["updateComponents"]
                ?.jsonObject?.get("components") as? JsonArray ?: return@forEach
            components.forEach { component ->
                val obj = component.jsonObject
                val type = obj["component"]?.jsonPrimitive?.contentOrNull
                if (type != null && catalog.components[type] == null) {
                    error("组件类型 \"$type\" 不在客户端 Catalog 中。可用组件：${availableComponentNames()}")
                }
                if (obj["id"]?.jsonPrimitive?.contentOrNull == "root") rootCount++
            }
        }
        if (array.any { it.jsonObject.containsKey("updateComponents") } && rootCount != 1) {
            error("components 中必须恰好有一个 id=\"root\" 的顶部组件（当前 $rootCount 个）")
        }
    }.fold(
        onSuccess = { null },
        onFailure = { it.message?.let { msg -> "A2UI 协议消息解析失败：$msg" } ?: "A2UI 协议消息解析失败" },
    )

    /** 会话删除时拆掉这些 surface，指纹也放行，同一份载荷以后还能再渲染。 */
    fun releaseSurfaces(surfaceIds: Collection<String>) {
        if (surfaceIds.isEmpty()) return
        val dropping = surfaceIds.toSet()
        dropping.forEach { id ->
            runCatching { processor.processMessage(A2uiDeleteSurfaceMessage(id)) }
        }
        synchronized(processedPayloads) {
            val iterator = processedPayloads.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value in dropping) iterator.remove()
            }
        }
        replay.release(dropping)
        synchronized(errorReportLock) { dropping.forEach { errorReportedAt.remove(it) } }
    }

    /**
     * 启动引擎消息循环与用户交互事件转发（幂等）。
     * processMessage 只是入队，真正处理靠 [A2uiMessageProcessor.collectMessages]。
     */
    fun startEventForwarding() {
        if (eventForwardingStarted) return
        synchronized(this) {
            if (eventForwardingStarted) return
            eventForwardingStarted = true
        }
        eventScope.launch { processor.collectMessages() }
        eventScope.launch {
            processor.outboundEvents.collect { message ->
                when (message) {
                    is A2uiClientEventMessage -> {
                        val route = A2uiSurfaceBus.routeFor(message.surfaceId)
                        A2uiSurfaceBus.publishUserEvent(
                            A2uiSurfaceBus.A2uiUserEvent(
                                surfaceId = message.surfaceId,
                                surfaceTitle = route?.title ?: message.surfaceId,
                                sessionId = route?.sessionId.orEmpty(),
                                componentId = message.componentId,
                                eventName = message.type,
                                context = message.context,
                                timestamp = message.timestamp,
                                // 官方把表单值放在 clientDataModel.surfaces，不在 context。只取本事件的 surface。
                                dataModel = surfaceDataModel(
                                    message.clientDataModel?.surfaces?.get(message.surfaceId),
                                ),
                            ),
                        )
                    }
                    is A2uiClientErrorMessage -> {
                        // 重复创建（already exists）是卡片重复投喂时的预期噪声，processMessages 内部
                        // 已把它吞掉（去重/流式重渲染路径），这里同样抑制，避免同一错误"一处吞、一处漏"
                        // 地回传给智能体，把模型引向"修正组件定义"的错误方向。
                        if (message.message.contains("already exists", ignoreCase = true)) {
                            return@collect
                        }
                        // 组件被 Catalog 校验拒绝等运行时错误此前被静默吞掉，界面只会一直转圈；
                        // 回传给智能体让模型自纠。同一 surface 的错误按冷却窗口限流：
                        // 引擎对每个非法组件各发一条错误，不节流会刷出一堆排队任务
                        val now = System.currentTimeMillis()
                        val shouldReport = synchronized(errorReportLock) {
                            val last = errorReportedAt[message.surfaceId]
                            if (last != null && now - last < ERROR_REPORT_COOLDOWN_MS) {
                                false
                            } else {
                                errorReportedAt[message.surfaceId] = now
                                true
                            }
                        }
                        if (shouldReport) {
                            val route = A2uiSurfaceBus.routeFor(message.surfaceId)
                            A2uiSurfaceBus.publishErrorEvent(
                                A2uiSurfaceBus.A2uiErrorEvent(
                                    surfaceId = message.surfaceId,
                                    surfaceTitle = route?.title ?: message.surfaceId,
                                    sessionId = route?.sessionId.orEmpty(),
                                    code = message.code,
                                    message = message.message,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 渲染指定 surface；尚未收到该 surface 的组件数据时返回 false（调用方可回退展示原文）。
     *
     * 只订阅「本卡片对应的那一个 surface」：`activeSurfaces` 每次发射都比对本项，命中同一实例时
     * 直接跳过重组。若订阅整张表（旧写法），任一表面的更新都会让聊天流里**所有** A2UI 卡片一起
     * 重组；而 [A2uiSurface] 的入参 `A2uiSurfaceModel` 是接口类型，Compose 视为不稳定参数无法
     * 跳过，于是大列表表面会被反复重新组合与重新测量——这是长会话多卡片场景最明显的卡顿来源。
     */
    @Composable
    fun SurfaceView(surfaceId: String, modifier: Modifier = Modifier): Boolean {
        val surface by remember(surfaceId) {
            processor.activeSurfaces
                .map { surfaces -> surfaces.firstOrNull { it.id == surfaceId } }
                .distinctUntilChanged()
        }.collectAsState(initial = null)
        val current = surface ?: return false
        A2uiSurface(surfaceModel = current, modifier = modifier)
        return true
    }

    /**
     * 出站事件里该 surface 的数据模型根。官方类型是 `Any?`（对象 / 数组 / 标量）；
     * harness 只收 [Map]，避免依赖 a2ui 类型。非对象根包进 `value`，避免数组或标量被丢掉。
     */
    private fun surfaceDataModel(raw: Any?): Map<String, Any?>? = when (raw) {
        null -> null
        is Map<*, *> -> raw.entries.associate { (key, value) -> key.toString() to value }
        else -> mapOf("value" to raw)
    }

    private fun availableComponentNames(): String =
        catalog.components.joinToString("/") { it.name }

    private fun extractSurfaceId(messagesJson: String): String? =
        extractSurfaceIds(messagesJson, "createSurface").firstOrNull()

    private fun extractSurfaceIds(messagesJson: String, field: String): Set<String> = runCatching {
        Json.parseToJsonElement(messagesJson).jsonArray.mapNotNull { element ->
            element.jsonObject[field]?.jsonObject?.get("surfaceId")?.jsonPrimitive?.contentOrNull
        }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 去重表容量：须覆盖单次会话出现过的 A2UI 卡片数（实测 80+，留足余量）。每条约 150 B。 */
    private const val MAX_PROCESSED_PAYLOADS = 1024
    private const val ERROR_REPORT_COOLDOWN_MS = 15_000L
}
