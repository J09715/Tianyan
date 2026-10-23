package top.tianyan.app.runtime.uml

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * UML 引擎产物清单（manifest）——对齐 Stryker `stryker_manifest.json` 的结构。
 *
 * 为什么需要它：天衍原先把 rootfs 下载地址硬编码在代码里，且 sha256 留空。
 * Stryker 的做法是「清单驱动」——产物块带 url + sha256 + size，应用每次现拉清单，
 * 于是：① 产物可以换版本而不用发新 APK；② 下载有真实校验，不再是空校验；
 * ③ 本机离线/清单拉不到时降级到内置回退地址，不会直接卡死。
 *
 * 天衍只消费 [rootless] 段（rootfs 镜像）；其余段（core/app/news…）解析时忽略。
 */
@Serializable
data class UmlManifest(
    @SerialName("manifest_version") val manifestVersion: Int = 0,
    val rootless: UmlRootlessAssets = UmlRootlessAssets(),
) {
    /** 可直接使用的 rootfs 产物（URL 合法才认）。 */
    val rootfs: UmlAsset? get() = rootless.rootfs?.takeIf { it.isUsable }
}

/** rootless 引擎产物块（Stryker manifest 的 `rootless` 段）。 */
@Serializable
data class UmlRootlessAssets(
    val version: String = "",
    val note: String = "",
    val qemu: UmlAsset? = null,
    val kernel: UmlAsset? = null,
    val initrd: UmlAsset? = null,
    val libslirp: UmlAsset? = null,
    val rootfs: UmlAsset? = null,
)

/** 单个产物：下载地址 + 校验信息。 */
@Serializable
data class UmlAsset(
    val url: String = "",
    val sha256: String = "",
    val size: Long = 0,
) {
    val isUsable: Boolean get() = url.startsWith("https://")
    val hasChecksum: Boolean get() = sha256.isNotBlank()
}
