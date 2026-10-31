package top.tianyan.app.core.datastore

const val REMOTE_CONFIGURATION_UNAVAILABLE = "!remote-configuration-unavailable!"

internal data class RemoteArchiveLoad<T>(val snapshot: T?, val error: String?)

/** Unreadable is different from empty: callers must keep execution disabled. */
internal fun <T> loadRemoteArchive(read: () -> String, decode: (String) -> T): RemoteArchiveLoad<T> =
    try { RemoteArchiveLoad(decode(read()), null) }
    catch (_: Exception) { RemoteArchiveLoad(null, "连接档案无法读取或解密，执行已停用。请明确选择本地系统后重新添加连接，原存档会保留。") }
