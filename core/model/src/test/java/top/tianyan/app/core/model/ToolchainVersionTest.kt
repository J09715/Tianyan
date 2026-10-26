package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱工具链版本判定逻辑单测。
 *
 * 覆盖：
 *  1. 版本号抽取（各种工具输出形态）；
 *  2. 版本比较（段数不等、无法解析、判不了时必须返回 null）；
 *  3. 状态判定顺序（沙箱不可达 ≠ 工具缺失；版本读不到 ≠ 已就绪）；
 *  4. NEEDS_ROOT 状态（root 依赖工具就绪后提升）；
 *  5. repairable 只统计可自动补齐的项（RepairStrategy.None 排除）。
 */
class ToolchainVersionTest {

    // ---------- 版本抽取 ----------

    @Test
    fun extractsVersionFromCmakeOutput() {
        assertEquals("3.22.1", ToolchainVersion.extractVersion("cmake version 3.22.1"))
    }

    @Test
    fun extractsVersionFromJadxOutput() {
        assertEquals("1.5.6", ToolchainVersion.extractVersion("jadx 1.5.6"))
    }

    @Test
    fun extractsVersionFromApktoolOutput() {
        assertEquals("2.9.3", ToolchainVersion.extractVersion("2.9.3"))
    }

    @Test
    fun extractsVersionFromBinutilsOutput() {
        assertEquals("2.42", ToolchainVersion.extractVersion("readelf (GNU Binutils) 2.42"))
    }

    @Test
    fun extractsVersionFromVPrefixedOutput() {
        assertEquals("22.14.0", ToolchainVersion.extractVersion("v22.14.0"))
    }

    @Test
    fun returnsNullWhenNoVersionPresent() {
        assertNull(ToolchainVersion.extractVersion("command not found"))
        assertNull(ToolchainVersion.extractVersion(""))
        assertNull(ToolchainVersion.extractVersion(null))
    }

    // ---------- 版本比较 ----------

    @Test
    fun comparesVersionsSegmentBySegment() {
        assertEquals(1, ToolchainVersion.compare("4.3.4", "3.22"))
        assertEquals(-1, ToolchainVersion.compare("3.21", "3.22"))
        assertEquals(0, ToolchainVersion.compare("3.22", "3.22.0"))
    }

    @Test
    fun comparesDifferentMajorVersions() {
        assertTrue(ToolchainVersion.compare("10.0", "9.9")!! > 0)
        assertTrue(ToolchainVersion.compare("2.0", "10.0")!! < 0)
    }

    @Test
    fun returnsNullWhenVersionUnparsable() {
        assertNull(ToolchainVersion.compare("abc", "3.22"))
        assertNull(ToolchainVersion.compare(null, "3.22"))
        assertNull(ToolchainVersion.compare("3.22", null))
    }

    // ---------- 状态判定 ----------

    @Test
    fun reportsUnknownWhenSandboxUnreachableInsteadOfMissing() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        val status = ToolchainVersion.resolveStatus(probe, null, null, reachable = false)
        assertEquals(ToolchainStatus.UNKNOWN, status)
    }

    @Test
    fun reportsMissingWhenExecutableNotFound() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        val status = ToolchainVersion.resolveStatus(probe, null, null)
        assertEquals(ToolchainStatus.MISSING, status)
    }

    @Test
    fun reportsOutdatedWhenVersionBelowMinimum() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        val status = ToolchainVersion.resolveStatus(probe, "/usr/bin/cmake", "3.18.0")
        assertEquals(ToolchainStatus.OUTDATED, status)
    }

    @Test
    fun reportsReadyWhenVersionMeetsMinimum() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        val status = ToolchainVersion.resolveStatus(probe, "/usr/bin/cmake", "4.3.4")
        assertEquals(ToolchainStatus.READY, status)
    }

    @Test
    fun reportsUnknownWhenMinVersionRequiredButVersionUnreadable() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        val status = ToolchainVersion.resolveStatus(probe, "/usr/bin/cmake", null)
        assertEquals(ToolchainStatus.UNKNOWN, status)
    }

    @Test
    fun reportsReadyWhenNoMinVersionRequired() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "readelf" }
        assertNull(probe.minVersion)
        val status = ToolchainVersion.resolveStatus(probe, "/usr/bin/readelf", null)
        assertEquals(ToolchainStatus.READY, status)
    }

    // ---------- NEEDS_ROOT ----------

    @Test
    fun readyRootToolIsPromotedToNeedsRoot() {
        val frida = SandboxToolchainCatalog.probes.first { it.id == "frida" }
        assertTrue(frida.requiresRootAtRuntime)

        val status = ToolchainVersion.resolveStatusWithRoot(frida, "/usr/bin/frida", "17.23.3")
        assertEquals(ToolchainStatus.NEEDS_ROOT, status)
    }

    @Test
    fun missingRootToolStaysMissing() {
        val frida = SandboxToolchainCatalog.probes.first { it.id == "frida" }
        val status = ToolchainVersion.resolveStatusWithRoot(frida, null, null)
        // 缺失就是缺失，不能因为 root 依赖就变成 NEEDS_ROOT
        assertEquals(ToolchainStatus.MISSING, status)
    }

    @Test
    fun nonRootReadyToolStaysReady() {
        val cmake = SandboxToolchainCatalog.probes.first { it.id == "cmake" }
        assertFalse(cmake.requiresRootAtRuntime)
        val status = ToolchainVersion.resolveStatusWithRoot(cmake, "/usr/bin/cmake", "4.3.4")
        assertEquals(ToolchainStatus.READY, status)
    }

    // ---------- 报告聚合 ----------

    @Test
    fun reportRepairableOnlyCountsAutoRepairable() {
        val auto = SandboxToolchainCatalog.probes.first { it.repair is RepairStrategy.ByBundleComponents }
        val manual = SandboxToolchainCatalog.probes.first { it.repair is RepairStrategy.None }
        val results = listOf(
            ToolchainProbeResult(auto, null, null, ToolchainStatus.MISSING, ""),
            ToolchainProbeResult(manual, null, null, ToolchainStatus.MISSING, ""),
        )

        val report = ToolchainReport(results = results)

        // 只有能自动补齐的进 repairable，None 策略进 manualOnly
        assertEquals(1, report.repairable.size)
        assertEquals(1, report.manualOnly.size)
        assertFalse(report.isAllReady)
    }

    @Test
    fun catalogCoversAllGroupsAndMarksRootTools() {
        val probes = SandboxToolchainCatalog.probes
        assertTrue(probes.isNotEmpty())
        assertTrue(probes.any { it.group == ToolchainGroup.NATIVE_BUILD })
        assertTrue(probes.any { it.group == ToolchainGroup.REVERSE_ENGINEERING })
        assertTrue(probes.any { it.group == ToolchainGroup.DEBUG_INSPECT })
        assertTrue(probes.any { it.requiresRootAtRuntime })
        assertTrue(probes.all { it.purpose.isNotBlank() })
        assertEquals(probes.size, probes.map { it.id }.distinct().size)
        assertNotNull(SandboxToolchainCatalog.probes.firstOrNull { it.id == "cmake" })
    }

    @Test
    fun allByBundleComponentIdsExistInBuiltinBundles() {
        // 补齐映射的开发套件组件 id 必须真实存在于 BuiltinPluginBundles，否则安装会空转
        val validIds = top.tianyan.app.core.model.BuiltinPluginBundles.bundles
            .flatMap { it.components }
            .map { it.id }
            .toSet()

        val mapped = SandboxToolchainCatalog.probes
            .mapNotNull { it.repair as? RepairStrategy.ByBundleComponents }
            .flatMap { it.componentIds }
            .distinct()

        mapped.forEach { id ->
            assertTrue("组件 id 不存在于开发套件: $id", id in validIds)
        }
    }

    @Test
    fun dex2jarIsNotRepairable() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "dex2jar" }
        assertTrue(probe.repair is RepairStrategy.None)
        val missing = ToolchainProbeResult(probe, null, null, ToolchainStatus.MISSING, "")
        val report = ToolchainReport(results = listOf(missing))
        assertTrue(report.repairable.isEmpty())
        assertEquals(1, report.manualOnly.size)
        assertEquals("dex2jar", report.manualOnly.single().probe.id)
    }

    @Test
    fun jadxAndApktoolUseTheDevSuiteBundleName() {
        val bundleName = BuiltinPluginBundles.bundles
            .first { bundle -> bundle.components.any { it.id == "android-re" } }
            .name
        listOf("jadx", "apktool").forEach { id ->
            val repair = SandboxToolchainCatalog.probes.first { it.id == id }.repair
                as RepairStrategy.ByBundleComponents
            assertEquals(listOf("android-re"), repair.componentIds)
            assertEquals(bundleName, repair.suiteName)
        }
    }

    @Test
    fun apktoolCandidateIsNotABareJar() {
        val probe = SandboxToolchainCatalog.probes.first { it.id == "apktool" }
        assertTrue(probe.candidatePaths.isNotEmpty())
        assertTrue(probe.candidatePaths.none { it.endsWith(".jar") })
        assertEquals("{cmd} --version", probe.versionCommand)
    }

    @Test
    fun allUnknownReportIsProbeFailure() {
        val results = SandboxToolchainCatalog.probes.map { probe ->
            ToolchainProbeResult(probe, null, null, ToolchainStatus.UNKNOWN, "")
        }
        assertTrue(ToolchainReport(results).probeFailed)
        val mixed = results.dropLast(1) + results.last().copy(status = ToolchainStatus.MISSING)
        assertFalse(ToolchainReport(mixed).probeFailed)
    }
}
