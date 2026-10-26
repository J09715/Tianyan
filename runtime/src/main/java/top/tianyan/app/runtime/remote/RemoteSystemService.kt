package top.tianyan.app.runtime.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.ShellCommand
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

data class RemoteSystemMetrics(val distribution: String, val architecture: String, val kernel: String,
    val memoryTotalMb: Long, val memoryUsedMb: Long, val diskTotalGb: Double, val diskUsedGb: Double,
    val load: String, val uptimeSeconds: Long, val processCount: Long)

data class RemoteSystemInspection(val loading: Boolean = true, val metrics: RemoteSystemMetrics? = null, val error: String? = null)

/** Explicit target IDs only: dashboard/workshop operations never consult or fall back to phone files. */
class RemoteSystemService(private val repository: RemoteServerRepository, private val connector: SshConnector) {
    private val gates = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val pending = java.util.concurrent.ConcurrentHashMap<String, MutableSet<ExecutionEnvironment>>()
    fun server(id: String): RemoteServer = repository.servers.value.singleOrNull { it.id == id }
        ?: error("服务器配置已移除，请重新选择主系统")

    private suspend fun <T> access(serverId: String, expected: RemoteServer? = null, operation: suspend (ExecutionEnvironment) -> T): T =
        gates.computeIfAbsent(serverId) { Mutex() }.withLock {
        val owned = pending.computeIfAbsent(serverId) { linkedSetOf() }
        // Failed disposal stays owned; retry it before allocating another connection.
        for (previous in owned.toList()) { previous.close(); owned.remove(previous) }
        val (target, credentials) = repository.connection(serverId)
        check(expected == null || target == expected) { "连接档案已更改，请刷新后重试" }
        val world = connector.openEnvironment(target, credentials,
            ExecutionEnvironmentId("ssh", target.distributionId, target.workspace, "system-ui:${UUID.randomUUID()}"))
        owned.add(world)
        var failure: Throwable? = null
        try { operation(world) }
        catch (t: Throwable) { failure = t; throw t }
        finally { try { world.close(); owned.remove(world) } catch (cleanup: Throwable) { failure?.addSuppressed(cleanup) ?: throw cleanup } }
    }

    fun observe(serverId: String, expected: RemoteServer? = null) = flow {
        while (true) {
            emit(RemoteSystemInspection())
            try { emit(RemoteSystemInspection(false, inspect(serverId, expected))) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { emit(RemoteSystemInspection(false, error = "无法读取服务器指标，请检查 SSH 连接及主机指纹")) }
            delay(15_000)
        }
    }
    suspend fun inspect(serverId: String, expected: RemoteServer? = null): RemoteSystemMetrics = access(serverId, expected) { world ->
        val result = world.execute(ShellCommand(metricsCommand(world.id.workspace), world.id.workspace, timeoutMs = 15_000))
        check(result.isSuccess) { "远程指标采集失败" }
        parseMetrics(result.stdout)
    }
    suspend fun list(serverId: String, relative: String = "", expected: RemoteServer? = null): List<ExecutionFileEntry> = access(serverId, expected) { world ->
        value(world.files.listEntries(logicalPath(relative)))
    }
    suspend fun projects(serverId: String, expected: RemoteServer? = null) = list(serverId, expected = expected).filter { it.directory && !it.name.startsWith('.') }
    suspend fun execute(serverId: String, command: String, expected: RemoteServer? = null): String = access(serverId, expected) { world ->
        require(command.isNotBlank() && command.length <= 32768) { "命令为空或过长" }
        val result = world.execute(ShellCommand(command, world.id.workspace, timeoutMs = 60_000))
        var output = "exit ${result.exitCode}\n${result.stdout}\n${result.stderr}"
        world.outputSecrets().filter { it.isNotEmpty() }.sortedByDescending { it.length }.forEach { output = output.replace(it, "[REDACTED]") }
        output.take(32768)
    }
    suspend fun createProject(serverId: String, name: String, expected: RemoteServer? = null) = access(serverId, expected) { world ->
        require(name.matches(Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}._-]{0,99}")) && name != "." && name != "..") { "项目名称无效" }
        val result = world.execute(ShellCommand("mkdir -- ${shellQuote(name)}", world.id.workspace, timeoutMs = 15_000))
        check(result.isSuccess) { "项目创建失败，请检查名称是否重复及目录权限" }
    }
    suspend fun read(serverId: String, relative: String, expected: RemoteServer? = null): String = access(serverId, expected) { world ->
        val bytes = value(world.files.readRawBytes(logicalPath(relative)))
        require(bytes.size <= 1024 * 1024) { "编辑器仅支持 1 MiB 以内的文本文件" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text) { "二进制文件不能在文本编辑器打开" }
        text
    }
    suspend fun save(serverId: String, relative: String, original: String, content: String, expected: RemoteServer? = null) = access(serverId, expected) { world ->
        require(content.toByteArray().size <= 1024 * 1024) { "文件内容超过编辑器上限" }
        val path = logicalPath(relative)
        val current = value(world.files.readRawBytes(path))
        check(current.contentEquals(original.toByteArray(Charsets.UTF_8))) { "文件已被外部修改，请重新打开后编辑" }
        if (original.isNotEmpty()) value(world.files.editDetailed(path, original, content))
        else value(world.files.write(path, content))
    }

    private fun <T> value(result: AppResult<T>): T = when (result) {
        is AppResult.Success -> result.data
        is AppResult.Failure -> error(result.error.message)
    }
    internal fun logicalPath(relative: String): String {
        require(!relative.startsWith('/') && '\u0000' !in relative && relative.split('/').none { it == ".." }) { "非法工作区路径" }
        return "/workspace" + relative.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
    }
}

internal fun metricsCommand(workspace: String): String = """
    set -e
    printf 'architecture\t'; uname -m
    printf 'kernel\t'; uname -r
    printf 'distribution\t'; if [ -r /etc/os-release ]; then sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"'; else uname -s; fi
    awk '/^MemTotal:/{t=${'$'}2}/^MemAvailable:/{a=${'$'}2} END{printf "memoryTotal\t%.0f\nmemoryUsed\t%.0f\n",t/1024,(t-a)/1024}' /proc/meminfo
    awk '{printf "uptime\t%.0f\n",${'$'}1}' /proc/uptime
    awk '{print "load\t" ${'$'}1 " " ${'$'}2 " " ${'$'}3}' /proc/loadavg
    df -Pk -- ${shellQuote(workspace)} | awk 'NR==2{printf "diskTotal\t%.0f\ndiskUsed\t%.0f\n",${'$'}2*1024,${'$'}3*1024}'
    find /proc -maxdepth 1 -type d -name '[0-9]*' 2>/dev/null | awk 'END{print "processes\t" NR}'
""".trimIndent()

internal fun parseMetrics(output: String): RemoteSystemMetrics {
    val values = output.lineSequence().filter { '\t' in it }.associate { it.substringBefore('\t') to it.substringAfter('\t').take(512) }
    fun number(key: String) = values[key]?.toDoubleOrNull() ?: error("缺少远程指标：$key")
    require(number("memoryTotal") > 0 && number("diskTotal") > 0) { "远程资源指标无效" }
    return RemoteSystemMetrics(values["distribution"].orEmpty(), values["architecture"].orEmpty(), values["kernel"].orEmpty(),
        number("memoryTotal").toLong(), number("memoryUsed").toLong(), number("diskTotal") / 1073741824,
        number("diskUsed") / 1073741824, values["load"].orEmpty(), number("uptime").toLong(), number("processes").toLong())
}
