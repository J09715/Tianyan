package top.tianyan.app.core.model

/**
 * 🔢 工具版本比较与状态判定 —— 纯 Kotlin 逻辑，无 Android / 无沙箱依赖，可直接单测。
 *
 * i18n 约定：model 层只返回状态码（[ToolchainStatus]）与结构化字段，
 * 用户可见文案由 UI 层根据 [ToolchainProbeResult] 组装。因此本文件**不产生中文文案**，
 * [ToolchainStatus] 的展示文本在 strings.xml 里。
 */
object ToolchainVersion {

    /**
     * 从任意工具输出中抽取第一个版本号。
     *
     * 覆盖常见形态：
     *  - `cmake version 3.22.1` → `3.22.1`
     *  - `jadx 1.5.6`            → `1.5.6`
     *  - `apktool 2.9.3`         → `2.9.3`
     *  - `v22.14.0`              → `22.14.0`
     *  - `ninja 1.11.1`          → `1.11.1`
     *  - `readelf (GNU Binutils) 2.42` → `2.42`
     *
     * 抽取不到时返回 null —— 调用方应据此给 UNKNOWN，绝不臆造版本号。
     */
    fun extractVersion(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""\d+\.\d+(?:\.\d+)*""").find(raw)
        return match?.value
    }

    /**
     * 比较两个版本号（点分数字）。
     *
     * @return >0 表示 [actual] 更新，<0 表示落后，0 表示相同。
     * 段数不同时按 0 补齐；存在无法解析的段时返回 null（判不了，降级 UNKNOWN）。
     */
    fun compare(actual: String?, minimum: String?): Int? {
        if (minimum.isNullOrBlank()) return null
        val actualParts = actual?.takeIf { it.isNotBlank() }?.split('.') ?: return null
        val minParts = minimum.split('.')
        val size = maxOf(actualParts.size, minParts.size)
        for (i in 0 until size) {
            val a = actualParts.getOrNull(i)?.trim()?.toIntOrNull() ?: if (i < actualParts.size) return null else 0
            val b = minParts.getOrNull(i)?.trim()?.toIntOrNull() ?: if (i < minParts.size) return null else 0
            if (a != b) return if (a > b) 1 else -1
        }
        return 0
    }

    /**
     * 判定单个工具的状态。
     *
     * 判定顺序（与文档口径严格一致）：
     *  1. 探测不可达（[reachable] 为 false）→ UNKNOWN（不误报缺失）；
     *  2. 未找到可执行文件 → MISSING；
     *  3. 找到了但版本解析不出来、且清单设了最低版本 → UNKNOWN；
     *  4. 有版本但低于最低版本 → OUTDATED；
     *  5. 其余 → READY。
     *
     * [NEEDS_ROOT] 由 [resolveStatusWithRoot] 产出：当工具就绪、但清单标记了
     * requiresRootAtRuntime 时，把 READY 提升为 NEEDS_ROOT，让面板如实标注「装好了但要用还需 root」。
     */
    fun resolveStatus(
        probe: ToolchainProbe,
        resolvedPath: String?,
        version: String?,
        reachable: Boolean = true,
    ): ToolchainStatus {
        if (!reachable) return ToolchainStatus.UNKNOWN
        if (resolvedPath.isNullOrBlank()) return ToolchainStatus.MISSING

        val minimum = probe.minVersion
        if (minimum.isNullOrBlank()) return ToolchainStatus.READY

        if (version.isNullOrBlank()) return ToolchainStatus.UNKNOWN

        val cmp = compare(version, minimum)
        return when {
            cmp == null -> ToolchainStatus.UNKNOWN
            cmp < 0 -> ToolchainStatus.OUTDATED
            else -> ToolchainStatus.READY
        }
    }

    /**
     * 在 [resolveStatus] 基础上，把「已就绪但运行时需要 root」的工具标为 NEEDS_ROOT。
     *
     * 只有 READY 才可能升级为 NEEDS_ROOT；MISSING/OUTDATED/UNKNOWN 保持不变
     *（先补齐/确认版本，root 依赖是另一回事）。
     */
    fun resolveStatusWithRoot(
        probe: ToolchainProbe,
        resolvedPath: String?,
        version: String?,
        reachable: Boolean = true,
    ): ToolchainStatus {
        val base = resolveStatus(probe, resolvedPath, version, reachable)
        return if (base == ToolchainStatus.READY && probe.requiresRootAtRuntime) {
            ToolchainStatus.NEEDS_ROOT
        } else {
            base
        }
    }
}
