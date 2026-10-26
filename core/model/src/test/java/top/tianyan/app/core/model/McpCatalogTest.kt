package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 能力仓库的契约测试。
 *
 * 这一层的价值全在"可复现"上：只要版本能漂、校验和能缺、组件绑定能指向不存在的 id，
 * 安装就会从"一件确定的事"退化成"看运气"。因此下面每一条都对应一条硬约束。
 */
class McpCatalogTest {

    @Test
    fun everyInstallableEntryPinsVersionAndChecksum() {
        assertTrue("catalog 不应为空", McpCatalog.installable.isNotEmpty())
        McpCatalog.installable.forEach { entry ->
            assertEquals("${entry.id} 必须是 npm 通道", McpCatalogChannel.NPM, entry.channel)
            assertTrue(
                "${entry.id} 缺少三段式固定版本：${entry.pinnedVersion}",
                Regex("^\\d+\\.\\d+\\.\\d+").containsMatchIn(entry.pinnedVersion),
            )
            assertTrue(
                "${entry.id} 的包规格必须钉住版本（禁止 @latest）：${entry.packageSpec}",
                entry.packageSpec.endsWith("@${entry.pinnedVersion}"),
            )
            assertTrue("${entry.id} 缺少 sha512 校验和", entry.integrity.startsWith("sha512-"))
            assertTrue(
                "${entry.id} 的落位标记必须在 ${McpCatalog.MARKER_DIR} 下：${entry.markerPath}",
                entry.markerPath.startsWith("${McpCatalog.MARKER_DIR}/"),
            )
        }
    }

    @Test
    fun installStepsCarryIdSpecAndIntegrityToTheInstallerScript() {
        McpCatalog.installable.forEach { entry ->
            val step = entry.installSteps.single()
            assertTrue("${entry.id} 未调用统一下载脚本", step.contains("setup_mcp_catalog.sh install"))
            assertTrue("${entry.id} 未把包规格传给脚本", step.contains(entry.packageSpec))
            assertTrue("${entry.id} 未把校验和传给脚本", step.contains(entry.integrity))
            assertFalse("${entry.id} 的安装步骤不得出现浮动版本", step.contains("@latest"))
        }
    }

    @Test
    fun installableEntriesMapToRealBundleComponents() {
        val componentIds = BuiltinPluginBundles.bundles.flatMap { it.components }.map { it.id }.toSet()
        McpCatalog.installable.forEach { entry ->
            assertTrue(
                "${entry.id} 绑定的组件不存在，安装会空转：${entry.installComponentId}",
                entry.installComponentId in componentIds,
            )
        }
        McpCatalog.installable.flatMap { it.dependsOnComponentIds }.distinct().forEach { id ->
            assertTrue("依赖组件不存在：$id", id in componentIds)
        }
    }

    @Test
    fun componentGenerationSurvivesTheUninstallAllowList() {
        val allIds = BuiltinPluginBundles.bundles.flatMap { it.components }.map { it.id }.toSet()
        McpCatalog.installable.forEach { entry ->
            val id = entry.installComponentId
            val install = BuiltinPluginBundles.buildBatchInstallScript(setOf(id)).joinToString("\n")
            assertTrue("$id 的装配脚本缺少下载步骤", install.contains("setup_mcp_catalog.sh install $id"))
            // 卸载脚本会逐条校验 postUninstallSteps 与 purgeFiles 的白名单，能生成即代表通过校验。
            val uninstall = BuiltinPluginBundles.buildBatchUninstallScript(setOf(id), allIds - id).joinToString("\n")
            assertTrue("$id 的卸载脚本缺少全局包卸载", uninstall.contains("npm uninstall -g ${entry.packageName}"))
            assertTrue("$id 的卸载脚本缺少落位标记清理", uninstall.contains(entry.markerPath))
        }
    }

    @Test
    fun npmPresetsMatchTheirCatalogRuntimeDeclaration() {
        val installablePresets = BuiltinMcpPresets.presets.filter { it.requiresInstall }
        assertTrue("至少应有一个按需安装的内置能力", installablePresets.isNotEmpty())
        installablePresets.forEach { preset ->
            val entry = McpCatalog.find(preset.id)
            assertNotNull("${preset.id} 声明了需要安装，但 catalog 没有对应条目", entry)
            requireNotNull(entry)
            assertTrue("${preset.id} 未绑定安装组件", preset.installComponentId.isNotBlank())
            assertEquals("${preset.id} 的绑定组件与 catalog 不一致", entry.installComponentId, preset.installComponentId)
            assertEquals("${preset.id} 的启动命令与 catalog 不一致", entry.launchCommand, preset.command)
            assertEquals("${preset.id} 的启动参数与 catalog 不一致", entry.launchArgs, preset.args)
            assertEquals("${preset.id} 的环境变量与 catalog 不一致", entry.runtimeEnv, preset.env)
            assertEquals("${preset.id} 的风险等级与 catalog 不一致", entry.risk, preset.builtinRisk)
            assertFalse("${preset.id} 在安装前不得默认启用", preset.isEnabled)
        }
    }

    /**
     * 启动参数必须**不含版本号**。
     *
     * 这是「升级」能不能真的生效的分水岭：版本一旦写进启动参数，升级只改了全局安装，
     * 进程仍按代码里的旧 pin 拉起，用户看到的「已升级」就是假的。版本只允许来自落位标记。
     */
    @Test
    fun launchPathResolvesVersionFromTheMarkerInsteadOfTheCodePin() {
        McpCatalog.installable.forEach { entry ->
            assertEquals("${entry.id} 的启动命令应交给装配脚本", "/bin/sh", entry.launchCommand)
            assertEquals(
                "${entry.id} 的启动参数应以 run 子命令开头",
                listOf(McpCatalog.SCRIPT_PATH, "run", entry.id, entry.packageName),
                entry.launchArgs.take(4),
            )
            assertEquals(
                "${entry.id} 的服务自身参数应原样跟在后面",
                entry.serverArgs,
                entry.launchArgs.drop(4),
            )
            assertFalse(
                "${entry.id} 的启动参数不得携带版本号：${entry.launchArgs}",
                Regex("@\\d+\\.\\d+\\.\\d+").containsMatchIn(entry.launchArgs.joinToString(" ")),
            )
        }
    }

    /** 检查更新与升级都要走装配脚本，且只带 id 与包名——不带版本，升级才有意义。 */
    @Test
    fun updateCommandsGoThroughTheInstallerScriptWithoutHardcodingVersions() {
        McpCatalog.installable.forEach { entry ->
            val expected = "/bin/sh ${McpCatalog.SCRIPT_PATH}"
            assertTrue(
                "${entry.id} 的检查更新命令未走装配脚本：${entry.checkUpdateCommand}",
                entry.checkUpdateCommand.startsWith("$expected check ${entry.id} ${entry.packageName}"),
            )
            assertTrue(
                "${entry.id} 的升级命令未走装配脚本：${entry.upgradeCommand}",
                entry.upgradeCommand.startsWith("$expected upgrade ${entry.id} ${entry.packageName}"),
            )
            listOf(entry.checkUpdateCommand, entry.upgradeCommand).forEach { command ->
                assertFalse("${entry.id} 的版本操作不得携带版本号：$command", Regex("@\\d+\\.\\d+\\.\\d+").containsMatchIn(command))
            }
            assertTrue(
                "${entry.id} 的探针应回落到本地入口核对，而不是只看标记文件：${entry.probeCommand}",
                entry.probeCommand.contains("probe ${entry.id} ${entry.packageName}"),
            )
        }
    }

    /** 「可更新」的判定必须同时依赖两边事实：只拿到一边就下结论会误导用户。 */
    @Test
    fun updateStatusOnlyClaimsAnUpdateWhenBothSidesAreKnownAndDiffer() {
        assertFalse(
            "未安装时不应声称可更新",
            McpUpdateStatus("mcp_x", installedVersion = "", latestVersion = "2.0.0").updateAvailable,
        )
        assertFalse(
            "上游未知时不应声称可更新",
            McpUpdateStatus("mcp_x", installedVersion = "1.0.0").updateAvailable,
        )
        assertFalse(
            "版本相同时不应声称可更新",
            McpUpdateStatus("mcp_x", installedVersion = "1.0.0", latestVersion = "1.0.0").updateAvailable,
        )
        assertTrue(
            "两边都已知且不同才是可更新",
            McpUpdateStatus("mcp_x", installedVersion = "1.0.0", latestVersion = "1.1.0").updateAvailable,
        )
        assertFalse(
            "出错时不得声称可更新",
            McpUpdateStatus("mcp_x", installedVersion = "1.0.0", latestVersion = "1.1.0", error = "镜像不可达").updateAvailable,
        )
        assertEquals("镜像不可达", McpUpdateStatus("mcp_x", error = "镜像不可达").summary)
    }

    @Test
    fun unavailableEntriesAreExcludedAndExplainWhy() {
        val unavailable = McpCatalog.entries.filterNot { it.available }
        assertTrue("应保留不可用条目以便暴露规划项", unavailable.isNotEmpty())
        unavailable.forEach { entry ->
            assertTrue("${entry.id} 不可用但没有给理由", entry.unavailableReason.isNotBlank())
            assertFalse("${entry.id} 不可用却进了可安装集合", McpCatalog.installable.contains(entry))
        }
    }

    @Test
    fun readyServerIdsFollowTheComponentProbe() {
        assertEquals(
            setOf("mcp_websearch", "mcp_codegraph"),
            McpCatalog.readyServerIds(setOf("mcp_websearch", "mcp_codegraph", "android-core")),
        )
        assertTrue("没有任何组件就绪时不应有 MCP 被判定为可用", McpCatalog.readyServerIds(emptySet()).isEmpty())
    }

    @Test
    fun bannedPackagesNeverReachTheCatalog() {
        // 抢注 / 投毒信号：无 scope 的同名包 server-github 的 0.0.1-security，以及 PyPI 上的疑似重名包。
        val banned = setOf("server-github", "mcp-server-memory")
        McpCatalog.entries.forEach { entry ->
            assertFalse("${entry.id} 引用了被排除的包名：${entry.packageName}", entry.packageName in banned)
        }
    }
}
