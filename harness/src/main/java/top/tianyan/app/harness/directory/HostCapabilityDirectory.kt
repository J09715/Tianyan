package top.tianyan.app.harness.directory

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import top.tianyan.app.harness.ApiFunctionDefinition
import top.tianyan.app.harness.ApiToolDefinition
import kotlinx.serialization.json.Json as SerializationJson
import kotlinx.serialization.json.JsonArray

/**
 * 宿主能力目录 —— host 工具 direct / deferred 拆分与动作分类的唯一事实源。
 *
 * 借鉴 pi 的工具暴露分级（exposure）：
 * - DIRECT：高频动作保留在 provider 工具面的 host 声明里，schema 与审批矩阵不变；
 * - DEFERRED：低频管理动作与虚拟屏原语不进每轮工具声明（消除巨型 action 枚举与
 *   上千字说明的 prompt 负担），由模型经 use_capability(list/inspect/call, server="host")
 *   按需发现与调用；inspect 结果是普通工具结果，天然随分支回放，无需额外激活状态；
 * - HIDDEN：从 inspect 清单移除，且在调用入口真正拒绝执行——不只是提示词摘除。
 *
 * 审批语义保证：deferred 调用经 [flattenToHostArgs] 展平后套用与直接 host 调用
 * 完全一致的风险矩阵（ApprovalPolicyEngine 委派），ASSISTED 的 GUI 放行、
 * PLAN 的只读约束、critical 判定均不因路由改变。
 *
 * 不变量（由 HostCapabilityDirectoryTest 锁定）：DIRECT ∪ DEFERRED 覆盖宿主执行器
 * 支持的全部动作，且两个集合不相交。
 */
object HostCapabilityDirectory {

    /** use_capability 中指向本域的 server id（内置能力域，与 MCP 服务器 id 共用同一代理入口）。 */
    const val SERVER_ID = "host"

    /** 必须是本 object 的首个属性：PARAM_POOL 等后续初始化器会经由 jsonObj() 读到它。 */
    private val Json = SerializationJson

    /** 保留在 provider 工具面的高频动作。 */
    val DIRECT_ACTIONS: List<String> = listOf(
        "status", "exec", "settings_get", "package_list", "app_list", "logcat", "device_status",
        "screen_observe", "screen_click", "screen_double_click", "screen_long_press",
        "screen_swipe", "screen_scroll", "screen_input_text", "paste_text", "screen_key", "app_launch",
    )

    /** 迁入按需发现域的低频管理动作与虚拟屏原语。 */
    data class DeferredAction(
        val name: String,
        val description: String,
        val params: List<String>,
        val required: List<String> = emptyList(),
    )

    val DEFERRED_ACTIONS: List<DeferredAction> = listOf(
        DeferredAction(
            "settings_put", "修改真实 Android 系统设置（需 Shizuku 或 Root，需审批）。", listOf("namespace", "key", "value"), listOf("key", "value"),
        ),
        DeferredAction("package_disable", "停用真实 Android 应用（需 Shizuku 或 Root）。", listOf("package"), listOf("package")),
        DeferredAction("package_enable", "重新启用已停用的真实 Android 应用（需 Shizuku 或 Root）。", listOf("package"), listOf("package")),
        DeferredAction(
            "package_uninstall_user", "为指定 Android 用户卸载应用；系统应用通常可用 install-existing 恢复，但其数据可能丢失（critical）。", listOf("package", "user"), listOf("package"),
        ),
        DeferredAction("app_freeze", "冻结真实 Android 应用（需 Shizuku 或 Root）。", listOf("package"), listOf("package")),
        DeferredAction("app_unfreeze", "解冻真实 Android 应用（需 Shizuku 或 Root）。", listOf("package"), listOf("package")),
        DeferredAction("app_grant_permission", "为真实 Android 应用授予运行时权限（需 Shizuku 或 Root）。", listOf("package", "permission"), listOf("package", "permission")),
        DeferredAction(
            "screen_capture", "把真实主屏截图写入指定 path（需 Shizuku 或 Root）。", listOf("path"), listOf("path"),
        ),
        DeferredAction(
            "virtual_screen_ensure",
            "在一块独立的 Shower 虚拟屏上建立会话（需 Shizuku 或 Root，完全不影响主屏）。悬浮窗会自动弹出，标题栏实时显示当前步骤；" +
                "没有「显示在其他应用上层」权限时用户看不到画面，必须告知用户去系统设置打开。",
            listOf("session"),
        ),
        DeferredAction("virtual_screen_launch", "在虚拟屏启动目标应用（先 virtual_screen_ensure 建屏）。", listOf("package", "session"), listOf("package")),
        DeferredAction(
            "virtual_screen_screenshot",
            "抓取虚拟屏画面。不填 path 时图片直接附在工具结果上，不必再 read；只在主模型需要亲自看画面时使用。",
            listOf("path", "session"),
        ),
        DeferredAction(
            "virtual_screen_click", "点击虚拟屏。坐标是 0–1000 相对位置（左上角 0,0），不是像素，不要按截图缩放换算。", listOf("x", "y", "session"), listOf("x", "y"),
        ),
        DeferredAction(
            "virtual_screen_double_click", "双击虚拟屏。坐标是 0–1000 相对位置，不是像素。", listOf("x", "y", "session"), listOf("x", "y"),
        ),
        DeferredAction(
            "virtual_screen_long_press", "长按虚拟屏。坐标是 0–1000 相对位置，不是像素。", listOf("x", "y", "duration_ms", "session"), listOf("x", "y"),
        ),
        DeferredAction(
            "virtual_screen_swipe", "在虚拟屏滑动。起终点是 0–1000 相对坐标，不是像素。", listOf("x1", "y1", "x2", "y2", "duration_ms", "session"), listOf("x1", "y1", "x2", "y2"),
        ),
        DeferredAction("virtual_screen_scroll", "在虚拟屏滚动。", listOf("direction", "distance_ratio", "duration_ms", "session"), listOf("direction")),
        DeferredAction("virtual_screen_key", "向虚拟屏发送按键（back/home/recents/enter/delete/paste/power）。", listOf("key", "session"), listOf("key")),
        DeferredAction(
            "virtual_screen_input_text",
            "向虚拟屏输入文本（中文走剪贴板粘贴）。虚拟屏打字必须用本动作；paste_text 和 screen_input_text 打到主屏焦点，会把虚拟屏里的应用切走。",
            listOf("text", "session"), listOf("text"),
        ),
        DeferredAction("virtual_screen_set_text", "替换虚拟屏输入框文本（空文本清空）。", listOf("text", "session"), listOf("text")),
        DeferredAction("virtual_screen_wait", "等待虚拟屏界面稳定；duration_ms 可为 0。", listOf("duration_ms", "session"), listOf("duration_ms")),
        DeferredAction("virtual_screen_close", "释放虚拟屏；一轮虚拟屏操作完成后调用。", listOf("session")),
        DeferredAction("virtual_screen_show", "恢复显示已隐藏的虚拟屏。", listOf("session")),
        DeferredAction("virtual_screen_hide", "隐藏虚拟屏悬浮窗。不要调用本动作，除非用户明确要求隐藏。", listOf("session")),
        DeferredAction(
            "virtual_screen_task",
            "多步虚拟屏界面操作的首选入口：goal 写要完成的事（可选 package 先打开应用），由设置里的手机操作模型执行，" +
                "不要自己一轮轮截图点按。成功且无人工介入时可传 workflow_name 保存为可复用工作流。" +
                "必须如实报告手机模型失败；主模型接管需明确说明。",
            listOf("goal", "package", "workflow_name", "max_steps", "session"), listOf("goal"),
        ),
    )

    /** 目录级隐藏：inspect 不列出，调用入口拒绝。当前为空，作为 hidden 暴露级别的执行位。 */
    private val HIDDEN_ACTIONS: Set<String> = emptySet()

    /** PLAN 只读门禁引用的宿主只读动作（自 ApprovalPolicyEngine 迁入，动作分类统一归目录）。 */
    val READ_ONLY_ACTIONS: Set<String> = top.tianyan.app.harness.approval.TrustedToolRiskPolicy.readOnlyHostActions

    /** ASSISTED 自动放行动作与审批引擎使用同一可信策略目录。 */
    val GUI_ASSISTED_ACTIONS: Set<String> = top.tianyan.app.harness.approval.TrustedToolRiskPolicy.assistedHostActions

    fun isDeferred(action: String): Boolean = deferredNames.contains(action)
    fun isHidden(action: String): Boolean = action in HIDDEN_ACTIONS
    fun deferredAction(action: String): DeferredAction? = DEFERRED_ACTIONS.firstOrNull { it.name == action }

    /** use_capability(args) 是否为指向本域的 call（rawToolName 必须是 use_capability 代理）。 */
    fun isCapabilityCall(args: JsonObject, rawToolName: String?): Boolean {
        if (rawToolName != "use_capability") return false
        if (args.stringOf("action")?.lowercase() != "call") return false
        return args.stringOf("server")?.trim()?.lowercase() == SERVER_ID
    }

    /** 把 use_capability(call, server="host") 参数展平为等价的 host 工具参数：arguments + action=tool。 */
    fun flattenToHostArgs(args: JsonObject): JsonObject =
        flattenHostArgs(
            tool = args.stringOf("tool")?.trim().orEmpty(),
            callArgs = args["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
        )

    /** 把直接给出的调用参数展平为 host 参数（codemode 脚本与 use_capability call 共用）。 */
    fun flattenHostArgs(tool: String, callArgs: JsonObject): JsonObject {
        val flattened = LinkedHashMap(callArgs)
        flattened["action"] = JsonPrimitive(tool)
        return JsonObject(flattened)
    }

    /** provider 工具面里 host 声明的 action 枚举 JSON 片段（逐字节稳定）。 */
    fun directActionsJson(): String = DIRECT_ACTIONS.joinToString(",") { "\"$it\"" }

    /**
     * 执行器实际接受的参数校验 schema（direct ∪ deferred 并集）。
     * 参数校验必须反映**执行器接受面**而非 provider 声明面：旧会话的直接
     * `host + virtual_screen_*` 调用（重放、沙箱直调、模型历史模仿）在校验层
     * 仍然合法，由审批矩阵按原语义把关；provider 声明面只宣告 direct 用于
     * prompt 减负，不能作为校验依据。
     */
    fun validationSchema(): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put(
                "action",
                buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        JsonArray(
                            (DIRECT_ACTIONS + DEFERRED_ACTIONS.map { it.name }).map { JsonPrimitive(it) },
                        ),
                    )
                },
            )
            PARAM_POOL.forEach { (key, schema) -> put(key, schema) }
        }
        put("required", JsonArray(listOf(JsonPrimitive("action"))))
    }

    /** use_capability list 中本域的摘要行。 */
    fun listSummaryLine(): String =
        "- $SERVER_ID · 宿主低频能力域（内置） · ${DEFERRED_ACTIONS.size} 个工具 · 常驻可用"

    /** use_capability inspect 清单（HIDDEN 动作不出现在清单中）。 */
    fun inspectListing(): String {
        val visible = DEFERRED_ACTIONS.filter { it.name !in HIDDEN_ACTIONS }
        val rendered = visible.joinToString("\n\n") { action ->
            buildString {
                appendLine("### ${action.name}")
                appendLine(action.description)
                appendLine("参数：${schemaFor(action.name).toString().take(1200)}")
            }
        }
        return "宿主低频能力域（server=\"$SERVER_ID\"）工具清单（${visible.size} 个）：\n" +
            "调用方式：use_capability(action=\"call\", server=\"$SERVER_ID\", tool=\"<工具名>\", arguments={...})。" +
            "审批与特权要求与直接 host 调用一致；只读查询类动作在 host 工具上直接可用，无需经本域。\n$rendered"
    }

    /** 单个 deferred 动作的参数 schema（从共享参数池按动作选择）。 */
    fun schemaFor(action: String): JsonObject {
        val deferred = deferredAction(action) ?: return JsonObject(emptyMap())
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                deferred.params.forEach { key -> PARAM_POOL[key]?.let { put(key, it) } }
            }
            if (deferred.required.isNotEmpty()) {
                put("required", JsonArray(deferred.required.map { JsonPrimitive(it) }))
            }
        }
    }

    /**
     * 保留在 provider 工具面的 host 工具声明（自 ProviderClient 迁入）：
     * 高频查询/特权命令/主屏 GUI 直接可用，低频管理动作与虚拟屏指引改为按需发现。
     */
    fun directHostTool(): ApiToolDefinition = ApiToolDefinition(
        function = ApiFunctionDefinition(
            name = "host",
            description =
                "在 Android 宿主侧执行状态查询、特权命令与主屏 GUI 自动化。只读查询（status/settings_get/package_list/" +
                    "app_list/logcat/device_status/screen_observe）免审批；logcat 首选内置无线 ADB（无需 Shizuku/Root，可选 port），" +
                    "其余特权操作需 Shizuku 或 Root。GUI 原语（screen_click/double_click/long_press/swipe/scroll/input_text/key）" +
                    "坐标是物理像素，走 HostGuiToolkit：无障碍全局手势 → cmd input → bin input 自动降级；中文输入走剪贴板粘贴。" +
                    "paste_text 和 screen_input_text 打到主屏焦点。设置修改、应用管理变更、屏幕截图与虚拟屏（virtual_screen_*，" +
                    "在独立 Shower 虚拟屏上隔离启动并操控第三方应用）不在本工具内：用 " +
                    "use_capability(action=\"inspect\", server=\"$SERVER_ID\") 查看清单后经 " +
                    "use_capability(action=\"call\", server=\"$SERVER_ID\", tool=…) 调用，审批与特权要求与本工具一致。",
            parameters = Json.parseToJsonElement(
                """{"type":"object","properties":{"action":{"type":"string","enum":[${directActionsJson()}]},""" +
                    """"command":{"type":"string","description":"仅 exec 使用的原始宿主命令"},""" +
                    """"namespace":{"type":"string","enum":["system","secure","global"],"description":"settings_get/settings_put 的设置命名空间"},""" +
                    """"key":{"type":"string","description":"系统设置键名，或 screen_key 的按键名(back/home/recents/enter/delete/paste/power)"},""" +
                    """"value":{"type":"string","description":"settings_put 的值"},""" +
                    """"text":{"type":"string","description":"screen_input_text/paste_text 打到主屏焦点的文本"},""" +
                    """"x":{"type":"integer","description":"主屏点击/双击/长按的物理像素 X"},""" +
                    """"y":{"type":"integer","description":"主屏点击/双击/长按的物理像素 Y"},""" +
                    """"x1":{"type":"integer","description":"swipe 起点物理像素 X"},""" +
                    """"y1":{"type":"integer","description":"swipe 起点物理像素 Y"},""" +
                    """"x2":{"type":"integer","description":"swipe 终点物理像素 X"},""" +
                    """"y2":{"type":"integer","description":"swipe 终点物理像素 Y"},""" +
                    """"duration_ms":{"type":"integer","description":"swipe/long_press/scroll 持续时间毫秒"},""" +
                    """"direction":{"type":"string","enum":["up","down","left","right"],"description":"screen_scroll 方向"},""" +
                    """"distance_ratio":{"type":"number","description":"screen_scroll 幅度 0.15-0.8"},""" +
                    """"package":{"type":"string","description":"应用操作或 logcat PID 过滤的 Android 包名（如 com.tencent.mm）"},""" +
                    """"query":{"type":"string","description":"app_list 的包名或应用名搜索词"},""" +
                    """"include_system":{"type":"boolean","description":"app_list 是否显示系统应用，默认 false"},""" +
                    """"limit":{"type":"integer","minimum":1,"maximum":200,"description":"app_list 返回数量，默认 50"},""" +
                    """"filter":{"type":"string","description":"package_list 的可选字面量过滤词"},""" +
                    """"tail_lines":{"type":"integer","minimum":1,"maximum":2000,"description":"logcat 返回行数，默认 200"},""" +
                    """"tag":{"type":"string","description":"logcat 的可选 tag"},""" +
                    """"priority":{"type":"string","enum":["V","D","I","W","E","F"],"description":"logcat 最低优先级，默认 V"},""" +
                    """"keyword":{"type":"string","description":"logcat 可选关键词（忽略大小写）"},""" +
                    """"port":{"type":"integer","minimum":1,"maximum":65535,"description":"无线 ADB 端口（如 12345），logcat 时可选显式指定"}},"required":["action"]}""",
            ).jsonObject,
        ),
    )

    /** 共享参数池：与原 host 工具 schema 同源，按动作在 [schemaFor] 中选取。 */
    private val PARAM_POOL: Map<String, JsonObject> = mapOf(
        "namespace" to jsonObj("""{"type":"string","enum":["system","secure","global"],"description":"设置命名空间"}"""),
        "key" to jsonObj("""{"type":"string","description":"screen_key 的按键名(back/home/recents/enter/delete/paste/power)"}"""),
        "value" to jsonObj("""{"type":"string","description":"settings_put 的值"}"""),
        "text" to jsonObj("""{"type":"string","description":"要输入/替换的文本"}"""),
        "x" to jsonObj("""{"type":"integer","description":"虚拟屏点击 X（0-1000 相对位置，左上角 0,0）"}"""),
        "y" to jsonObj("""{"type":"integer","description":"虚拟屏点击 Y（0-1000 相对位置）"}"""),
        "x1" to jsonObj("""{"type":"integer","description":"虚拟屏 swipe 起点 X（0-1000 相对坐标）"}"""),
        "y1" to jsonObj("""{"type":"integer","description":"虚拟屏 swipe 起点 Y（0-1000 相对坐标）"}"""),
        "x2" to jsonObj("""{"type":"integer","description":"虚拟屏 swipe 终点 X（0-1000 相对坐标）"}"""),
        "y2" to jsonObj("""{"type":"integer","description":"虚拟屏 swipe 终点 Y（0-1000 相对坐标）"}"""),
        "duration_ms" to jsonObj("""{"type":"integer","description":"持续毫秒；virtual_screen_wait 可为 0"}"""),
        "direction" to jsonObj("""{"type":"string","enum":["up","down","left","right"],"description":"滚动方向"}"""),
        "distance_ratio" to jsonObj("""{"type":"number","description":"滚动幅度 0.15-0.8"}"""),
        "package" to jsonObj("""{"type":"string","description":"Android 包名（如 com.tencent.mm）"}"""),
        "path" to jsonObj("""{"type":"string","description":"保存路径；virtual_screen_screenshot 可省略（图片附在结果上）"}"""),
        "permission" to jsonObj("""{"type":"string","description":"Android 权限名"}"""),
        "query" to jsonObj("""{"type":"string","description":"搜索词"}"""),
        "include_system" to jsonObj("""{"type":"boolean","description":"是否显示系统应用"}"""),
        "limit" to jsonObj("""{"type":"integer","minimum":1,"maximum":200,"description":"返回数量"}"""),
        "user" to jsonObj("""{"type":"integer","minimum":0,"maximum":999,"description":"Android 用户 ID，默认 0"}"""),
        "filter" to jsonObj("""{"type":"string","description":"可选字面量过滤词"}"""),
        "tail_lines" to jsonObj("""{"type":"integer","minimum":1,"maximum":2000,"description":"返回行数"}"""),
        "tag" to jsonObj("""{"type":"string","description":"可选 tag"}"""),
        "priority" to jsonObj("""{"type":"string","enum":["V","D","I","W","E","F"],"description":"最低优先级"}"""),
        "keyword" to jsonObj("""{"type":"string","description":"可选关键词（忽略大小写）"}"""),
        "port" to jsonObj("""{"type":"integer","minimum":1,"maximum":65535,"description":"无线 ADB 端口"}"""),
        "goal" to jsonObj("""{"type":"string","description":"要在虚拟屏上完成的任务描述"}"""),
        "workflow_name" to jsonObj("""{"type":"string","description":"成功且无人工介入时保存为此名称的可编辑工作流"}"""),
        "max_steps" to jsonObj("""{"type":"integer","minimum":1,"maximum":20,"description":"最多操作步数，默认 12"}"""),
        "session" to jsonObj(
            """{"type":"string","description":"虚拟屏会话 ID，默认 default；不同会话对应相互独立的虚拟屏，可并行操控多个应用"}""",
        ),
    )

    private val deferredNames: Set<String> = DEFERRED_ACTIONS.map { it.name }.toSet()

    private fun JsonObject.stringOf(key: String): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun jsonObj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
