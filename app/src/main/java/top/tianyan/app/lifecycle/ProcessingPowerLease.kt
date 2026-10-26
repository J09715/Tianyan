package top.tianyan.app.lifecycle

/**
 * 算力租约句柄。
 *
 * 调用 [close] 释放该租约；当进程内所有租约全部释放后，
 * [RuntimeLifecycleSupervisor] 才会释放底层 WakeLock / WifiLock。
 *
 * 推荐使用 `use { }` 块确保异常路径也能释放：
 * ```kotlin
 * supervisor.acquireLease("agent").use { lease ->
 *     // 执行需要保活的工作
 * }
 * ```
 */
interface ProcessingPowerLease : AutoCloseable {
    /** 申请方标识，用于日志和调试（如 "agent" / "workflow" / "runtime-foreground-service"）。 */
    val holderId: String

    /** 释放租约；幂等，多次调用安全。 */
    override fun close()
}
