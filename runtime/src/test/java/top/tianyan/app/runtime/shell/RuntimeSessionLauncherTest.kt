package top.tianyan.app.runtime.shell

import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.runtime.pty.PtyManager

class RuntimeSessionLauncherTest {
    private val session = object : LinuxSession {
        override val isAlive = true
        override val output = emptyFlow<TerminalOutput>()
        override suspend fun write(data: ByteArray) = Unit
        override suspend fun resize(columns: Int, rows: Int) = Unit
        override suspend fun interrupt() = Unit
        override suspend fun close() = Unit
    }

    @Test fun `protocol launch uses pipes even when native PTY cannot be queried`() = runBlocking {
        val pty = Proxy.newProxyInstance(PtyManager::class.java.classLoader, arrayOf(PtyManager::class.java)) { _, _, _ ->
            error("protocol must not access terminal backends")
        } as PtyManager
        var cleaned = false
        val result = launchRuntimeSession(pty, SessionConfig(usePty = false), listOf("proot", "exec python3"),
            listOf("script"), mapOf("TMPDIR" to "/app/tmp"), { _, _ -> error("resize") }, { cleaned = true },
        ) { command, environment, release ->
            assertEquals(listOf("proot", "exec python3"), command)
            assertEquals(mapOf("TMPDIR" to "/app/tmp"), environment)
            object : LinuxSession by session { override suspend fun close() { release() } }
        }
        assertTrue(result.isAlive)
        result.close()
        assertTrue(cleaned)
    }

    @Test fun `terminal launch retains native and script fallback behavior`() = runBlocking {
        for (native in listOf(true, false)) {
            var launched = ""
            val pty = Proxy.newProxyInstance(PtyManager::class.java.classLoader, arrayOf(PtyManager::class.java)) { _, method, args ->
                when (method.name) {
                    "getNativeAvailable" -> native
                    "open", "openNative" -> { launched = method.name; assertEquals(listOf(if (native) "native" else "script"), args[0]); session }
                    else -> error(method.name)
                }
            } as PtyManager
            assertSame(session, launchRuntimeSession(pty, SessionConfig(), listOf("native"), listOf("script"), emptyMap(),
                { _, _ -> }, {}, { _, _, _ -> error("terminal used pipes") }))
            assertEquals(if (native) "openNative" else "open", launched)
        }
    }
}
