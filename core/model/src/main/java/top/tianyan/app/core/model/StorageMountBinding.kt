package top.tianyan.app.core.model

import kotlinx.serialization.Serializable
import java.io.File
import java.io.Serializable as JavaSerializable

/**
 * 宿主 (Android) 与沙箱 (Linux PRoot) 存储挂载绑定
 * 对应 PRoot 命令: -b <hostPath>:<guestPath>
 */
@Serializable
data class StorageMountBinding(
    val id: String,
    val name: String,
    val hostPath: String,
    val guestPath: String,
    val enabled: Boolean = true,
    val isSystemDefault: Boolean = false,
) : JavaSerializable {

    companion object {
        /** 容器内允许的挂载根：绑定 guestPath 必须落在其中之一 */
        val ALLOWED_GUEST_MOUNT_ROOTS = setOf("/mnt", "/sdcard")

        /** 宿主侧允许的挂载根（Android 共享存储） */
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

        /**
         * 归一化容器路径：折叠空段与 "."，返回以 / 开头、无尾 / 的规范路径。
         * 非绝对路径或包含 ".." 时返回 null（视为非法，不猜用户意图）。
         */
        fun normalizeGuestPath(path: String): String? {
            if (!path.startsWith('/')) return null
            val segments = path.split('/').filter { it.isNotBlank() && it != "." }
            if (segments.any { it == ".." }) return null
            return "/${segments.joinToString("/")}".trimEnd('/').ifBlank { "/" }
        }

        /** 容器路径归一化后是否位于允许的挂载根内 */
        fun isGuestPathAllowed(guestPath: String): Boolean {
            val guest = normalizeGuestPath(guestPath) ?: return false
            return ALLOWED_GUEST_MOUNT_ROOTS.any { root ->
                guest == root || guest.startsWith("$root/")
            }
        }

        /** 宿主路径（canonical 后）是否位于共享存储根内 */
        fun isHostPathAllowed(hostPath: String): Boolean {
            val sharedRoot = File(SHARED_STORAGE_ROOT).canonicalFile
            val host = File(hostPath).canonicalFile
            return host == sharedRoot ||
                host.absolutePath.startsWith(sharedRoot.absolutePath + File.separator)
        }

        /**
         * 返回绑定的第一个校验错误（人类可读中文），null 表示合法。
         * 供 PRoot 命令构建前的安全校验、运行时过滤脏数据、设置页保存/展示共用，
         * 保证三处规则永不漂移。
         */
        fun validationError(binding: StorageMountBinding): String? {
            if (':' in binding.hostPath || '\u0000' in binding.hostPath) return "宿主挂载路径包含非法字符"
            if (':' in binding.guestPath || '\u0000' in binding.guestPath) return "容器挂载路径包含非法字符"
            if (!binding.guestPath.startsWith('/')) return "容器挂载路径必须是绝对路径"
            val segments = binding.guestPath.split('/').filter { it.isNotBlank() && it != "." }
            if (segments.any { it == ".." }) return "容器挂载路径不允许包含 .."
            if (!isGuestPathAllowed(binding.guestPath)) return "容器挂载仅允许位于 /mnt 或 /sdcard 内"
            if (!isHostPathAllowed(binding.hostPath)) return "宿主挂载仅允许位于 $SHARED_STORAGE_ROOT 内"
            return null
        }
    }
}
