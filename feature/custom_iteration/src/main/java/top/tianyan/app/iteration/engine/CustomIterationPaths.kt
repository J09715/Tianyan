package top.tianyan.app.iteration.engine

import android.content.Context
import java.io.File

/**
 * 自定义迭代所需的运行时路径集合。
 *
 * 三个目录全部来自 [top.tianyan.app.runtime.RuntimePathManager]，调用方在**点击时刻**解析，
 * 以保证 RootFS 安装/切换发行版后路径立即反映最新状态。
 *
 * @param hostHomeDir PRoot 绑定到 guest `/root` 的宿主目录
 * @param attachmentsDir Agent Skill 全局扫描根（`<attachmentsDir>/skills` → guest `/attachments/skills`）
 * @param workspaceDir Agent 工作区根（`<workspaceDir>/skills` → guest `/workspace/skills`）
 */
data class CustomIterationPaths(
    val hostHomeDir: File,
    val attachmentsDir: File,
    val workspaceDir: File,
)

/**
 * assets 读取抽象。
 *
 * 让 [CustomIterationBootstrap] 与 Android `Context` 解耦：单元测试注入内存实现，
 * 生产环境绑定 [AndroidAssetReader]。
 */
fun interface AssetReader {
    /** 读取 assets 内 [path]；不存在时返回 null。 */
    fun read(path: String): ByteArray?
}

/** 生产实现：直接读 APK assets。 */
class AndroidAssetReader(private val context: Context) : AssetReader {
    override fun read(path: String): ByteArray? =
        runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
}
