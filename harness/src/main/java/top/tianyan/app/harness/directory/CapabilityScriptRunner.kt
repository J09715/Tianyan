package top.tianyan.app.harness.directory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * codemode 脚本运行器（借鉴 pi 的 codemode 暴露级别，Rhino 引擎）。
 *
 * 模型写一段 JS，全局对象 `capability` 提供能力调用：
 * - `capability.call(server, tool, args)` → `{ok: boolean, output: string}`，
 *   每条调用经 [innerCall] 走与直接 call 完全相同的校验、审批、嵌套留痕与脱敏；
 * - `capability.list()` / `capability.inspect(server)` → 能力域 JSON（脚本内自发现）；
 * - 脚本返回值（字符串或可 JSON 化的对象）即工具结果正文。
 *
 * 适合把多轮 call 合并成一次（循环、条件、聚合），减少模型往返轮次。
 *
 * 安全边界（与 docs/SECURITY_SURFACE.md 对应）：
 * - [ClassShutter] 全禁 Java 互操作——脚本无法触碰 java.* 与宿主类路径；
 * - 不暴露任何文件、网络、进程 API，唯一出口是 capability 绑定；
 * - 解释模式（optimizationLevel=-1，Android dex 不支持 Rhino 字节码生成）+
 *   指令观察器按 deadline 熔断死循环；
 * - 代码长度 [MAX_CODE_CHARS] 上限；每条内层调用照常审批，脚本不是免审通道。
 */
class CapabilityScriptRunner(
    private val argRedactor: (String) -> String = { it },
    /** 内层能力调用：由 CapabilityToolRouter 注入（含 workspace/审批链路与嵌套留痕）。 */
    private val innerCall: suspend (serverId: String, tool: String, args: JsonObject) -> Pair<Boolean, String>,
) {

    /** 当前执行的截止时刻；Rhino 指令观察器据此熔断（每个实例只跑一段脚本，无并发竞争）。 */
    @Volatile
    private var deadlineNanos = 0L
    private lateinit var executionContext: CoroutineContext
    private var terminalFailure: Throwable? = null

    private val contextFactory = object : ContextFactory() {
        override fun makeContext(): Context = object : Context() {
            override fun observeInstructionCount(instructionCount: Int) {
                checkExecution()
            }
        }.apply {
            languageVersion = Context.VERSION_ES6
            // Android dex 不支持 Rhino 运行期生成字节码，必须解释模式
            optimizationLevel = -1
            setClassShutter(ClassShutter { false })
            // 注意：下面这行是历史规避写法，**无法**阻止 Rhino 初始化 JavaToJSONConverters。
            // javap 对 rhino-1.9.1 实测：setter 对该类的引用次数为 0，而 getter 直读
            // 静态常量 JavaToJSONConverters.STRING。真机崩溃已在调用侧由
            // CapabilityJsonText.stringify() / installSafeJson() 切断，机理见其注释。
            // 保留此设置：若 getter 走字段分支，它仍能挡住宿主对象的 JSON 转换。
            setJavaToJSONConverter { throw Context.reportRuntimeError("Java object JSON conversion is disabled") }
        }
    }

    suspend fun execute(code: String, timeoutMs: Long): Pair<Boolean, String> {
        if (code.length > MAX_CODE_CHARS) {
            return false to "脚本过长（${code.length} 字符，上限 $MAX_CODE_CHARS）。请精简逻辑或拆分为多次 script 调用。"
        }
        // 引擎探针放在最前：R8 裁剪/改名导致的求值器缺失必须在编译脚本前就变成可读错误。
        engineUnavailableReason()?.let { return false to it }
        deadlineNanos = System.nanoTime() + timeoutMs.coerceAtLeast(0) * 1_000_000
        terminalFailure = null
        val startedAt = System.currentTimeMillis()
        return try {
            withContext(Dispatchers.IO) {
                executionContext = currentCoroutineContext()
                checkExecution()
                runScript(code)
            }
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            t.findCause<CancellationException>()?.let { throw it }
            t.findCause<ScriptCallInterrupted>()?.let { throw it }
            if (t.findCause<ScriptDeadlineExceeded>() != null) {
                false to "脚本超时（上限 ${timeoutMs / 1000} 秒）已中止；已执行的内层调用与结果见本工具调用的审计记录，可先用部分结果继续任务。"
            } else {
                false to "脚本执行失败：${diagnose(t)}"
            }
        }.let { (ok, output) ->
            ok to capOutput(output, startedAt)
        }
    }

    private fun runScript(code: String): Pair<Boolean, String> {
        val cx = contextFactory.enterContext()
        try {
            cx.instructionObserverThreshold = INSTRUCTION_OBSERVER_THRESHOLD
            val scope = cx.initStandardObjects()
            installSafeJson(cx, scope)

            val capability = cx.newObject(scope)
            defineFunction(capability, "call") { cx1, scope1, args ->
                capabilityCall(cx1, scope1, args)
            }
            defineFunction(capability, "list") { cx1, scope1, _ ->
                CapabilityJsonText.parse(cx1, scope1, capabilityListJson())
            }
            defineFunction(capability, "inspect") { cx1, scope1, args ->
                CapabilityJsonText.parse(cx1, scope1, capabilityInspectJson(args.getOrNull(0)?.toString().orEmpty()))
            }
            ScriptableObject.putProperty(scope, "capability", capability)

            val result = cx.evaluateString(scope, code, "codemode", 1, null)
            checkExecution()
            val output = when {
                result == null || result is Undefined -> "(脚本无返回值)"
                result is CharSequence -> result.toString()
                else -> CapabilityJsonText.stringify(result)
            }
            checkExecution()
            return true to output
        } finally {
            Context.exit()
        }
    }

    /** 内层调用的 JS 绑定：deadline 预检 → 阻塞执行内层（已在 IO 线程）→ 组装 {ok, output}。 */
    private fun capabilityCall(cx: Context, scope: Scriptable, args: Array<Any?>): Any? {
        checkExecution()
        val serverId = args.getOrNull(0)?.toString()?.trim().orEmpty()
        val tool = args.getOrNull(1)?.toString()?.trim().orEmpty()
        val argsJson = if (args.size > 2 && args[2] != null) {
            CapabilityJsonText.stringify(args[2])
        } else {
            "{}"
        }
        val callArgs = runCatching { Json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        // Keep the parent Job, but use this blocking thread's event loop rather than
        // redispatching to IO (which could deadlock a limited dispatcher).
        val (ok, output) = try {
            runBlocking(executionContext.minusKey(ContinuationInterceptor)) {
                checkExecution()
                val remainingMs = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(1)
                withTimeoutOrNull(remainingMs) { innerCall(serverId, tool, callArgs) }
                    ?: throw ScriptDeadlineExceeded()
            }
        } catch (t: Throwable) {
            terminalFailure = t
            throw t
        }
        checkExecution()
        return cx.newObject(scope).apply {
            put("ok", this, ok)
            put("output", this, output)
        }
    }


    /** 用纯 Kotlin 实现接管沙箱的 `JSON.stringify` 与 `JSON.parse`，详见 [CapabilityJsonText]。 */
    private fun installSafeJson(cx: Context, scope: Scriptable) {
        CapabilityJsonText.install(cx, scope, ::defineFunction)
    }




    /**
     * 引擎可用性探针。Rhino 在解释模式下用 `Class.forName("org.mozilla.javascript.Interpreter")`
     * 拿求值器类，再对该 Class 直接调 `getDeclaredConstructor().newInstance()`（Kit.newInstanceOrNull，
     * 其 catch 列表不含 NPE）。release 构建若没有 keep 该类，R8 会改名/剥离它 →
     * `Class.forName` 返回 null → 编译阶段抛一句不可读的 NPE。这里提前把失败翻译成可操作的原因。
     */
    private fun engineUnavailableReason(): String? {
        val evaluator = runCatching { Class.forName(INTERPRETER_CLASS, false, javaClass.classLoader) }
            .getOrNull()
            ?: return "脚本引擎不可用：Rhino 求值器类 $INTERPRETER_CLASS 未随本构建保留（R8 keep 规则缺失）。" +
                "请在 harness/consumer-rules.pro 保留该类后重新构建；当前可改用 use_capability(action=\"call\") 逐条调用。"
        return if (runCatching { evaluator.getDeclaredConstructor() }.isSuccess) {
            null
        } else {
            "脚本引擎不可用：Rhino 求值器 $INTERPRETER_CLASS 缺少无参构造器（R8 成员裁剪）。请检查 consumer-rules.pro 的 keep 规则。"
        }
    }

    /**
     * 组装可诊断的失败原因。绝不交给 argRedactor：
     * 脱敏是针对**调用参数**的，对**错误文本**做脱敏会把根因嚼成无法辨认的碎片。
     */
    private fun diagnose(t: Throwable, limit: Int = 6): String = buildString {
        var cursor: Throwable? = t
        var depth = 0
        while (cursor != null && depth < limit) {
            if (depth > 0) append(" ← ")
            append(cursor::class.simpleName ?: cursor::class.java.name)
            cursor.message?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
            cursor = cursor.cause
            depth++
        }
        if (t.findCause<LinkageError>() != null) {
            append("\n提示：这是类初始化/类缺失错误（LinkageError）；Rhino 所依赖的 java.beans 在 Android 上不可用，脚本后端可能无法启动。")
        }
        if (contains("getDeclaredConstructor")) {
            append(
                "\n提示：Rhino 求值器实例化拿到 null Class（Class.forName 失败）。" +
                    "release 构建必须在 harness/consumer-rules.pro keep $INTERPRETER_CLASS。",
            )
        }
    }.take(1_000)

    private fun capabilityListJson(): String = buildString {
        append("{\"domains\":[{\"id\":\"")
        append(HostCapabilityDirectory.SERVER_ID)
        append("\",\"name\":\"宿主低频能力域\",\"tools\":")
        append(HostCapabilityDirectory.DEFERRED_ACTIONS.size)
        append("}]}")
    }

    private fun capabilityInspectJson(serverId: String): String =
        if (serverId.trim().lowercase() == HostCapabilityDirectory.SERVER_ID) {
            buildString {
                append("{\"tools\":[")
                append(
                    HostCapabilityDirectory.DEFERRED_ACTIONS.joinToString(",") { action ->
                        buildString {
                            append("{\"name\":\"${action.name}\",\"description\":")
                            append(jsonString(action.description))
                            append(",\"params\":")
                            append(HostCapabilityDirectory.schemaFor(action.name).toString())
                            append("}")
                        }
                    },
                )
                append("]}")
            }
        } else {
            "{\"note\":\"MCP 能力域脚本内暂不支持自动发现，请先用 use_capability(action=\\\"inspect\\\", server=…) 获取清单后按名调用\"}"
        }

    private fun jsonString(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun defineFunction(target: Scriptable, name: String, block: (Context, Scriptable, Array<Any?>) -> Any?) {
        val function = object : BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<Any?>): Any? =
                block(cx, scope, args)
        }
        ScriptableObject.putProperty(target, name, function)
    }

    private fun capOutput(output: String, startedAt: Long): String =
        if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) +
                "\n…[脚本结果过长已截断；内层调用明细见审计记录，耗时 ${System.currentTimeMillis() - startedAt}ms]"
        } else {
            output
        }

    private fun checkExecution() {
        terminalFailure?.let { throw it }
        executionContext.ensureActive()
        if (System.nanoTime() >= deadlineNanos) {
            val failure = ScriptDeadlineExceeded()
            terminalFailure = failure
            throw failure
        }
    }

    private inline fun <reified T : Throwable> Throwable.findCause(): T? {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    /** deadline 熔断专用类型：与一般脚本异常区分，给出可续作任务的提示。 */
    private class ScriptDeadlineExceeded : RuntimeException()

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val MAX_TIMEOUT_SECONDS = 300L
        const val MAX_CODE_CHARS = 32_768
        const val MAX_OUTPUT_CHARS = 64_000

        /** Rhino 求值器类名：既是 classOrNull 的加载目标，也是 release 构建必须 keep 的名字。 */
        private const val INTERPRETER_CLASS = "org.mozilla.javascript.Interpreter"

        /** 指令观察阈值：解释模式下每次观察的指令间隔，控制死循环熔断的粒度。 */
        private const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000
    }
}
