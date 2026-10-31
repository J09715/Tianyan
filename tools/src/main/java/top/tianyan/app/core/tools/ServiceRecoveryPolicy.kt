package top.tianyan.app.core.tools

import kotlinx.coroutines.CancellationException

/** Per installed service generation; transient failures back off without a restart storm. */
internal class ServiceRecoveryPolicy(private val clock: () -> Long = System::nanoTime) {
    private var retryAt = 0L
    private var failures = 0
    private var healthyChecks = 0
    private var unhealthyChecks = 0

    suspend fun check(healthy: suspend () -> Boolean, recover: suspend () -> Unit): Boolean {
        if (healthy()) {
            unhealthyChecks = 0
            if (++healthyChecks >= 6) { failures = 0; retryAt = 0 }
            return false
        }
        healthyChecks = 0
        if (++unhealthyChecks < 3 || clock() < retryAt) return false
        try {
            recover()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The controller retains the actual process output in the tool's bounded log.
        }
        failures = (failures + 1).coerceAtMost(7)
        retryAt = clock() + (5L shl (failures - 1)).coerceAtMost(300L) * 1_000_000_000L
        unhealthyChecks = 0
        return true
    }
}
