package top.tianyan.app.harness.acp

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AgentApprovalRequestEntity
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.model.SessionRunState
import top.tianyan.app.harness.AssistantText
import top.tianyan.app.harness.UserMessage
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.projection.SessionStateMirrors
import java.security.MessageDigest

/** 一次引擎回合的结果，语义与 HarnessLoop 的私有 RunResult 对齐但独立（跨包可见）。 */
enum class AcpTurnOutcome {
    COMPLETED,
    CANCELLED,
    WAITING_APPROVAL,
    FAILED,
}

/**
 * 引擎型智能体的会话执行器。
 *
 * 与 HarnessLoop 的分工：HarnessLoop 跑的是「天衍本体」——天衍自己的工具循环、
 * 自己的提示词、自己的审批闸门。当一个会话绑定了引擎型智能体（Claude Code /
 * Codex / DSH 等），执行权就交给引擎进程：天衍退回到宿主位置，只做三件事——
 * 把用户输入递进去、把引擎的流式输出搬上屏、把引擎的权限请求接到天衍既有的
 * 审批通道上。
 *
 * 这不是「抄一份人格提示词」，而是真换执行体。因此：
 * - 引擎会话的 assistant 输出、工具调用、思考都由 ACP 事件驱动；
 * - 审批走 [AgentApprovalRepository] 同一张表，用户看到的审批卡片与本体一致；
 * - 引擎进程随会话存活，多个回合复用同一条连接（引擎自己维护上下文）。
 */
@Singleton
class AcpSessionRunner @Inject constructor(
    private val connectionFactory: AcpEngineConnectionFactory,
    private val catalog: AgentEngineCatalog,
    private val projector: SessionMessageProjector,
    private val stateMirrors: SessionStateMirrors,
    private val approvalRepository: AgentApprovalRepository,
    private val logger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 天衍会话 id → 引擎连接。连接生命周期跟随会话，跨回合复用。 */
    private val connections = ConcurrentHashMap<String, EngineBinding>()

    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 会话当前绑定的引擎（供 UI 显示「运行中」与引擎名）。 */
    private val _activeEngines = MutableStateFlow<Map<String, String>>(emptyMap())
    val activeEngines: StateFlow<Map<String, String>> = _activeEngines.asStateFlow()

    private data class EngineBinding(
        val engineId: String,
        val connection: AcpEngineConnection,
        val engineSessionId: String,
        var pendingPermission: AcpPermissionRequest? = null,
    )

    fun isEngineSession(sessionId: String): Boolean = connections.containsKey(sessionId)

    fun boundEngineId(sessionId: String): String? = connections[sessionId]?.engineId

    /**
     * 跑一个引擎回合。调用方（HarnessLoop）负责会话状态机的收尾，
     * 这里只负责把这一轮跑完并返回结构化结果。
     */
    suspend fun runTurn(
        sessionId: String,
        engineId: String,
        workspace: String,
        userText: String,
        imageUrls: List<String> = emptyList(),
    ): AcpTurnOutcome {
        val profile = catalog.find(engineId) ?: return fail(
            sessionId,
            "会话绑定的智能体引擎不存在：$engineId（可能已被移除，请在插件中心重新安装）",
        )

        // 用户消息先上屏：引擎的流式输出到达前，气泡不该是空的。
        projector.publishPersisted(
            sessionId,
            UserMessage(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                text = userText,
                imageUrls = imageUrls,
            ),
        )

        val binding = try {
            ensureBinding(sessionId, profile, workspace)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            return fail(sessionId, friendlyEngineError(profile, t))
        }

        stateMirrors.setRunState(sessionId, SessionRunState.RUNNING)
        stateMirrors.setStatus(sessionId, "${profile.name} 运行中")

        val assistantId = UUID.randomUUID().toString()
        val assistantAt = System.currentTimeMillis()
        val textBuffer = StringBuilder()

        val eventJob: Job = scope.launch {
            binding.connection.eventFlow.collect { event ->
                if (event.sessionId != sessionId) return@collect
                when (event) {
                    is AcpEvent.AssistantText -> {
                        textBuffer.append(event.text)
                        projector.streamText(sessionId, assistantId, assistantAt, textBuffer.toString())
                    }
                    is AcpEvent.Thought -> stateMirrors.setThinkingLive(sessionId, true)
                    is AcpEvent.ToolCall -> {
                        val title = toolCallTitle(event.raw)
                        if (title.isNotBlank()) stateMirrors.setStatus(sessionId, title)
                    }
                    is AcpEvent.Permission -> registerPermission(sessionId, binding, event.request)
                    is AcpEvent.TurnFinished -> stateMirrors.setThinkingLive(sessionId, false)
                    is AcpEvent.Exited -> stateMirrors.setStatus(sessionId, "${profile.name} 进程已退出")
                }
            }
        }

        return try {
            // 先等订阅真正挂上再发 prompt：SharedFlow 无 replay，抢跑会吞掉引擎最早的输出。
            binding.connection.awaitSubscriber()
            val result = binding.connection.prompt(binding.engineSessionId, translateTianyanSlash(userText))
            eventJob.cancel()
            projector.endStreaming(sessionId)
            if (textBuffer.isNotEmpty()) {
                projector.publishPersisted(
                    sessionId,
                    AssistantText(id = assistantId, createdAt = assistantAt, text = textBuffer.toString()),
                )
            }
            stateMirrors.setStatus(sessionId, null)
            stateMirrors.setThinkingLive(sessionId, false)
            if (binding.pendingPermission != null) AcpTurnOutcome.WAITING_APPROVAL
            else if (result.stopReason == STOP_REASON_CANCELLED) AcpTurnOutcome.CANCELLED
            else AcpTurnOutcome.COMPLETED
        } catch (cancellation: CancellationException) {
            eventJob.cancel()
            withContext(NonCancellable) {
                runCatching { binding.connection.cancel(binding.engineSessionId) }
                projector.endStreaming(sessionId)
            }
            throw cancellation
        } catch (t: Throwable) {
            eventJob.cancel()
            projector.endStreaming(sessionId)
            fail(sessionId, "引擎 ${profile.name} 执行失败：${t.message ?: t::class.simpleName}")
        }
    }

    /** 用户对引擎权限请求的裁决：回包给引擎并解除 WAITING_APPROVAL。 */
    suspend fun resolvePermission(sessionId: String, approved: Boolean): Boolean {
        val binding = connections[sessionId] ?: return false
        val request = binding.pendingPermission ?: return false
        binding.pendingPermission = null
        return runCatching {
            binding.connection.respondToPermission(request, approved)
            true
        }.getOrElse { t ->
            logger.w("ACP 权限回包失败：$sessionId", t)
            false
        }
    }

    /** 会话删除/切换时释放引擎进程，避免孤儿进程常驻。 */
    suspend fun release(sessionId: String) {
        val binding = connections.remove(sessionId) ?: return
        _activeEngines.value = _activeEngines.value - sessionId
        runCatching { binding.connection.close() }
    }

    suspend fun releaseAll() {
        connections.keys.toList().forEach { release(it) }
    }

    private suspend fun ensureBinding(
        sessionId: String,
        profile: AgentEngineProfile,
        workspace: String,
    ): EngineBinding = locks.getOrPut(sessionId) { Mutex() }.withLock {
        connections[sessionId]?.takeIf { it.engineId == profile.id && it.connection.isAlive }?.let {
            return@withLock it
        }
        // 换引擎或进程已死：先清掉旧连接再重建，否则会把新输入写进死进程。
        connections.remove(sessionId)?.let { stale -> runCatching { stale.connection.close() } }

        val connection = connectionFactory.open(profile, workspace)
        try {
            connection.initialize()
            val engineSessionId = connection.newSession(
                tianyanSessionId = sessionId,
                cwd = workspace.ifBlank { "/workspace" },
                mcpServers = emptyList(),
            )
            EngineBinding(profile.id, connection, engineSessionId).also { binding ->
                connections[sessionId] = binding
                _activeEngines.value = _activeEngines.value + (sessionId to profile.name)
            }
        } catch (t: Throwable) {
            runCatching { connection.close() }
            throw t
        }
    }

    private suspend fun registerPermission(
        sessionId: String,
        binding: EngineBinding,
        request: AcpPermissionRequest,
    ) {
        binding.pendingPermission = request
        val summary = toolCallTitle(request.toolCall ?: JsonObject(emptyMap()))
            .ifBlank { "引擎请求执行一个操作" }
        val mode = approvalRepository.currentMode()
        // FULL_ACCESS：用户已明确把该会话的工具闸门交给智能体，不再逐次打断引擎。
        if (mode == ApprovalMode.FULL_ACCESS) {
            runCatching { binding.connection.respondToPermission(request, allow = true) }
            binding.pendingPermission = null
            return
        }
        val now = System.currentTimeMillis()
        approvalRepository.create(
            AgentApprovalRequestEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                toolCallId = request.requestId.toString(),
                toolName = request.toolCall?.get("title")?.toString()?.trim('"').orEmpty().ifBlank { "engine_tool" },
                argumentsJson = (request.toolCall ?: JsonObject(emptyMap())).toString(),
                workspace = "",
                riskLevel = "high",
                reason = "引擎智能体请求执行工具调用，需要你确认。",
                summary = summary,
                createdAt = now,
                argsHash = argsHash(request),
                expiresAt = now + PERMISSION_TTL_MS,
            ),
        )
        stateMirrors.setRunState(sessionId, SessionRunState.WAITING_APPROVAL)
        stateMirrors.setStatus(sessionId, "等待用户批准")
    }

    private fun argsHash(request: AcpPermissionRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((request.toolCall?.toString() ?: request.requestId.toString()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** 从 ACP toolCall 载荷里取一个可读标题；字段名按协议 wire schema。 */
    private fun toolCallTitle(raw: JsonObject): String =
        listOf("title", "kind", "toolCallId")
            .firstNotNullOfOrNull { key ->
                (raw[key] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }.orEmpty()

    private suspend fun fail(sessionId: String, message: String): AcpTurnOutcome {
        // 引擎失败落盘（runtime.log），脱敏由 AppLogger 的 SensitiveDataRedactor 统一处理；
        // 这样用户遇到「执行失败」后，能从日志里拿到 stderr / 协议错误的完整上下文。
        logger.e("ACP[session:$sessionId] $message")
        stateMirrors.setError(sessionId, message)
        stateMirrors.setRunState(sessionId, SessionRunState.FAILED)
        stateMirrors.setStatus(sessionId, null)
        projector.publishPersisted(
            sessionId,
            AssistantText(
                id = UUID.randomUUID().toString(),
                createdAt = System.currentTimeMillis(),
                text = "❌ $message",
            ),
        )
        return AcpTurnOutcome.FAILED
    }

    /**
     * 引擎未安装时给出可执行的下一步，而不是把 npm 的原始报错丢给用户。
     * 「命令找不到」在 PRoot 里表现为 exit 127 或 shell 的 not found 文本。
     */
    private fun friendlyEngineError(profile: AgentEngineProfile, throwable: Throwable): String {
        val detail = throwable.message.orEmpty()
        val missing = detail.contains("not found", ignoreCase = true) ||
            detail.contains("No such file", ignoreCase = true) ||
            detail.contains("127")
        return if (missing) {
            "智能体引擎 ${profile.name} 尚未安装。请到「插件与工具中心 → 智能体生态」安装 ${profile.name} 后重试。"
        } else {
            "无法拉起智能体引擎 ${profile.name}：${detail.ifBlank { throwable::class.simpleName }}"
        }
    }

    private companion object {
        const val PERMISSION_TTL_MS = 10 * 60 * 1000L
        const val STOP_REASON_CANCELLED = "cancelled"
    }
}

/**
 * 把天衍本体的 slash 快捷命令翻译成引擎能理解的纯文本任务描述。
 *
 * 天衍的 `/run`、`/install`、`/git` 等是给天衍本体 LLM 的提示词约定（通过系统提示词
 * 解释），但绑定引擎会话时用户输入会被原样交给引擎进程。dsh / Claude Code 等引擎
 * 有各自的 slash 命令集，收到 `/run` 会报 `Unknown ACP command: /run`。
 * 因此引擎会话发送前必须把天衍自己的命令翻译成一句明确的自然语言任务。
 */
internal fun translateTianyanSlash(text: String): String {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("/")) return text
    val (command, rest) = trimmed.split(' ', limit = 2).let { parts ->
        parts[0] to parts.getOrElse(1) { "" }
    }
    val tail = rest.trim()
    val translated = when (command) {
        "/run" -> if (tail.isBlank()) "运行当前工作区的入口代码" else "运行：$tail"
        "/install" -> if (tail.isBlank()) "安装当前项目所需的依赖" else "安装依赖：$tail"
        "/init" -> if (tail.isBlank()) "初始化当前项目" else "初始化项目：$tail"
        "/git" -> if (tail.isBlank()) "查看 git 状态" else "执行 git 操作：$tail"
        "/test" -> if (tail.isBlank()) "运行当前项目的测试" else "运行测试：$tail"
        "/wf" -> if (tail.isBlank()) "执行工作流" else "执行工作流：$tail"
        "/help", "/clear" -> return text
        else -> return text
    }
    return translated
}
