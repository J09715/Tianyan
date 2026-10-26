package top.tianyan.app.core.common.memory

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 内存水位哨兵：低频采样 Java 堆水位，越过高水位线时执行"可再生数据"释放钩子并打点留痕
 * ——目标是把慢性内存增长拦截在 OOM 闪退之前，而不是在崩溃报告里猜根因。
 *
 * 设计要点：
 * - 纯 Kotlin 无 Android 依赖；释放什么由装配层注册（视频等待缓冲、图片内存缓存等），
 *   钩子必须是"丢了能自动重建"的可再生数据，不得承载有序状态；
 * - 迟滞 + 冷却：≥[WatermarkPolicy.fireRatio] 触发并 disarm，回落到 rearmRatio 以下
 *   才重新武装，且两次触发间隔不短于 cooldownMs，避免 GC 抖动导致打点刷屏；
 * - 触发前后各读一次水位，释放效果写进日志可审计（log 回调由装配层接到 AppLogger 等）。
 */
class MemoryWatchdog(
    private val log: (String) -> Unit = {},
) {

    class Releaser(val name: String, val action: () -> Unit)

    private val releasers = CopyOnWriteArrayList<Releaser>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var job: Job? = null

    /** 注册释放钩子；同名重复注册以最后一次为准（幂等，装配层可安全重复调用）。 */
    fun registerReleaser(name: String, action: () -> Unit) {
        releasers.removeAll { it.name == name }
        releasers.add(Releaser(name, action))
    }

    fun unregisterReleaser(name: String) {
        releasers.removeAll { it.name == name }
    }

    fun start(
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        fireRatio: Double = DEFAULT_FIRE_RATIO,
        rearmRatio: Double = DEFAULT_REARM_RATIO,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ) {
        if (job?.isActive == true) return
        val policy = WatermarkPolicy(
            fireRatio = fireRatio,
            rearmRatio = rearmRatio,
            cooldownMs = cooldownMs,
        )
        job = scope.launch {
            var armed = true
            var lastFiredAt = 0L
            while (isActive) {
                delay(intervalMs)
                val runtime = Runtime.getRuntime()
                val maxBytes = runtime.maxMemory()
                if (maxBytes <= 0) continue
                val usedBytes = runtime.totalMemory() - runtime.freeMemory()
                val ratio = usedBytes.toDouble() / maxBytes
                val now = System.currentTimeMillis()
                when (policy.evaluate(armed = armed, ratio = ratio, lastFiredAt = lastFiredAt, now = now)) {
                    WatermarkPolicy.Decision.FIRE -> {
                        armed = false
                        lastFiredAt = now
                        fire(maxBytes = maxBytes, usedBytes = usedBytes, ratio = ratio)
                    }

                    WatermarkPolicy.Decision.REARM -> armed = true
                    WatermarkPolicy.Decision.NONE -> Unit
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun fire(maxBytes: Long, usedBytes: Long, ratio: Double) {
        val names = releasers.joinToString { it.name }
        log(
            "内存水位哨兵：堆 ${percent(ratio)}（${mb(usedBytes)}/${mb(maxBytes)}MB）越过高水位，" +
                "执行释放钩子 [${names.ifBlank { "无" }}]",
        )
        releasers.forEach { releaser ->
            runCatching { releaser.action() }
                .onFailure { log("内存水位哨兵：释放钩子 ${releaser.name} 失败：${it.message.orEmpty()}") }
        }
        val runtime = Runtime.getRuntime()
        val afterBytes = runtime.totalMemory() - runtime.freeMemory()
        log("内存水位哨兵：释放后堆 ${percent(afterBytes.toDouble() / maxBytes)}（${mb(afterBytes)}MB）")
    }

    /**
     * 迟滞决策（纯函数，便于单测覆盖状态迁移）：
     * armed 且越过 fireRatio（且已过冷却）→ FIRE；disarm 后回落到 rearmRatio 以下 → REARM；其余 NONE。
     */
    internal data class WatermarkPolicy(
        val fireRatio: Double,
        val rearmRatio: Double,
        val cooldownMs: Long,
    ) {
        enum class Decision { FIRE, REARM, NONE }

        fun evaluate(armed: Boolean, ratio: Double, lastFiredAt: Long, now: Long): Decision = when {
            armed && ratio >= fireRatio && now - lastFiredAt >= cooldownMs -> Decision.FIRE
            !armed && ratio < rearmRatio -> Decision.REARM
            else -> Decision.NONE
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val DEFAULT_FIRE_RATIO = 0.85
        const val DEFAULT_REARM_RATIO = 0.70
        const val DEFAULT_COOLDOWN_MS = 60_000L

        private fun percent(ratio: Double): String = "${(ratio * 100).toInt()}%"

        private fun mb(bytes: Long): String = (bytes shr 20).toString()
    }
}
