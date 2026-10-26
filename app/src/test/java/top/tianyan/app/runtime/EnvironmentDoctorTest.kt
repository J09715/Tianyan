package top.tianyan.app.runtime

import top.tianyan.app.core.model.BuiltinPluginBundles
import top.tianyan.app.core.model.DoctorStatus
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.runtime.doctor.EnvironmentDoctor
import top.tianyan.app.runtime.doctor.EnvironmentRepairer
import top.tianyan.app.runtime.shell.CommandResult
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentDoctorTest {

    @Test
    fun reportsUnreadyWhenSandboxNotInitialized() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.NotInitialized
        val doctor = EnvironmentDoctor(linuxRuntime = runtime)

        val report = doctor.check()

        assertEquals(DoctorStatus.ERROR, report.overallStatus)
        assertEquals(1, report.errorCount)
        assertEquals("sandbox_unready", report.items.first().id)
    }

    @Test
    fun reportsHealthyWhenAllPrerequisitesMet() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready

        // 配置正常情况下的命令返回值
        runtime.commandResults["mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/resolv.conf 2>/dev/null"] =
            CommandResult(0, "nameserver 114.114.114.114\nnameserver 223.5.5.5\n", "", 1)
        runtime.commandResults["test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true"] =
            CommandResult(0, "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main", "", 1)
        runtime.commandResults["for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["node --version 2>/dev/null || /opt/tianyan/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null"] =
            CommandResult(0, "v22.22.3\n", "", 1)
        BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand?.let { cmd ->
                runtime.commandResults[cmd] = CommandResult(0, "android env ok", "", 1)
            }

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        assertEquals(DoctorStatus.HEALTHY, report.overallStatus)
        assertEquals(8, report.healthyCount)
        assertEquals(0, report.warningCount)
        assertEquals(0, report.errorCount)
        assertTrue(report.isAllHealthy)
        assertFalse(report.needsFix)
    }

    @Test
    fun reportsWarningsWhenMirrorsAndNodeMissing() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready

        runtime.commandResults["mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/resolv.conf 2>/dev/null"] =
            CommandResult(0, "", "", 1) // 缺失 DNS
        runtime.commandResults["test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs"] =
            CommandResult(1, "", "not found", 1) // 缺失 CA
        runtime.commandResults["cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true"] =
            CommandResult(0, "deb http://ports.ubuntu.com/ubuntu-ports noble main", "", 1) // 官方海外源
        runtime.commandResults["for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done"] =
            CommandResult(0, "git\nxz\n", "", 1) // 缺失 git, xz
        runtime.commandResults["node --version 2>/dev/null || /opt/tianyan/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null"] =
            CommandResult(1, "", "not found", 1) // 缺失 node

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        assertEquals(DoctorStatus.WARNING, report.overallStatus)
        assertEquals(3, report.healthyCount)
        assertEquals(5, report.warningCount)
        assertTrue(report.needsFix)
    }

    @Test
    fun reportsWarningWhenUbuntuUsesNonPortsX86Mirror() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready

        runtime.commandResults["mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/resolv.conf 2>/dev/null"] =
            CommandResult(0, "nameserver 114.114.114.114\n", "", 1)
        runtime.commandResults["test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs"] =
            CommandResult(0, "", "", 1)
        // 错误的 x86 镜像（未带 -ports）
        runtime.commandResults["cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true"] =
            CommandResult(0, "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu noble main restricted", "", 1)
        runtime.commandResults["for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["node --version 2>/dev/null || /opt/tianyan/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null"] =
            CommandResult(0, "v22.22.3\n", "", 1)

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        assertEquals(DoctorStatus.WARNING, report.overallStatus)
        val aptItem = report.items.first { it.id == "apt_mirrors" }
        assertEquals(DoctorStatus.WARNING, aptItem.status)
        assertTrue(aptItem.summary.contains("ubuntu-ports"))
    }

    @Test
    fun reportsUnknownWhenSandboxBusyInsteadOfFalseWarnings() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready
        // 模拟沙箱正忙：所有沙箱内探针都拿不到结果（异常/超时）
        runtime.executeFailure = true

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        val sandboxItems = report.items.filter { it.id != "host_all_files_access" }

        // 探测不可达 ≠ 配置异常：全部如实灰牌 UNKNOWN，绝不误报 WARNING/ERROR
        assertTrue(sandboxItems.isNotEmpty())
        assertTrue(sandboxItems.all { it.status == DoctorStatus.UNKNOWN })
        assertEquals(DoctorStatus.UNKNOWN, report.overallStatus)
        assertEquals(0, report.warningCount)
        assertEquals(0, report.errorCount)
        assertEquals(sandboxItems.size, report.unknownCount)
        assertFalse(report.needsFix)

        // UNKNOWN 项不可修复：一键自愈不应被灰牌触发
        assertTrue(sandboxItems.all { !it.fixable })

        // 执行异常要透出异常原文，不能再一律写成「探针超时」
        val storageItem = report.items.first { it.id == "sandbox_storage" }
        assertTrue(storageItem.detail!!.contains("sandbox busy (fake)"))
        assertFalse(storageItem.detail!!.contains("探针超时"))
    }

    @Test
    fun reportsTimeoutInsteadOfMissingAndroidEnvironment() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready
        val androidCoreCheckCmd = BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand
        checkNotNull(androidCoreCheckCmd)
        // 命令触达执行超时：以超时退出码返回结果（不抛异常），需与执行异常区分
        runtime.commandResults[androidCoreCheckCmd] = CommandResult(
            exitCode = 124,
            stdout = "",
            stderr = "Command timed out after 8000ms",
            durationMs = 8000,
            outputDiagnostic = "Command timed out after 8000ms",
        )

        val report = EnvironmentDoctor(linuxRuntime = runtime).check()

        val androidItem = report.items.first { it.id == "android_environment" }
        assertEquals(DoctorStatus.UNKNOWN, androidItem.status)
        assertTrue(androidItem.detail!!.contains("Android 环境探针超时"))
        assertFalse(androidItem.summary.contains("未安装"))
    }

    @Test
    fun aptMirrorsUnknownWhenSourcesUnreadable() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready

        // 沙箱正常但源配置整体不可读（cat ... || true 吞错后 stdout 为空）——无法判定，应灰牌
        runtime.commandResults["mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/resolv.conf 2>/dev/null"] =
            CommandResult(0, "nameserver 114.114.114.114\n", "", 1)
        runtime.commandResults["test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["node --version 2>/dev/null || /opt/tianyan/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null"] =
            CommandResult(0, "v22.22.3\n", "", 1)
        BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand?.let { cmd ->
                runtime.commandResults[cmd] = CommandResult(0, "android env ok", "", 1)
            }

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        val aptItem = report.items.first { it.id == "apt_mirrors" }
        assertEquals(DoctorStatus.UNKNOWN, aptItem.status)
        assertFalse(aptItem.summary.contains("官方默认源"))
    }

    @Test
    fun environmentRepairerEmitsAllProgressStepsToCompletion() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready
        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val repairer = EnvironmentRepairer(runtime, doctor)

        val progresses = repairer.repair().toList()

        assertTrue(progresses.size >= 5)
        val last = progresses.last()
        assertTrue(last.isCompleted)
        assertFalse(last.isFailed)
        assertEquals(1.0f, last.progress, 0.01f)
    }

    @Test
    fun reportsHealthyWhenOfflineAndroidSuiteInstalled() = runBlocking {
        val runtime = FakeLinuxRuntime()
        runtime.state.value = RuntimeState.Ready

        runtime.commandResults["mkdir -p /workspace /tmp && touch /workspace/.doctor_probe && rm -f /workspace/.doctor_probe"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/resolv.conf 2>/dev/null"] =
            CommandResult(0, "nameserver 114.114.114.114\n", "", 1)
        runtime.commandResults["test -f /etc/ssl/certs/ca-certificates.crt || test -d /etc/ssl/certs"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["cat /etc/apt/sources.list /etc/apt/sources.list.d/*.sources /etc/apt/sources.list.d/*.list 2>/dev/null || true"] =
            CommandResult(0, "deb https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports noble main", "", 1)
        runtime.commandResults["for t in curl git tar xz file; do which \$t >/dev/null 2>&1 || echo \$t; done"] =
            CommandResult(0, "", "", 1)
        runtime.commandResults["node --version 2>/dev/null || /opt/tianyan/bin/node --version 2>/dev/null || /usr/bin/node --version 2>/dev/null"] =
            CommandResult(0, "v22.22.3\n", "", 1)

        val androidCoreCheckCmd = BuiltinPluginBundles.bundles
            .firstOrNull { it.id == "android-suite" }
            ?.components?.firstOrNull { it.id == "android-core" }
            ?.checkCommand
        checkNotNull(androidCoreCheckCmd)
        runtime.commandResults[androidCoreCheckCmd] = CommandResult(0, "android env ready", "", 1)

        val doctor = EnvironmentDoctor(linuxRuntime = runtime)
        val report = doctor.check()

        val androidItem = report.items.first { it.id == "android_environment" }
        assertEquals(DoctorStatus.HEALTHY, androidItem.status)
        assertTrue(androidItem.summary.contains("JDK 17 / Android SDK / Gradle / NDK 已就绪"))
    }
}
