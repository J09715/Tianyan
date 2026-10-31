package top.tianyan.app.lifecycle

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程级算力保活协调者（P0 借鉴自 PalmClaw GatewayRuntimeSupervisor）。
 *
 * ## 设计原则
 * 全进程持有**唯一一个** WakeLock 与 WifiLock。任何需要后台保活的模块
 * （Agent 推理、工作流执行、Runtime PRoot 服务）调用 [acquireLease] 申请租约；
 * 当所有租约全部释放后再统一释放锁，彻底消除以下竞态：
 *
 * - AgentFGS 先停 → Agent WakeLock 提前释放 → CPU 被冻结 → 沙箱构建中断
 * - RuntimeFGS 先停 → WifiLock 消失 → Agent 继续跑但 Wi-Fi 断连 → 网络请求超时
 *
 * ## 使用方式
 * ```kotlin
 * val lease = supervisor.acquireLease("agent")
 * // ... 执行需要保活的工作
 * lease.close()   // 或使用 use { } 块
 * ```
 *
 * ## 线程安全
 * 内部 `holders` 集合由 `synchronized(this)` 保护，可安全地从任意线程调用。
 */
class RuntimeLifecycleSupervisor(
    private val context: Context,
) {

    /** 当前是否有任意活跃租约（即锁是否 held）。 */
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    /** 活跃租约的 holderId 集合，所有访问必须持有对象锁。 */
    private val holders = mutableSetOf<String>()

    /** 进程唯一 CPU 唤醒锁，防止息屏后 CPU 休眠冻结推理 / PRoot 进程。 */
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewAt = 0L

    /**
     * 进程唯一 Wi-Fi 锁，防止息屏后无线电源进入省电模式导致沙箱网络断连。
     *
     * `WIFI_MODE_FULL` 已在 API 29 废弃（WIFI_MODE_FULL_HIGH_PERF 取代），
     * 但对于后台数据同步场景在各厂商 ROM 上兼容性最佳；此处保留 @Suppress 标记。
     */
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 申请保活租约。
     *
     * - 首个租约申请时自动获取 WakeLock / WifiLock。
     * - 同一 [holderId] 可重复申请（幂等）；释放时每个句柄独立计数，不因重复申请而双倍计算。
     *
     * @param holderId 申请方标识，用于日志（如 "agent" / "workflow" / "runtime-foreground-service"）。
     * @return 租约句柄；调用 [ProcessingPowerLease.close] 释放。
     */
    fun acquireLease(holderId: String): ProcessingPowerLease {
        val totalHolders: Int
        synchronized(this) {
            val wasEmpty = holders.isEmpty()
            holders.add(holderId)
            if (wasEmpty) {
                acquireLocks()
            }
            totalHolders = holders.size
        }
        Log.d(TAG, "Lease acquired by '$holderId' (total holders: $totalHolders)")
        return object : ProcessingPowerLease {
            override val holderId: String = holderId
            private var released = false

            override fun close() {
                val remaining: Int
                synchronized(this@RuntimeLifecycleSupervisor) {
                    if (released) return
                    released = true
                    holders.remove(holderId)
                    if (holders.isEmpty()) {
                        releaseLocks()
                    }
                    remaining = holders.size
                }
                Log.d(TAG, "Lease released by '$holderId' (remaining: $remaining)")
            }
        }
    }

    /** 当前活跃租约数（线程安全快照）。 */
    fun holderCount(): Int = synchronized(this) { holders.size }

    /** 当前活跃租约 holderId 集合的快照（仅用于诊断/日志）。 */
    fun holderSnapshot(): Set<String> = synchronized(this) { holders.toSet() }

    /** A live foreground owner renews the bounded lock; abandoned owners still time out. */
    fun refreshActiveLeases() = synchronized(this) {
        if (holders.isNotEmpty()) acquireLocks()
    }

    // ──────────────────────────────────────────────────────────────
    // 内部：锁管理
    // ──────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        acquireWakeLock()
        acquireWifiLock()
        _isActive.value = true
    }

    private fun releaseLocks() {
        releaseWakeLock()
        releaseWifiLock()
        _isActive.value = false
    }

    private fun acquireWakeLock() {
        val now = SystemClock.elapsedRealtime()
        if (wakeLock?.isHeld == true && now < wakeLockRenewAt) return
        runCatching {
            val lock = wakeLock ?: context.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply { setReferenceCounted(false) }
            lock.acquire(LOCK_TIMEOUT_MS)
            wakeLock = lock
            wakeLockRenewAt = now + LOCK_TIMEOUT_MS / 2
            Log.i(TAG, "WakeLock acquired (holders: ${holderSnapshot()})")
        }.onFailure { Log.w(TAG, "Failed to acquire WakeLock", it) }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "WakeLock released")
        }.onFailure { Log.w(TAG, "Failed to release WakeLock", it) }
        wakeLock = null
        wakeLockRenewAt = 0L
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = context.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL, WIFI_LOCK_TAG)
                .also { it.acquire() }
            Log.i(TAG, "WifiLock acquired")
        }.onFailure { Log.w(TAG, "Failed to acquire WifiLock", it) }
    }

    private fun releaseWifiLock() {
        runCatching {
            wifiLock?.takeIf { it.isHeld }?.release()
            Log.i(TAG, "WifiLock released")
        }.onFailure { Log.w(TAG, "Failed to release WifiLock", it) }
        wifiLock = null
    }

    companion object {
        private const val TAG = "RuntimeLifecycleSupervisor"
        private const val WAKE_LOCK_TAG = "tianyan:lifecycle-supervisor"
        private const val WIFI_LOCK_TAG = "tianyan:lifecycle-supervisor-wifi"

        /**
         * 兜底超时：8 小时。即使出现 lease 泄漏，系统也会在此之后强制释放，
         * 避免意外永久持锁。正常流程下所有租约释放时会主动 release。
         */
        private const val LOCK_TIMEOUT_MS = 8L * 60 * 60 * 1000
    }
}
