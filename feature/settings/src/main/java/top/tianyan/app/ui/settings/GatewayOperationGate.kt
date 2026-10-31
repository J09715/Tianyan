package top.tianyan.app.ui.settings

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 网关操作闸门。
 *
 * 工具详情页的启停、生成 Token、应用模型都会改动同一个后台服务，且配置类改动只能靠「冷重启」生效
 * （环境变量在进程启动时注入）。如果这些入口各自并发触发，很容易叠成 `stop → start → stop → start`：
 * 后一次 stop 会落在前一次刚拉起的实例上，用户看到的是「启动后立刻又停止」。
 *
 * 因此所有网关操作都经过这里：
 * - [run] 串行执行，后来者一定在前一次操作完成后的状态上执行；
 * - [operating] 用「在飞计数」而不是布尔量驱动，多个入口叠加时直到最后一个结束才复位，
 *   网关卡片的按钮在整段时间内都被进度圈替换，不会出现「重启期间还能再点一次」。
 */
internal class GatewayOperationGate {
    private val mutex = Mutex()
    private val inFlight = AtomicInteger(0)
    private val _operating = MutableStateFlow(false)

    val operating: StateFlow<Boolean> = _operating.asStateFlow()

    suspend fun <T> run(block: suspend () -> T): T {
        inFlight.incrementAndGet()
        _operating.value = true
        try {
            return mutex.withLock { block() }
        } finally {
            if (inFlight.decrementAndGet() == 0) _operating.value = false
        }
    }
}
