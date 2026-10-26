package top.tianyan.app.harness.mcp

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.core.ResourceCleanupException
import top.tianyan.app.runtime.shell.LinuxSession

/**
 * STDIO 泵的单行大小防御阶梯（与 [McpResponseSizeLimiter] 的移动端内存防御对齐）：
 * 1. **内联安全区 (< [maxFrameChars])**：内存缓冲，正常逐行转发；
 * 2. **落盘转存区 ([maxFrameChars] ~ [maxSpillChars])**：该行剩余部分流式写入 spill 文件，
 *    行结束时以合成 tool-result 帧交付（预览 + 文件路径）——不丢内容，在途请求不再超时；
 * 3. **熔断硬顶 (> [maxSpillChars])** 或落盘目录不可用：回退旧的整行跳过 + 单帧熔断错误。
 */
class LinuxMcpStdioChannel(
    private val serverId: String,
    private val session: LinuxSession,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val maxFrameChars: Int = McpStdioTransport.MAX_FRAME_CHARS,
    private val maxSpillChars: Long = McpStdioTransport.MAX_SPILL_FRAME_CHARS,
    /** 超长行落盘目录；null 时回退系统临时目录（与 [McpResponseSizeLimiter] 同规则）。 */
    private val spillDirectory: File? = null,
    private val bufferCapacity: Int = McpStdioTransport.MAX_BUFFERED_LINES,
    private val environment: top.tianyan.app.runtime.environment.ExecutionEnvironment? = null,
    private val secretValues: Collection<String> = emptyList(),
) : McpStdioChannel {
    internal var ownership: AutoCloseable? = null
    private val cleanup = McpResourceCleanup()
    private val sessionCleanup = McpResourceCleanup()
    private val environmentCleanup = McpResourceCleanup()
    private val lines: Channel<String> = Channel(capacity = bufferCapacity)
    override val incoming: ReceiveChannel<String> = lines
    override val isAlive: Boolean get() = session.isAlive
    private val pumpScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + SupervisorJob(scope.coroutineContext[Job]))

    private val pump = pumpScope.launch(ActivePump(this)) {
        val buf = StringBuilder()
        var skippingOversized = false
        // 同一段连续超长输出只广播一条熔断错误帧：首帧已能触发在途请求失败，
        // 若每条超长行都发帧，会被协议层连续计数放大为通道级中毒断连
        var reportedOversizedFrame = false
        // 非 null 表示当前正在流式落盘一条超长行（L2 阶段）
        var spill: SpillState? = null
        val diagnostics = StringBuilder()
        try {
            session.output.collect { output ->
                val chunk = output.text
                var start = 0
                while (start < chunk.length) {
                    val newline = chunk.indexOf('\n', start)
                    val end = if (newline >= 0) newline else chunk.length
                    if (skippingOversized) {
                        if (newline >= 0) {
                            skippingOversized = false
                            start = newline + 1
                        } else {
                            break
                        }
                        continue
                    }
                    val partLength = end - start
                    val active = spill
                    if (active != null) {
                        // L2 落盘中：持续写入直到行结束；超出硬顶则删除残余文件并熔断跳过
                        if (active.chars + partLength > maxSpillChars) {
                            val idJson = active.idJson
                            active.abort()
                            spill = null
                            buf.clear()
                            skippingOversized = true
                            if (!reportedOversizedFrame) {
                                reportedOversizedFrame = true
                                lines.send(
                                    errorFrame(idJson, "MCP STDIO 单行输出超过落盘硬顶 (${maxSpillChars / 1024 / 1024}MB)，已触发移动端熔断保护"),
                                )
                            }
                            if (newline >= 0) {
                                skippingOversized = false
                                start = newline + 1
                            } else {
                                break
                            }
                            continue
                        }
                        if (partLength > 0) active.append(chunk, start, end)
                        if (newline < 0) break
                        // 行结束：以合成 tool-result 帧交付转存结果
                        spill = null
                        reportedOversizedFrame = false
                        lines.send(active.finish())
                        start = newline + 1
                        continue
                    }
                    if (buf.length + partLength > maxFrameChars) {
                        // L1→L2：超过内联安全区，优先流式落盘；落盘不可用或超过硬顶回退熔断跳过
                        val state = SpillState.begin(buf, chunk, start, end, spillDirectory, secretValues)
                        if (state == null) {
                            skippingOversized = true
                            if (!reportedOversizedFrame) {
                                val idJson = ID_REGEX.find(buf)?.groups?.get(1)?.value ?: "null"
                                reportedOversizedFrame = true
                                lines.send(
                                    errorFrame(idJson, "MCP STDIO 单行输出超过安全上限 (${maxFrameChars / 1024 / 1024}MB)，已触发移动端熔断保护"),
                                )
                            }
                            buf.clear()
                            if (newline >= 0) {
                                skippingOversized = false
                                start = newline + 1
                            } else {
                                break
                            }
                            continue
                        }
                        if (state.chars + partLength > maxSpillChars) {
                            val idJson = state.idJson
                            state.abort()
                            spill = null
                            buf.clear()
                            skippingOversized = true
                            if (!reportedOversizedFrame) {
                                reportedOversizedFrame = true
                                lines.send(
                                    errorFrame(idJson, "MCP STDIO 单行输出超过落盘硬顶 (${maxSpillChars / 1024 / 1024}MB)，已触发移动端熔断保护"),
                                )
                            }
                            if (newline >= 0) {
                                skippingOversized = false
                                start = newline + 1
                            } else {
                                break
                            }
                            continue
                        }
                        spill = state
                        if (partLength > 0) state.append(chunk, start, end)
                        if (newline < 0) break
                        spill = null
                        reportedOversizedFrame = false
                        lines.send(state.finish())
                        start = newline + 1
                        continue
                    }
                    buf.append(chunk, start, end)
                    if (newline < 0) break
                    val line = buf.toString().trim()
                    buf.clear()
                    reportedOversizedFrame = false
                    if (line.startsWith("{")) lines.send(line)
                    else if (line.isNotBlank()) {
                        diagnostics.append(line).append('\n')
                        if (diagnostics.length > 2048) diagnostics.delete(0, diagnostics.length - 2048)
                    }
                    start = newline + 1
                }
            }
            val detail = SecretRedactor().redact(diagnostics.toString())
                .let { text -> secretValues.filter { it.isNotBlank() }.fold(text) { value, secret -> value.replace(secret, "[REDACTED]") } }
                .trim()
            lines.close(detail.takeIf { it.isNotBlank() }?.let { McpStdioChannelException("MCP 子进程输出已结束：$it") })
        } catch (cancellation: CancellationException) {
            lines.close(cancellation)
            throw cancellation
        } catch (t: Throwable) {
            lines.close(t)
            runCatching { sessionCleanup.close { session.close() } }
        }
    }

    override suspend fun writeLine(line: String) = session.write((line + "\n").toByteArray(Charsets.UTF_8))

    override suspend fun close() {
        check(currentCoroutineContext()[ActivePump]?.channel !== this) { "Cannot close MCP channel from its own pump" }
        cleanup.close {
            // 只关 session 不够：泵协程可能正挂起在 lines.send 上（消费方超时离场、缓冲已满），
            // 此时 output flow 的完成永远观察不到，协程与 Channel 永久泄漏。取消独立子作用域让泵
            // 走 catch 分支收尾（lines.close(cause)）。
            pumpScope.coroutineContext[Job]?.cancelAndJoin()
            val failures = mutableListOf<Throwable>()
            try { sessionCleanup.close { session.close() } } catch (t: Throwable) { failures += t }
            try { environmentCleanup.close { environment?.close() } } catch (t: Throwable) { failures += t }
            if (failures.isNotEmpty()) throw ResourceCleanupException(failures)
            ownership?.close()
        }
    }

    /**
     * L2 落盘转存状态：单行超过内联安全区后，把该行剩余部分流式写入 spill 文件，
     * 行结束时以合成 tool-result 帧交付（预览 + 文件路径），替代旧的整行丢弃。
     */
    private class SpillState private constructor(
        val idJson: String,
        private val file: File,
        private val out: FileOutputStream,
        private val preview: String,
        var chars: Long,
    ) {
        fun append(chunk: String, start: Int, end: Int) {
            out.write(chunk.substring(start, end).toByteArray(Charsets.UTF_8))
            chars += (end - start)
        }

        /** 行结束：关闭文件并返回合成 tool-result 帧；关闭失败则返回错误帧（请求不能悬空等到超时）。 */
        fun finish(): String {
            val closed = runCatching { out.close() }.isSuccess
            if (!closed) {
                runCatching { file.delete() }
                return errorFrame(idJson, "MCP STDIO 大响应落盘失败")
            }
            val text = buildString {
                append("[MCP STDIO 单行响应较大（${chars / 1024}KB），已转存至文件: ${file.absolutePath}]\n\n[内容预览]：\n")
                append(preview)
                append("\n\n【提示】：请根据上方内容预览分析结果，或用文件读取工具查看完整内容；也可在后续工具调用中缩小请求范围。")
            }
            val escaped = Json.encodeToString(text)
            return """{"jsonrpc":"2.0","id":$idJson,"result":{"content":[{"type":"text","text":$escaped}],"isError":false}}"""
        }

        fun abort() {
            runCatching { out.close() }
            runCatching { file.delete() }
        }

        companion object {
            /** id 通常位于 JSON-RPC 帧头部：buf 之外最多向后扫 64KB 即可覆盖。 */
            private const val ID_PROBE_CHARS = 64 * 1024

            /**
             * 失败时返回 null（buf 保持原样，调用方回退熔断路径）；成功时 buf 内容已写入文件并清空。
             * id 与预览同时扫 buf 与当前 chunk 的起始段——超长行可能在单个 chunk 内完整到达，buf 为空。
             * 预览做凭证脱敏，与诊断输出同规则。
             */
            fun begin(
                buf: StringBuilder,
                chunk: String,
                start: Int,
                end: Int,
                spillDirectory: File?,
                secretValues: Collection<String>,
            ): SpillState? {
                val spillFile = McpResponseSizeLimiter.acquireSpillFile(spillDirectory) ?: return null
                val out = try {
                    FileOutputStream(spillFile)
                } catch (t: Throwable) {
                    runCatching { spillFile.delete() }
                    return null
                }
                return try {
                    val head = StringBuilder(buf).append(chunk, start, minOf(end, start + ID_PROBE_CHARS))
                    val idJson = ID_REGEX.find(head)?.groups?.get(1)?.value ?: "null"
                    val previewRaw = if (buf.length >= McpResponseSizeLimiter.PREVIEW_MAX_CHARS) {
                        buf.take(McpResponseSizeLimiter.PREVIEW_MAX_CHARS).toString()
                    } else {
                        buf.toString() + chunk.substring(
                            start,
                            minOf(end, start + McpResponseSizeLimiter.PREVIEW_MAX_CHARS - buf.length),
                        )
                    }
                    val preview = SecretRedactor().redact(previewRaw)
                        .let { text -> secretValues.filter { it.isNotBlank() }.fold(text) { value, secret -> value.replace(secret, "[REDACTED]") } }
                    out.write(buf.toString().toByteArray(Charsets.UTF_8))
                    val state = SpillState(idJson, spillFile, out, preview, buf.length.toLong())
                    buf.clear()
                    state
                } catch (t: Throwable) {
                    runCatching { out.close() }
                    runCatching { spillFile.delete() }
                    null
                }
            }
        }
    }

    private class ActivePump(val channel: LinuxMcpStdioChannel) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActivePump>
    }

    private companion object {
        private val ID_REGEX = Regex(""""id"\s*:\s*("([^"]+)"|(\d+))""")

        /** 熔断/落盘失败的合成 JSON-RPC error 帧：与正常响应同构，协议层按 id 路由给在途请求。 */
        private fun errorFrame(idJson: String, message: String): String {
            val escaped = Json.encodeToString(message)
            return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":-32603,"message":$escaped}}"""
        }
    }
}
