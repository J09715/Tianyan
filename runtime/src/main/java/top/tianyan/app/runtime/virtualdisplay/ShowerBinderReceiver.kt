package top.tianyan.app.runtime.virtualdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.ShowerBinderContainer
import com.ai.assistance.showerclient.ShowerBinderRegistry
import com.ai.assistance.showerclient.ShowerLog

/**
 * 接收 shower-server（app_process 独立进程）通过 IActivityManager 广播交出的
 * IShowerService Binder，并注册到 [ShowerBinderRegistry] 供 ShowerController 使用。
 *
 * 注意：
 * - 必须在 Manifest 中声明 exported=true：广播发送方运行在 shell uid 的独立进程；
 * - [ACTION_SHOWER_BINDER_READY] 字符串硬编码在预编译的 shower-server.jar 内，
 *   与广播协议绑定，禁止改为 tianyan 包名。
 */
class ShowerBinderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SHOWER_BINDER_READY) return
        // exported=true 的接收器可能收到任意应用伪造/畸形的广播：反序列化失败会抛
        // BadParcelableException（例如 release 构建下 Parcelable 类名被 R8 重命名，
        // 表现为 ClassNotFoundException 解不出 com.ai.assistance.shower.ShowerBinderContainer）。
        // 未捕获时会让宿主进程直接崩溃，等同于被一条伪造广播 DoS，故整体兜底。
        try {
            handleBinderHandoff(intent)
        } catch (t: Throwable) {
            ShowerLog.w(TAG, "处理 Binder 交接广播失败，已忽略", t)
        }
    }

    private fun handleBinderHandoff(intent: Intent) {
        // 来源校验：shower-server 经 IActivityManager 发广播，原始调用者是 shell(2000)
        // 或 root(0)。exported=true 无法避免广播可发，但至少拒绝普通三方应用的伪造
        // Binder（防止恶意 App 抢先注册失效/误导性的 IShowerService）。
        // getSentFromUid 为 API 34+；低版本 system_server 中转后无法可靠还原来源，放行。
        //
        // UID_UNKNOWN(-1)：实测 Android 16 真机（vivo V2419A）下，server 经 IActivityManager
        // 转发到达时 sentFromUid 恒为 -1（server 侧日志显示广播已正常发出）。若把 -1 一并
        // 拒绝，会把**合法交接**丢弃，表现为「server 已启动却永远等不到 Binder」。
        // SYSTEM(1000) 同样要放行：不少 ROM 把这次广播的发送者记成 system_server，而不是
        // 真正调用 broadcastIntent 的 shell。只拒绝能确认是普通三方应用（uid >= 10000）的来源。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // getSentFromUid 在公开 SDK 里被标成 @hide，直接写 intent.sentFromUid 编译不过。
            val sentFromUid = readSentFromUid(intent)
            if (sentFromUid != null && sentFromUid != UID_UNKNOWN && sentFromUid !in PRIVILEGED_SENDER_UIDS) {
                ShowerLog.w(TAG, "拒绝来源不明的 Binder 广播：sentFromUid=$sentFromUid")
                return
            }
        }

        // API 33+ 走类型安全重载；minSdk 29 < 33，需保留旧 API 分支
        val container = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_BINDER_CONTAINER, ShowerBinderContainer::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER)
        }
        val binder = container?.binder
        val service = binder?.let { IShowerService.Stub.asInterface(it) }
        val alive = service?.asBinder()?.isBinderAlive == true
        ShowerLog.d(TAG, "onReceive: service=$service alive=$alive")
        ShowerBinderRegistry.setService(service)
    }

    private fun readSentFromUid(intent: Intent): Int? = runCatching {
        intent.javaClass.getMethod("getSentFromUid").invoke(intent) as Int
    }.getOrNull()

    companion object {
        private const val TAG = "TianyanShowerReceiver"

        /** Linux shell uid：app_process 以 shell 身份运行 shower-server。 */
        private const val SHELL_UID = 2000
        /** Linux root uid：Root 模式下 server 可能以 root 身份运行。 */
        private const val ROOT_UID = 0
        /** system_server：部分 ROM 把 IActivityManager 广播的来源记成系统 uid。 */
        private const val SYSTEM_UID = 1000
        /** Intent.sentFromUid 未回填时的占位值（android.os.Process.INVALID_UID）。 */
        private const val UID_UNKNOWN = -1
        private val PRIVILEGED_SENDER_UIDS = setOf(ROOT_UID, SYSTEM_UID, SHELL_UID)

        const val ACTION_SHOWER_BINDER_READY =
            "com.ai.assistance.operit.action.SHOWER_BINDER_READY"
        const val EXTRA_BINDER_CONTAINER = "binder_container"
    }
}
