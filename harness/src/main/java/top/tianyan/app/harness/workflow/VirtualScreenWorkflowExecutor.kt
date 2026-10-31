package top.tianyan.app.harness.workflow

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.VirtualScreenWorkflowActions
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext
import top.tianyan.app.harness.PhoneAgentServices
import top.tianyan.app.harness.VirtualScreenHostActions
import top.tianyan.app.runtime.virtualdisplay.VirtualDisplayCoordinator
import top.tianyan.app.runtime.virtualdisplay.VirtualScreenToolkit

/** Delegated by HOST_ACTION; replays deterministic primitives without calling a model. */
class VirtualScreenWorkflowExecutor(
    private val coordinator: VirtualDisplayCoordinator,
    private val toolkit: VirtualScreenToolkit,
    private val services: PhoneAgentServices,
    private val runs: VirtualScreenWorkflowRuns,
) {
    suspend fun execute(node: WorkflowNode, context: WorkflowRuntimeContext): NodeExecutionOutput = coroutineScope {
        val args = virtualWorkflowArguments(node, context)
        val session = args.getValue("session").jsonPrimitive.content
        val action = args.getValue("action").jsonPrimitive.content
        val waitMs = args["wait_ms"]!!.jsonPrimitive.content.toLong()
        val screen = runs.screen(context.executionId, session)
        if (!screen.lock.tryLock()) return@coroutineScope failed("同一虚拟屏的步骤不能并行执行；请按成功端口依次连线。")
        val task = screen.control
        try {
            var version = task.awaitReady()
            val manualRevision = task.manualRevision()
            if (screen.revision != null && screen.revision != manualRevision) {
                return@coroutineScope failed("步骤之间存在人工介入，请核对页面后重新运行工作流。")
            }
            screen.revision = manualRevision
            val actions = VirtualScreenHostActions(
                coordinator, toolkit, PACKAGE,
                requireHostIdentifier = { values, key, pattern ->
                    string(values, key).trim().also { require(pattern.matches(it)) { "$key 格式不合法" } }
                },
                requireString = ::string,
                requireInt = { values, key -> string(values, key).toIntOrNull() ?: error("$key 必须为整数") },
                optionalLong = { values, key, default, min, max ->
                    values[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.also {
                        require(it in min..max) { "$key 必须在 $min–$max" }
                    } ?: default
                },
                optionalSession = { session },
                canInject = { task.isCurrent(version) },
            )
            services.log(session, "WorkflowAction execution=${context.executionId} node=${node.id} action=$action waitMs=$waitMs")
            version = task.awaitReady()
            if (task.manualRevision() != manualRevision) {
                return@coroutineScope failed("发出操作前存在人工介入，请核对页面后重新运行工作流。")
            }
            // closeSession cancels registered tasks: release this run's ownership before closing.
            if (action == "virtual_screen_close") runs.beforeClose(context.executionId, session)
            val (success, message) = actions.execute(action, args)
            if (success) delay(waitMs)
            if (action != "virtual_screen_close") task.awaitReady()
            if (action != "virtual_screen_close" && task.manualRevision() != manualRevision) {
                return@coroutineScope failed("步骤期间存在人工介入，工作流已中止；请核对页面和坐标后重新运行。")
            }
            services.log(session, "WorkflowResult node=${node.id} success=$success")
            NodeExecutionOutput(
                if (success) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
                exitCode = if (success) 0 else 1, textOutput = message,
                variables = mapOf("VIRTUAL_SCREEN_SESSION" to session), error = message.takeIf { !success },
            )
        } finally {
            screen.lock.unlock()
        }
    }

    private fun failed(message: String) = NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 1, error = message)
    private fun string(args: JsonObject, key: String): String = args[key]?.jsonPrimitive?.contentOrNull
        ?: error("缺少参数：$key")

    companion object {
        private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
        fun handles(action: String) = VirtualScreenWorkflowActions.all.any { it.id == action }
    }
}

/** Validate edited parameters before any screen or input mutation. Never trim input content. */
internal fun virtualWorkflowArguments(node: WorkflowNode, context: WorkflowRuntimeContext): JsonObject {
    val values = node.config.mapValues { (key, value) ->
        interpolate(value, context).let { if (key == "text") it else it.trim() }
    }.toMutableMap()
    val action = values["action"].orEmpty()
    require(VirtualScreenWorkflowExecutor.handles(action)) { "不支持的虚拟屏工作流动作：$action" }
    val definition = VirtualScreenWorkflowActions.all.first { it.id == action }
    val fields = definition.fields.map { it.key }.toSet()
    values.keys.retainAll(fields + "action")
    values["session"] = values["session"].orEmpty().ifBlank { "workflow-${context.executionId}" }
    definition.fields.filter { it.required }.forEach {
        val missing = if (it.key == "text") values[it.key].isNullOrEmpty() else values[it.key].isNullOrBlank()
        require(!missing) { "缺少参数：${it.label}" }
    }
    if (action == "virtual_screen_set_text") values.putIfAbsent("text", "")
    for (key in listOf("x", "y", "x1", "y1", "x2", "y2").filter { it in fields }) {
        values[key]?.let { require(it.toIntOrNull() in 0..1000) { "$key 必须为 0–1000 的整数" } }
    }
    val wait = values["wait_ms"].orEmpty().ifBlank { "0" }
    require(wait.toLongOrNull() in 0L..600_000L) { "操作后等待必须为 0–600000ms 的整数" }
    values["wait_ms"] = wait
    values["duration_ms"]?.takeIf { it.isNotBlank() }?.let {
        val range = when (action) {
            "virtual_screen_wait" -> 0L..600_000L
            "virtual_screen_long_press" -> 200L..5000L
            else -> 50L..5000L
        }
        require(it.toLongOrNull() in range) { "duration_ms 必须在 $range" }
    }
    // Empty optional values should use the primitive's defaults, not become malformed numbers.
    if (values["duration_ms"]?.isBlank() == true) values.remove("duration_ms")
    return JsonObject(values.mapValues { JsonPrimitive(it.value) })
}
