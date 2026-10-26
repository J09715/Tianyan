package top.tianyan.app.runtime.gui

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 主屏和所有虚拟屏共用系统剪贴板，写入至消费完成之间不能交叉。 */
internal object GuiClipboardLock {
    private val mutex = Mutex()
    suspend fun <T> use(block: suspend () -> T): T = mutex.withLock {
        try {
            block()
        } finally {
            // 取消也要留出消费时间，不能让下一会话立即覆盖已发送 PASTE 的文本。
            withContext(NonCancellable) { delay(180) }
        }
    }
}
