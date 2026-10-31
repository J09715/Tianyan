package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

/**
 * 🔧 沙箱工具链探针定义 (Sandbox Toolchain Probe)
 *
 * 描述「沙箱里某个可执行工具」的期望存在性与最低版本要求。
 *
 * 职责边界（与开发套件 PluginBundleScripts 严格区分）：
 * - 本清单**只负责检测**：探针回答「工具在不在、版本够不够新」；
 * - **补齐**不再由探针直接拼 apt 命令，而是把工具映射到开发套件组件 id，
 *   交给 ToolManager / BundleComponentBatch 安装（复用其白名单、PRoot 准备步骤与互斥锁）；
 * - 仅开发套件未覆盖的工具（strace/ltrace/gdb-multiarch/patchelf/binutils/smali）
 *   才走 apt 补齐，且同样复用 PluginBundleScripts 的准备步骤与 aptOptions。
 *   dex2jar 在 Debian 12 / Ubuntu 22.04/24.04 没有对应软件包，策略为 None。
 *
 * 本文件是纯数据 + 纯函数模块：不依赖 Android、不依赖沙箱，
 * 因此可以在 JVM 单元测试里完整覆盖版本比较与状态判定逻辑。
 */
@Serializable
data class ToolchainProbe(
    val id: String,
    /** 展示名（中文），例如 "CMake"。 */
    val displayName: String,
    /** 用途说明，展示在面板里让用户知道为什么需要它。 */
    val purpose: String,
    /** 命令名探测：任一候选命令存在即视为已安装。 */
    val commands: List<String>,
    /** 候选绝对路径（离线套件优先），与 [commands] 任一命中即算就绪。 */
    val candidatePaths: List<String> = emptyList(),
    /** 取版本号的命令模板；{cmd} 替换为探测到的可执行路径，{path} 替换为候选路径。 */
    val versionCommand: String? = null,
    /** 最低可接受版本号（数字段比较，如 "3.22" 表示 >= 3.22）。 */
    val minVersion: String? = null,
    /** 该工具是否必须 root 才能实际驱动（工具本体可装，运行时才需要特权）。 */
    val requiresRootAtRuntime: Boolean = false,
    /** 分类，用于面板分组。 */
    val group: ToolchainGroup,
    /** 补齐策略：为空表示「无法自动补齐」（提示用户去开发套件手动装）。 */
    val repair: RepairStrategy = RepairStrategy.None,
)

/**
 * 补齐策略。
 *
 * [ByBundleComponents]：把缺失/落后映射到开发套件组件 id，交给 ToolManager 安装
 * （这是首选路径，开发套件已覆盖 cmake/ninja/apktool/jadx/aapt2/apksigner/zipalign）。
 * [ByAptPackages]：开发套件未覆盖的补充工具，直接用 apt 装（复用 PluginBundleScripts 准备步骤）。
 * [None]：无法自动补齐，仅提示用户到开发套件安装。
 */
@Serializable
sealed interface RepairStrategy {
    @Serializable
    data object None : RepairStrategy

    @Serializable
    data class ByBundleComponents(
        val componentIds: List<String>,
        /** 用户可见的套件名，用于提示「请在开发套件安装 X」。 */
        val suiteName: String,
    ) : RepairStrategy

    @Serializable
    data class ByAptPackages(
        val packages: List<String>,
    ) : RepairStrategy
}

/** 工具链分组，面板按此渲染分区。 */
@Serializable
enum class ToolchainGroup {
    NATIVE_BUILD,
    REVERSE_ENGINEERING,
    DEBUG_INSPECT,
}

/** 单个工具的检测结论。 */
@Serializable
data class ToolchainProbeResult(
    val probe: ToolchainProbe,
    /** 实际命中的可执行路径，未命中为 null。 */
    val resolvedPath: String? = null,
    /** 解析出的版本号原文（未安装或无法解析时为 null）。 */
    val version: String? = null,
    val status: ToolchainStatus,
    /** 面向用户的中文结论。 */
    val summary: String,
)

/**
 * 工具状态。
 *
 * [MISSING] / [OUTDATED] 均视为「可自动补齐」；[NEEDS_ROOT] 表示工具本体已装好，
 * 但真正驱动它需要 root 特权（PRoot 伪 root 不算）；[UNKNOWN] 表示沙箱忙 / 版本读不到，
 * 绝不误报为缺失。
 */
@Serializable
enum class ToolchainStatus {
    READY,
    OUTDATED,
    MISSING,
    NEEDS_ROOT,
    UNKNOWN,
}

/** 一次全量检测的汇总。 */
@Serializable
data class ToolchainReport(
    val results: List<ToolchainProbeResult> = emptyList(),
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val readyCount: Int get() = results.count { it.status == ToolchainStatus.READY }
    val outdatedCount: Int get() = results.count { it.status == ToolchainStatus.OUTDATED }
    val missingCount: Int get() = results.count { it.status == ToolchainStatus.MISSING }
    val needsRootCount: Int get() = results.count { it.status == ToolchainStatus.NEEDS_ROOT }
    val unknownCount: Int get() = results.count { it.status == ToolchainStatus.UNKNOWN }

    /**
     * 真正可一键补齐的工具：状态为 MISSING/OUTDATED，且补齐策略不是 [RepairStrategy.None]。
     * 这保证「一键补齐 N 项」的 N 只统计那些补完后会真正变就绪的项，不空转。
     */
    val repairable: List<ToolchainProbeResult>
        get() = results.filter {
            (it.status == ToolchainStatus.MISSING || it.status == ToolchainStatus.OUTDATED) &&
                it.probe.repair !is RepairStrategy.None
        }

    /** 无法自动补齐、只能手动到开发套件安装的工具。 */
    val manualOnly: List<ToolchainProbeResult>
        get() = results.filter {
            (it.status == ToolchainStatus.MISSING || it.status == ToolchainStatus.OUTDATED) &&
                it.probe.repair is RepairStrategy.None
        }

    /** 补齐后依然需要 root 才能驱动的工具，单独提示，避免用户误以为已可用。 */
    val rootRequired: List<ToolchainProbeResult>
        get() = results.filter { it.status == ToolchainStatus.NEEDS_ROOT }

    val isAllReady: Boolean get() = results.isNotEmpty() && repairable.isEmpty() && manualOnly.isEmpty()

    /**
     * 每个探针都是 UNKNOWN：沙箱不可达或输出被截断。
     * UI 把它当成探测失败（失败横幅），而不是一份「全部待确认」的正常报告。
     */
    val probeFailed: Boolean
        get() = results.isNotEmpty() && results.all { it.status == ToolchainStatus.UNKNOWN }
}

/**
 * 🧰 沙箱工具链清单 —— 全量探针表（原生构建 / 逆向 / 调试）。
 *
 * 补齐策略映射到开发套件组件（BuiltinPluginBundles）：
 * - cmake / ninja → android-ndk
 * - apktool / jadx → android-re
 * - aapt2 / apksigner / zipalign → android-core
 * 其余开发套件未覆盖的工具（patchelf / binutils / smali / strace / ltrace / gdb）
 * 走 ByAptPackages；dex2jar / frida 走 None（仓库无包，或依赖 pip + root）。
 */
object SandboxToolchainCatalog {

    private fun bundleName(componentId: String): String =
        BuiltinPluginBundles.bundles
            .first { bundle -> bundle.components.any { it.id == componentId } }
            .name


    val probes: List<ToolchainProbe> = listOf(
        // ---------- 原生构建链 ----------
        ToolchainProbe(
            id = "cmake",
            displayName = "CMake",
            purpose = "Android 原生模块（externalNativeBuild）构建器，缺失时 C/C++ 代码无法编译",
            commands = listOf("cmake"),
            candidatePaths = listOf("/opt/tianyan/tools/android-suite-offline/cmake/bin/cmake"),
            versionCommand = "{cmd} --version",
            minVersion = "3.22",
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-ndk"),
                suiteName = bundleName("android-ndk"),
            ),
        ),
        ToolchainProbe(
            id = "ninja",
            displayName = "Ninja",
            purpose = "CMake 的默认构建后端，缺失时 native 构建无法落地产物",
            commands = listOf("ninja"),
            candidatePaths = listOf("/opt/tianyan/tools/android-suite-offline/cmake/bin/ninja"),
            versionCommand = "{cmd} --version",
            minVersion = "1.10",
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-ndk"),
                suiteName = bundleName("android-ndk"),
            ),
        ),
        ToolchainProbe(
            id = "patchelf",
            displayName = "patchelf",
            purpose = "改写 ELF 头信息（库名/rpath/解释器），SO 修补与注入必用",
            commands = listOf("patchelf"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByAptPackages(listOf("patchelf")),
        ),
        ToolchainProbe(
            id = "aapt2",
            displayName = "aapt2",
            purpose = "资源打包与解析，资源表（arsc）分析必用",
            commands = listOf("aapt2", "aapt"),
            candidatePaths = listOf("/opt/android-sdk/build-tools/35.0.0/aapt2"),
            versionCommand = "{cmd} version",
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-core"),
                suiteName = bundleName("android-core"),
            ),
        ),
        ToolchainProbe(
            id = "apksigner",
            displayName = "Apksigner",
            purpose = "APK 签名与重签名，自改包安装前必须过这一关",
            commands = listOf("apksigner"),
            candidatePaths = listOf(
                "/opt/android-sdk/build-tools/35.0.0/apksigner",
                "/usr/bin/apksigner",
            ),
            // apksigner version 输出的是内部工具版本（0.9），不是 build-tools 版本，比较无意义；
            // 命令存在即视为就绪
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-core"),
                suiteName = bundleName("android-core"),
            ),
        ),
        ToolchainProbe(
            id = "zipalign",
            displayName = "Zipalign",
            purpose = "APK 对齐优化，影响安装效率与体积",
            commands = listOf("zipalign"),
            // zipalign 没有 version 子命令（裸跑 exit=2 输出 usage），命令存在即就绪
            group = ToolchainGroup.NATIVE_BUILD,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-core"),
                suiteName = bundleName("android-core"),
            ),
        ),
        // ---------- 逆向分析 ----------
        ToolchainProbe(
            id = "jadx",
            displayName = "JADX",
            purpose = "APK/Dex 反编译为可读 Java 源码，逆向主力工具",
            commands = listOf("jadx"),
            candidatePaths = listOf(
                "/opt/tianyan/tools/android-suite-offline/jadx/bin/jadx",
                "/opt/jadx/bin/jadx",
            ),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.REVERSE_ENGINEERING,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-re"),
                suiteName = bundleName("android-re"),
            ),
        ),
        ToolchainProbe(
            id = "apktool",
            displayName = "Apktool",
            purpose = "APK 资源与 Smali 回编译，改包名/资源/流程必用",
            commands = listOf("apktool"),
            // 用套件安装的 apktool 包装脚本，不要把 apktool.jar 当成可执行文件直接跑。
            candidatePaths = listOf(
                "/opt/tianyan/bin/apktool",
                "/usr/local/bin/apktool",
                "/usr/bin/apktool",
                "/opt/tianyan/tools/android-suite-offline/bin/apktool",
            ),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.REVERSE_ENGINEERING,
            repair = RepairStrategy.ByBundleComponents(
                componentIds = listOf("android-re"),
                suiteName = bundleName("android-re"),
            ),
        ),
        ToolchainProbe(
            id = "baksmali",
            displayName = "baksmali/smali",
            purpose = "Dex 与 Smali 互转，字节码级修改的基础",
            commands = listOf("baksmali", "smali"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.REVERSE_ENGINEERING,
            repair = RepairStrategy.ByAptPackages(listOf("smali")),
        ),
        ToolchainProbe(
            id = "dex2jar",
            displayName = "dex2jar",
            purpose = "Dex 转 Jar，便于用常规 Java 工具分析字节码",
            commands = listOf("d2j-dex2jar", "d2j-dex2jar.sh"),
            group = ToolchainGroup.REVERSE_ENGINEERING,
            // Debian 12 / Ubuntu 22.04 / 24.04 的官方源没有 dex2jar 包，装它只会让补齐失败并把红点留着。
            repair = RepairStrategy.None,
        ),
        ToolchainProbe(
            id = "readelf",
            displayName = "binutils (readelf/objdump/strings)",
            purpose = "ELF 头段符号表分析，SO 逆向与壳识别的基础",
            commands = listOf("readelf"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.REVERSE_ENGINEERING,
            repair = RepairStrategy.ByAptPackages(listOf("binutils")),
        ),
        ToolchainProbe(
            id = "frida",
            displayName = "Frida",
            purpose = "动态插桩与 hook，运行期改写行为；工具可装，驱动需 root",
            commands = listOf("frida", "frida-ps"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.REVERSE_ENGINEERING,
            requiresRootAtRuntime = true,
            repair = RepairStrategy.None,
        ),
        // ---------- 调试与追踪 ----------
        ToolchainProbe(
            id = "strace",
            displayName = "strace",
            purpose = "系统调用追踪，定位文件/网络行为与崩溃根因",
            commands = listOf("strace"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.DEBUG_INSPECT,
            repair = RepairStrategy.ByAptPackages(listOf("strace")),
        ),
        ToolchainProbe(
            id = "ltrace",
            displayName = "ltrace",
            purpose = "库函数调用追踪，观察 dlopen/malloc 等关键行为",
            commands = listOf("ltrace"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.DEBUG_INSPECT,
            repair = RepairStrategy.ByAptPackages(listOf("ltrace")),
        ),
        ToolchainProbe(
            id = "gdb",
            displayName = "GDB (multiarch)",
            purpose = "原生调试器，支持 ARM64 跨架构调试",
            commands = listOf("gdb-multiarch", "gdb"),
            versionCommand = "{cmd} --version",
            group = ToolchainGroup.DEBUG_INSPECT,
            repair = RepairStrategy.ByAptPackages(listOf("gdb-multiarch")),
        ),
    )

    fun probesInGroup(group: ToolchainGroup): List<ToolchainProbe> = probes.filter { it.group == group }
}
