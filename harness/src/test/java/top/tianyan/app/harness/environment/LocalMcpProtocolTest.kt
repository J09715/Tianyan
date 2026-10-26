package top.tianyan.app.harness.environment

import java.lang.reflect.Proxy
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.*
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.*

class LocalMcpProtocolTest {
    @Test fun `all bundled local STDIO servers initialize and discover over pipes`() = runBlocking {
        val json = Json { ignoreUnknownKeys = true }
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, _, _ ->
            error("unbound runtime fallback")
        } as LinuxRuntime
        // 只对仍随 APK 内置的 Python 脚本型服务做进程级校验。
        // 已改为上游 npm 包的能力（WebSearch / CodeGraph）必须先安装组件才可用，
        // 其"固定版本、禁止浮动"由 core:model 的 McpCatalogTest 覆盖，不在这里拉起进程。
        for (server in BuiltinMcpPresets.presets.filter {
            it.transportType == McpTransportType.STDIO && it.command == "python3"
        }) {
            val fake = FakeWorld(ExecutionEnvironmentId("local-proot", "ubuntu", "/workspace", "test"))
            val outputs = Channel<TerminalOutput>(Channel.UNLIMITED)
            val process = object : LinuxSession {
                override var isAlive = true
                override val output = outputs.receiveAsFlow()
                override suspend fun write(data: ByteArray) {
                    val request = json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
                    val id = request["id"] ?: return
                    val result = if (request["method"]?.jsonPrimitive?.content == "initialize")
                        """{"protocolVersion":"2025-06-18","capabilities":{}}""" else """{"tools":[]}"""
                    outputs.send(TerminalOutput(TerminalStream.STDOUT, """{"jsonrpc":"2.0","id":$id,"result":$result}""" + "\n"))
                }
                override suspend fun resize(columns: Int, rows: Int) = Unit
                override suspend fun interrupt() = Unit
                override suspend fun close() { isAlive = false; outputs.close() }
            }
            val world = object : ExecutionEnvironment by fake {
                override suspend fun openSession(config: SessionConfig): LinuxSession {
                    assertFalse(config.usePty)
                    assertFalse(config.allowSttyResize)
                    assertFalse(config.showBanner)
                    assertFalse(config.commandLine.contains("stty"))
                    assertTrue(config.commandLine.contains("'python3'"))
                    assertTrue(config.commandLine.contains(server.args[1]))
                    return process
                }
            }
            val transport = McpStdioTransport(json, McpCommandBuilder(), LinuxMcpStdioChannelFactory(runtime,
                McpCommandBuilder(), ExecutionEnvironmentFactory { _, _, _ -> world }))
            try {
                assertTrue(transport.check(server).online)
                assertEquals(emptyList<McpToolInfo>(), transport.discover(server))
                assertTrue(process.isAlive)
            } finally { transport.closeConnection(server.id) }
            assertFalse(process.isAlive)
        }
    }

    /**
     * 按需安装的内置能力：必须声明安装依赖、由装配脚本按落位标记拉起、且在安装前保持关闭。
     *
     * 这里刻意**不**要求启动参数里出现版本号——版本必须来自落位标记，写进参数就等于把
     * 「升级」变成假动作（只改了全局安装，进程仍按代码里的旧 pin 跑）。出厂 pin 与校验和
     * 由 catalog 条目负责，所以断言落在 `McpCatalog` 上。
     */
    @Test fun `installable presets launch through the catalog script and stay gated`() {
        val gated = BuiltinMcpPresets.presets.filter { it.requiresInstall }
        assertTrue("应存在按需安装的内置能力", gated.isNotEmpty())
        gated.forEach { server ->
            val entry = McpCatalog.find(server.id)
            assertNotNull("${server.id} 声明了需要安装，但 catalog 没有对应条目", entry)
            requireNotNull(entry)
            assertTrue("${server.id} 必须绑定安装组件", server.installComponentId.isNotBlank())
            assertFalse("${server.id} 在安装前不得默认启用", server.isEnabled)
            assertEquals("${server.id} 应由装配脚本拉起", "/bin/sh", server.command)
            assertEquals(
                "${server.id} 的启动参数必须按落位标记解析版本",
                listOf(McpCatalog.SCRIPT_PATH, "run", server.id, entry.packageName),
                server.args.take(4),
            )
            assertFalse(
                "${server.id} 的启动参数不得携带版本号：${server.args}",
                Regex("@\\d+\\.\\d+\\.\\d+").containsMatchIn(server.args.joinToString(" ")),
            )
            assertTrue(
                "${server.id} 未钉住三段式出厂版本：${entry.pinnedVersion}",
                Regex("^\\d+\\.\\d+\\.\\d+").containsMatchIn(entry.pinnedVersion),
            )
            assertTrue("${server.id} 缺少 sha512 校验和", entry.integrity.startsWith("sha512-"))
        }
    }
}
