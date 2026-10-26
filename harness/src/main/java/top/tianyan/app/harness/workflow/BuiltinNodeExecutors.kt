package top.tianyan.app.harness.workflow

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import top.tianyan.app.core.common.shell.ShellQuote
import top.tianyan.app.core.model.workflow.NodeExecutionOutput
import top.tianyan.app.core.model.workflow.NodeRunStatus
import top.tianyan.app.core.model.workflow.WorkflowApprovalRequest
import top.tianyan.app.core.model.workflow.WorkflowNode
import top.tianyan.app.core.model.workflow.WorkflowNodeType
import top.tianyan.app.core.model.workflow.WorkflowRuntimeContext
import top.tianyan.app.core.model.workflow.previousOutput
import top.tianyan.app.core.model.workflow.resultReference
import top.tianyan.app.core.model.workflow.WorkflowResultSchema
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.shell.ShellCommand

class PassthroughNodeExecutor() : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.TRIGGER, WorkflowNodeType.TERMINAL_OUTPUT)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val missing = node.config["requiredVariables"]
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.filter { context.globalVariables[it].isNullOrBlank() }
            .orEmpty()
        return if (missing.isEmpty()) {
            NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = if (node.type == WorkflowNodeType.TERMINAL_OUTPUT) context.previousOutput().ifBlank { node.description } else node.description)
        } else {
            NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "缺少变量：${missing.joinToString()}")
        }
    }
}

class ApprovalNodeExecutor(
    private val broker: WorkflowApprovalBroker,
) : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.HUMAN_APPROVAL)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        onProgress(NodeRunStatus.WAITING_APPROVAL, node.description.ifBlank { "等待人工确认" })
        val decision = broker.await(
            WorkflowApprovalRequest(
                executionId = context.executionId,
                nodeId = node.id,
                title = node.title,
                description = listOf(interpolate(node.description, context), context.previousOutput().take(24_000)).filter(String::isNotBlank).joinToString("\n\n"),
                requestedVariables = node.config["requestedVariables"]
                    ?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
            ),
        )
        return if (decision.approved) {
            NodeExecutionOutput(NodeRunStatus.SUCCESS, textOutput = "已确认", variables = decision.variables)
        } else {
            NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 130, error = "用户拒绝执行")
        }
    }
}

class LinuxNodeExecutor(
    private val linuxRuntime: LinuxRuntime,
) : NodeExecutor {
    override val supportedTypes = setOf(
        WorkflowNodeType.BASH_COMMAND,
        WorkflowNodeType.PROCESS_SERVICE,
        WorkflowNodeType.TIANYAN_BUILD,
    )

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput = withContext(Dispatchers.IO) {
        val commandLine = commandFor(node, context)
            ?: return@withContext NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "节点缺少可执行配置")
        onProgress(NodeRunStatus.STREAMING, commandLine)
        if (node.type == WorkflowNodeType.PROCESS_SERVICE) {
            val process = linuxRuntime.startBackground(
                id = "workflow:${context.executionId}:${node.id}",
                command = ShellCommand(commandLine, workingDirectory = context.workspacePath),
                type = ProcessType.SERVICE,
            )
            return@withContext NodeExecutionOutput(
                status = NodeRunStatus.SUCCESS,
                textOutput = "后台进程已启动：${process.id}",
                variables = mapOf("PROCESS_ID" to process.id),
            )
        }

        val streamed = StringBuilder()
        val result = coroutineScope {
            val reporter = launch {
                while (true) {
                    delay(300)
                    val snapshot = synchronized(streamed) { streamed.toString().takeLast(24_000) }
                    if (snapshot.isNotBlank()) onProgress(NodeRunStatus.STREAMING, snapshot)
                }
            }
            try { linuxRuntime.execute(
            ShellCommand(
                commandLine = commandLine,
                workingDirectory = node.config["workingDirectory"]?.let { interpolate(it, context) }
                    ?: context.workspacePath,
                timeoutMs = node.timeoutSeconds * 1_000L,
                forcePty = node.type == WorkflowNodeType.TIANYAN_BUILD,
                onOutput = { line -> synchronized(streamed) {
                    streamed.appendLine(line)
                    if (streamed.length > 2_000_000) streamed.delete(0, streamed.length - 2_000_000)
                } },
            ),
            ) } finally { reporter.cancel() }
        }
        val combined = buildString {
            append(streamed.toString())
            if (result.stdout.isNotBlank() && result.stdout !in this) append(result.stdout)
            if (result.stderr.isNotBlank()) appendLine().append(result.stderr)
        }.trim()
        NodeExecutionOutput(
            status = if (result.isSuccess) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = result.exitCode,
            textOutput = combined,
            artifacts = APK_PATH.findAll(combined).map { it.value }.distinct().toList(),
            durationMs = result.durationMs,
            error = result.stderr.takeIf { !result.isSuccess }?.ifBlank { "命令退出码 ${result.exitCode}" },
        )
    }

    private fun commandFor(node: WorkflowNode, context: WorkflowRuntimeContext): String? = when (node.type) {
        WorkflowNodeType.BASH_COMMAND, WorkflowNodeType.PROCESS_SERVICE -> node.config["command"]?.let { interpolate(it, context) }
        WorkflowNodeType.TIANYAN_BUILD -> when (node.config["mode"]?.lowercase()) {
            "doctor" -> "tianyan-build doctor ."
            "analyze" -> "tianyan-build analyze ."
            else -> "tianyan-build ${node.config["projectType"] ?: "android"} . ${node.config["task"] ?: "assembleDebug"} --offline"
        }
        else -> null
    }

    private fun interpolate(template: String, context: WorkflowRuntimeContext): String = VARIABLE.replace(template) { match ->
        val key = match.groupValues[1]
        val value = when {
            context.resultReference(key) != null -> context.resultReference(key).orEmpty()
            key == "WORKSPACE_PATH" -> context.workspacePath
            key == "previous.output" -> context.previousOutput()
            key.endsWith(".output") -> context.nodeOutputs[key.removeSuffix(".output")]?.textOutput.orEmpty()
            else -> context.globalVariables[key].orEmpty()
        }
        ShellQuote.of(value)
    }

    private companion object {
        // Android ICU treats an unescaped closing brace as a syntax error, while the
        // desktop JDK accepts it as a literal. Escape both braces so release builds
        // cannot crash while initializing this executor.
        val VARIABLE = Regex("\\$\\{([A-Za-z0-9_.\\-]+)\\}")
        val APK_PATH = Regex("(?:/|[.]{1,2}/)?(?:[^\\s/'\"]+/)*[^\\s/'\"]+[.]apk\\b", RegexOption.IGNORE_CASE)
    }
}

class AgentNodeExecutor(
    private val agentExecution: WorkflowAgentExecutionPort,
) : NodeExecutor {
    override val supportedTypes = setOf(WorkflowNodeType.AGENT_INFERENCE, WorkflowNodeType.SUBAGENT_DELEGATE)

    override suspend fun execute(
        node: WorkflowNode,
        context: WorkflowRuntimeContext,
        onProgress: suspend (NodeRunStatus, String) -> Unit,
    ): NodeExecutionOutput {
        val schema = node.config[WorkflowResultSchema.CONFIG_KEY]?.let { raw ->
            try { WorkflowResultSchema.parse(raw) } catch (error: IllegalArgumentException) {
                return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "结果 schema 无效：${error.message}")
            } catch (error: IllegalStateException) {
                return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, error = "结果 schema 无效：${error.message}")
            }
        }
        val promptTemplate = node.config["prompt"]
            ?.takeIf(String::isNotBlank)
            ?: node.description.takeIf(String::isNotBlank)
            ?: context.previousOutput().takeIf(String::isNotBlank)
            ?: return NodeExecutionOutput(
                status = NodeRunStatus.FAILED,
                exitCode = 2,
                error = "${node.title} 缺少 prompt，且没有可用的上游输出",
            )
        val request = WorkflowAgentRequest(
            executionId = context.executionId,
            nodeId = node.id,
            nodeTitle = node.title,
            prompt = interpolateAgentPrompt(promptTemplate, context) + if (schema == null) "" else
                "\n\n最终答复必须仅包含符合以下 schema 的 JSON 对象，不要使用 Markdown 围栏或附加说明：\n$schema",
            workspacePath = context.workspacePath,
            modelId = node.config["modelId"]?.takeIf(String::isNotBlank)
                ?: context.globalVariables["WORKFLOW_MODEL_ID"]?.takeIf(String::isNotBlank),
            modelVariant = node.config["modelVariant"]?.takeIf(String::isNotBlank)
                ?: context.globalVariables["WORKFLOW_MODEL_VARIANT"]?.takeIf(String::isNotBlank),
            role = node.config["role"].orEmpty(),
            department = node.config["department"].orEmpty(),
            agentQuery = node.config["agentQuery"].orEmpty(),
            taskName = node.config["taskName"].orEmpty(),
            writePaths = node.config["writePaths"]
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotBlank)
                .orEmpty(),
        )
        onProgress(
            NodeRunStatus.STREAMING,
            if (node.type == WorkflowNodeType.SUBAGENT_DELEGATE) "正在派发子智能体…" else "智能体正在推理…",
        )
        val result = when (node.type) {
            WorkflowNodeType.AGENT_INFERENCE -> agentExecution.infer(request)
            WorkflowNodeType.SUBAGENT_DELEGATE -> agentExecution.delegate(request)
            else -> error("Unsupported agent node ${node.type}")
        }
        val structuredResult = if (result.success && schema != null) {
            try { WorkflowResultSchema.validateResult(result.output, schema) } catch (error: IllegalArgumentException) {
                return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, textOutput = result.output, error = "结构化结果校验失败：${error.message}")
            } catch (error: IllegalStateException) {
                return NodeExecutionOutput(NodeRunStatus.FAILED, exitCode = 2, textOutput = result.output, error = "结构化结果校验失败：${error.message}")
            }
        } else null
        return NodeExecutionOutput(
            status = if (result.success) NodeRunStatus.SUCCESS else NodeRunStatus.FAILED,
            exitCode = if (result.success) 0 else 1,
            textOutput = result.output,
            variables = buildMap {
                put(if (node.type == WorkflowNodeType.AGENT_INFERENCE) "AGENT_OUTPUT" else "SUBAGENT_OUTPUT", result.output)
                result.toolCallCount?.let { put("AGENT_TOOL_CALL_COUNT", it.toString()) }
            },
            error = result.output.takeIf { !result.success },
            structuredResult = structuredResult,
        )
    }
}

internal fun interpolateAgentPrompt(template: String, context: WorkflowRuntimeContext): String =
    interpolate(template, context)
