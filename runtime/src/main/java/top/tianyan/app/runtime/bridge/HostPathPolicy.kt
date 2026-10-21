package top.tianyan.app.runtime.bridge

import java.io.File

/**
 * 宿主桥接的路径准入策略。
 *
 * ## 为什么单独成文件
 *
 * 原本这段逻辑是 [HostBridge] 的私有方法，但它的核心是**纯字符串/路径判断**，
 * 不需要 Android 上下文——只有「App 外部文件目录」这一条根需要 Context。
 * 把纯逻辑抽出来之后就能用普通 JUnit 测（不需要 Robolectric），回归成本才降得下来。
 *
 * ## 威胁模型
 *
 * 桥接端点的入参来自沙箱内的 Agent 工具调用，是**不可信输入**。沙箱与 App
 * 共享网络命名空间，因此沙箱里的任何进程都能向 `127.0.0.1:7980` 发请求
 * （认证 token 写在 `/opt/tianyan/.bridge-key`，沙箱内可读）。
 *
 * 典型攻击：构造 `/sdcard/../../data/data/top.tianyan.app/shared_prefs/xxx.xml`。
 * 字符串前缀替换后得到 `/storage/emulated/0/../../data/data/...`，看起来仍在
 * 共享存储之下，但文件系统解析 `..` 后实际落在 App 的私有目录。
 *
 * ## 判定规则
 *
 * 1. 先把沙箱路径映射到宿主路径（`/sdcard` → `/storage/emulated/0`）；
 * 2. 用 [File.canonicalPath] 规范化——它会解析 `..`、`.`、重复分隔符**以及符号链接**；
 * 3. 规范化结果必须落在允许根之内。
 *
 * 用 canonicalPath 而不是 `Path.normalize` 是因为后者不解析符号链接：
 * 「先在共享存储里建一个指向 /data 的软链，再走软链」能绕过纯字符串判定。
 * 代价是目标必须存在——对 install-apk 来说目标本来就得是存在的文件。
 */
object HostPathPolicy {

    /** 宿主共享存储根：用户放 APK 的地方。 */
    const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

    /**
     * 把沙箱路径映射到宿主路径（不做准入判定）。
     *
     * `/sdcard/Download/app.apk` → `/storage/emulated/0/Download/app.apk`。
     */
    fun mapSandboxToHost(sandboxPath: String): String = when {
        sandboxPath.startsWith("/sdcard/") ->
            "$SHARED_STORAGE_ROOT/${sandboxPath.removePrefix("/sdcard/")}"
        sandboxPath == "/sdcard" -> SHARED_STORAGE_ROOT
        else -> sandboxPath
    }

    /**
     * 规范化并校验路径是否落在允许根之内。
     *
     * @param rawPath 宿主路径（未规范化）。
     * @param allowedRoots 允许的根目录列表。
     * @return 规范化的绝对路径；越界或无法规范化时返回 null。
     */
    fun canonicalWithin(rawPath: String, allowedRoots: List<File>): String? {
        val candidate = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
        val canonical = candidate.path
        val permitted = allowedRoots.any { root ->
            val rootPath = runCatching { root.canonicalPath }.getOrDefault(root.absolutePath)
            canonical == rootPath || canonical.startsWith(rootPath + File.separator)
        }
        return canonical.takeIf { permitted }
    }

    /**
     * 一步到位：沙箱路径 → 允许根之内的宿主路径。
     *
     * @param sandboxPath 沙箱内路径（不可信输入）。
     * @param allowedRoots 允许的根目录列表。
     * @return 规范化的宿主路径；越界时返回 null。
     */
    fun resolve(sandboxPath: String, allowedRoots: List<File>): String? =
        canonicalWithin(mapSandboxToHost(sandboxPath), allowedRoots)
}
