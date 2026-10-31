package top.tianyan.app.runtime

import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.database.StorageMountBindingRepository
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.model.StorageMountBinding
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * 汇总本次会话/命令可见的存储挂载绑定：内置共享/下载/文档三项 + 用户自定义绑定。
 *
 * 自定义绑定来自用户可编辑的 Room 表，历史版本可能存入越界路径。这里必须先行过滤并告警，
 * 绝不能让非法绑定流入 [top.tianyan.app.runtime.proot.ProotCommandBuilder] 导致会话启动崩溃；
 * ProotCommandBuilder 内的 require 仅作为防挂载逃逸的最后安全防线。
 *
 * 从 LinuxRuntimeImpl 拆出（逐字保留逻辑），以便控制该类文件行数基线。
 */
internal suspend fun resolveStorageMounts(
    settingsDataStore: RuntimePreferences,
    repository: StorageMountBindingRepository,
    logger: AppLogger,
): List<StorageMountBinding> = buildList {
    val sharedEnabled = settingsDataStore.mountSharedStorageEnabled.first()
    val sharedDir = File("/storage/emulated/0")
    if (sharedEnabled && sharedDir.isDirectory && sharedDir.canRead()) {
        add(StorageMountBinding("system-shared", "共享存储", "/storage/emulated/0", "/sdcard", true, true))
    }
    val downloadDir = File("/storage/emulated/0/Download")
    if (!sharedEnabled && settingsDataStore.mountDownloadEnabled.first() && downloadDir.isDirectory && downloadDir.canRead()) {
        add(StorageMountBinding("system-download", "下载", "/storage/emulated/0/Download", "/sdcard/Download", true, true))
    }
    val documentsDir = File("/storage/emulated/0/Documents")
    if (!sharedEnabled && settingsDataStore.mountDocumentsEnabled.first() && documentsDir.isDirectory && documentsDir.canRead()) {
        add(StorageMountBinding("system-documents", "文档", "/storage/emulated/0/Documents", "/sdcard/Documents", true, true))
    }
    repository.bindings.first().forEach { binding ->
        if (!binding.enabled) return@forEach
        val error = StorageMountBinding.validationError(binding)
        if (error == null) {
            add(binding)
        } else {
            logger.w("忽略非法存储挂载绑定（请在存储挂载设置中删除后重建） id=${binding.id} " +
                "host=${binding.hostPath} guest=${binding.guestPath}: $error")
        }
    }
}
