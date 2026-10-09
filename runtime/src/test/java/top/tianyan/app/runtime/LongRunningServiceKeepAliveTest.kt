package top.tianyan.app.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长驻服务的保活守卫。
 *
 * 这类问题只有真机息屏后才暴露，单元测试跑不出来：
 * llama-server 是 PRoot 子进程，息屏后系统会冻结整个进程组，
 * 表现就是「刚提示启动成功，回到列表已变成服务未启动」。
 * 用户实测本地模型服务启动后立刻掉线，根因就是 LocalLlmManager 没有任何保活机制，
 * 而 SSH / FTP 两个同类服务都持有 PARTIAL_WAKE_LOCK。
 *
 * 用源码断言拦住：凡是「常驻后台、需要持续运行」的服务管理器都必须申请唤醒锁。
 */
class LongRunningServiceKeepAliveTest {

    private val runtimeSrc = File("src/main/java/top/tianyan/app/runtime")

    /** 必须保活的服务管理器及其文件。 */
    private val keepAliveRequired = listOf(
        "SshServiceManager.kt",
        "FtpServiceManager.kt",
        "LocalLlmManager.kt",
    )

    @Test
    fun everyLongRunningServiceHoldsAWakeLock() {
        keepAliveRequired.forEach { name ->
            val file = File(runtimeSrc, name)
            assertTrue("缺少文件: ${file.path}", file.isFile)
            val text = file.readText()
            assertTrue(
                "$name 没有申请 PARTIAL_WAKE_LOCK：息屏后进程会被系统冻结，" +
                    "表现为「启动成功但立刻变回未启动」",
                text.contains("PARTIAL_WAKE_LOCK"),
            )
            assertTrue(
                "$name 申请了唤醒锁但没有释放路径：会永久占住 CPU，耗电且被系统记录",
                text.contains("release()") || text.contains("releaseWakeLock"),
            )
            // 关键：必须有实际的 acquire 调用点。只检查字符串常量存在是不够的——
            // 实测把 acquireWakeLock() 的调用删掉后，仅看常量存在的断言依然通过。
            assertTrue(
                "$name 声明了唤醒锁但从未调用 acquire：息屏后进程照样被冻结",
                text.contains("acquireWakeLock()") || Regex("""\.acquire\(""").containsMatchIn(text),
            )
        }
    }

    /** 启动成功后必须真的获取唤醒锁——声明了却不调用等于没有保活。 */
    @Test
    fun localLlmActuallyAcquiresWakeLockOnStart() {
        val text = File(runtimeSrc, "LocalLlmManager.kt").readText()
        assertTrue(
            "LocalLlmManager 未在启动流程中调用 acquireWakeLock()：模型服务仍会在息屏后掉线",
            Regex("""_serviceState\.value = LocalLlmServiceState\.Running[\s\S]{0,300}?acquireWakeLock\(\)|acquireWakeLock\(\)[\s\S]{0,300}?_serviceState\.value = LocalLlmServiceState\.Running""")
                .containsMatchIn(text),
        )
    }

    /** 本地推理服务的唤醒锁必须在停止与进程死亡两条路径上都释放。 */
    @Test
    fun localLlmReleasesWakeLockOnEveryExitPath() {
        val text = File(runtimeSrc, "LocalLlmManager.kt").readText()
        // stopInternal（用户停止 / 换上下文重启）
        assertTrue(
            "stopInternal 未释放唤醒锁：用户停止服务后 CPU 仍被占用",
            Regex("stopInternal\\(\\)[\\s\\S]{0,400}?releaseWakeLock\\(\\)").containsMatchIn(text),
        )
        // monitorService（进程自行退出）
        assertTrue(
            "monitorService 未释放唤醒锁：进程崩溃后唤醒锁泄漏",
            Regex("monitorService[\\s\\S]{0,600}?releaseWakeLock\\(\\)").containsMatchIn(text),
        )
    }

    /** 唤醒锁要有超时，不能无限持有。 */
    @Test
    fun wakeLockHasABoundedTimeout() {
        val text = File(runtimeSrc, "LocalLlmManager.kt").readText()
        assertTrue(
            "LocalLlmManager 的唤醒锁缺少超时：acquire 无参会一直持有直到进程结束",
            text.contains("WAKE_LOCK_TIMEOUT_MS") && text.contains("acquire(WAKE_LOCK_TIMEOUT_MS)"),
        )
    }
}
