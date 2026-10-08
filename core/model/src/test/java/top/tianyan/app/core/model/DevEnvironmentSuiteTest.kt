package top.tianyan.app.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 开发套件装配脚本的结构校验。
 *
 * 这些断言针对的是「脚本语法上没错、但在真机上装不上」这一类问题——
 * 靠读代码看不出来，靠跑单元测试也跑不出来，只能在生成脚本这一步把结构钉住：
 *   · 回落链的运算符优先级（`A || B && C` 里的 C 永远不会跑）；
 *   · 强删一个被待装包依赖的包，让 apt 直接以 Unmet dependencies 失败；
 *   · 自愈步引用了尚未定义的变量。
 */
class DevEnvironmentSuiteTest {

    private fun steps(vararg ids: String) =
        BuiltinPluginBundles.buildBatchInstallScript(ids.toSet())

    private val allComponentIds = BuiltinPluginBundles.bundles.flatMap { it.components }.map { it.id }

    /** 全选：流水线内容最全，用来校验各步之间的结构关系。 */
    private fun allSteps() = steps(*allComponentIds.toTypedArray())

    /** 全选时必须生成非空流水线，且第一步是环境自愈。 */
    @Test
    fun fullSelectionProducesAPipeline() {
        val script = steps(*allComponentIds.toTypedArray())
        assertTrue(script.isNotEmpty(), "全选也要有步骤")
        assertTrue(script.first().contains("mkdir -p /etc/dpkg"), "首步应为 dpkg 目录准备")
    }

    /**
     * 不选任何组件时仍要装基础包。
     *
     * 基础包（curl/git/python3/rg 等）是终端与 Agent 的工具链底线，
     * 设计上就是「始终隐式安装」，所以步骤不该为空。
     */
    @Test
    fun emptySelectionStillInstallsBasePackages() {
        val script = steps().joinToString("\n")
        assertTrue(script.contains("git") && script.contains("curl"), "空选择也要装基础包")
        assertTrue(!script.contains("/opt/tianyan/scripts/"), "空选择不该带上任何组件的后置脚本")
    }

    /**
     * apt 回落链必须显式分组。
     *
     * `A || B && C` 在 sh 里解析为 `A || (B && C)`：B（-f install 修依赖）一失败，
     * C（重试主安装）就永远不会执行——「修完依赖再重试」这条恢复路径恰好在该用的时候是死的。
     */
    @Test
    fun aptFallbackChainIsExplicitlyGrouped() {
        val candidates = allSteps().filter {
            it.contains("||") && it.contains("apt-get") && it.contains(" install ")
        }
        val grouped = candidates.filter { it.contains("|| {") && it.contains("; }") }
        assertTrue(
            grouped.isNotEmpty(),
            "批量安装的回落链缺少显式分组 {}：`A || B && C` 里的 C 永远不会执行\n  " +
                candidates.joinToString("\n  "),
        )
    }

    /**
     * 不得强删被待装包依赖的包。
     *
     * 安装列表里的 apktool 依赖 default-jre，后者经 java-common 拉入 java-wrappers。
     * 用 --force-depends 强删 java-wrappers，会留下「apktool 已装、其依赖没了」的状态，
     * 紧接着的 apt-get install 要么被迫重装它（白删），要么以 Unmet dependencies 失败。
     */
    @Test
    fun doesNotForceRemoveDependenciesOfInstalledPackages() {
        val script = steps(*allComponentIds.toTypedArray())
        val removeSteps = script.filter { it.contains("dpkg --remove") }
        assertTrue(removeSteps.any { it.contains("unzip") }, "应当仍清理被中断的 unzip 事务")
        removeSteps.forEach { step ->
            assertTrue(
                !step.contains("java-wrappers"),
                "dpkg --remove 里出现了 java-wrappers：apktool 经 default-jre/java-common 依赖它，强删会导致后续 install 失败\n  $step",
            )
        }
    }

    /**
     * 生成结果里不得残留未展开的 Kotlin 变量。
     *
     * `$aptOpts` / `$packageArg` 都是 Kotlin 侧插值：生成时必须已展开成实际内容。
     * 残留字面量意味着 shell 收到的是 `apt-get $aptOpts ...`，变量为空、语义变形。
     */
    @Test
    fun generatedStepsHaveNoUnresolvedKotlinVariables() {
        val literals = listOf("$" + "aptOpts", "$" + "packageArg")
        allSteps().forEach { step ->
            literals.forEach { literal ->
                assertTrue(!step.contains(literal), "生成的步骤里残留了未展开的 $literal\n  $step")
            }
        }
    }

    /** 依赖修复步要在批量安装之前，否则修了也白修。 */
    @Test
    fun dependencyRepairRunsBeforeBulkInstall() {
        val script = allSteps()
        val repair = script.indexOfFirst { it.contains("-f install") && it.contains("|| true") }
        val bulk = script.indexOfFirst { it.contains("|| {") && it.contains("install -y --no-install-recommends") }
        assertTrue(repair >= 0, "应有依赖修复步（实际 $repair）")
        assertTrue(bulk >= 0, "应有批量安装步（实际 $bulk）")
        assertTrue(repair < bulk, "依赖修复必须排在批量安装之前（repair=$repair bulk=$bulk）")
    }

    /** 批量安装只做一次 update，避免每个组件各跑一遍拖慢装配。 */
    @Test
    fun aptUpdateRunsOnlyOnce() {
        val updates = steps(*allComponentIds.toTypedArray()).count { it.contains(" update ") && it.contains("apt-get") }
        assertEquals(1, updates, "apt-get update 只应出现一次")
    }

    /** 组件 id 必须唯一：重复 id 会让选择状态与探针结果串台。 */
    @Test
    fun componentIdsAreUnique() {
        val dupes = allComponentIds.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(dupes.isEmpty(), "组件 id 重复: $dupes")
    }

    /** 每个组件都要有探针命令，否则装完无法判断是否就绪。 */
    @Test
    fun everyComponentHasACheckCommand() {
        BuiltinPluginBundles.bundles.flatMap { it.components }.forEach { comp ->
            assertTrue(comp.checkCommand.isNotBlank(), "${comp.id} 缺少 checkCommand")
        }
    }

    /** 后置脚本必须用 /bin/sh 绝对路径调用：PRoot 里不保证有 bash。 */
    @Test
    fun postInstallStepsInvokePosixShell() {
        BuiltinPluginBundles.bundles.flatMap { it.components }.forEach { comp ->
            comp.postInstallSteps.forEach { step ->
                assertTrue(
                    step.startsWith("/bin/sh "),
                    "${comp.id} 的后置步骤应以 /bin/sh 调用脚本: $step",
                )
            }
        }
    }

    /** 基础包始终隐式安装：终端与 Agent 的工具链底线。 */
    @Test
    fun basePackagesAreAlwaysIncluded() {
        val script = steps("python-core").joinToString("\n")
        BuiltinPluginBundles.baseRequiredPackages.forEach { pkg ->
            assertTrue(script.contains(pkg), "基础包 $pkg 必须始终被安装")
        }
    }

    /** 只选一个组件时不该带上别的组件的后置脚本。 */
    @Test
    fun selectionDoesNotLeakOtherComponentsScripts() {
        val script = steps("python-core").joinToString("\n")
        assertTrue(!script.contains("setup_android_core.sh"), "python-core 不该触发 Android 装配脚本")
        assertTrue(!script.contains("setup_flutter.sh"), "python-core 不该触发 Flutter 装配脚本")
    }
}
