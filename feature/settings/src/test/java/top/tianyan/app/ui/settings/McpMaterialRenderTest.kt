package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.tianyan.app.core.model.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h800dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AgentEcoMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun modelManagementEntryKeepsItsNavigationCallback() {
        var opened = false
        render("round3-agent-hub", height = 800, content = {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AgentEcoSettingsScreen(onBack = {}, onOpenModelProfiles = { opened = true }, onOpenLocalLlm = {},
                    onOpenAgentSettings = {}, onOpenSubagentSettings = {}, onOpenSkillSettings = {}, onOpenMcpSettings = {},
                    onOpenQuickPhrases = {}, onOpenStats = {}, onOpenPhoneAgent = {}, viewModel = null)
            }
        }) { root ->
            click(root, "模型档案管理")
            assertTrue(opened)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpListMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun installationAndPendingToggleGatesSurviveTheSharedRowLayout() {
        var detail = false
        var added = false
        val ready = McpServerConfig("ready", "文档检索", description = "按需唤醒的本地服务")
        val blocked = McpServerConfig("blocked", "待安装服务", isEnabled = false)
        val offline = McpServerConfig("offline", "远程服务", transportType = McpTransportType.SSE)
        render("round3-mcp-list", dark = true, width = 360, height = 800, scale = 1.3f, content = {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                McpManagementActions(3, {}, {}, { added = true })
                McpServerItemCard(ready, McpConnectionState.SLEEPING, null, false, null, 6, "已授权", true,
                    onToggle = {}, onClickDetail = {})
                McpServerItemCard(blocked, McpConnectionState.UNKNOWN, null, false, "开发组件", null, null, false,
                    onToggle = { fail("An uninstalled service must stay disabled") }, onClickDetail = { detail = true })
                McpServerItemCard(offline, McpConnectionState.OFFLINE, "服务连接失败", true, null, null, null, false,
                    onToggle = { fail("A pending toggle must stay disabled") }, onClickDetail = {})
            }
        }) { root ->
            assertTrue(hasText(root, "已就绪（休眠）"))
            assertTrue(hasText(root, "需先安装"))
            for (name in listOf("待安装服务", "远程服务")) {
                val control = nodes(root).single {
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(name) &&
                        it.config.getOrNull(SemanticsProperties.ToggleableState) != null
                }
                assertNotNull(control.config.getOrNull(SemanticsProperties.Disabled))
            }
            click(root, "待安装服务")
            assertTrue(detail)
            click(root, "添加服务")
            assertTrue(added)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpEditorMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun savingAnExistingServicePreservesNonFormFieldsWithoutStartingIt() {
        val original = McpServerConfig("fixture", "本地检索", description = "离线测试配置", command = "uvx",
            args = listOf("mcp-fixture"), env = mapOf("DATA_DIR" to "/opt/tianyan/demo"), isBuiltin = true, isEnabled = false,
            requiresInstall = true, installComponentId = "fixture-component")
        var saved: McpServerConfig? = null
        render("round3-mcp-editor", content = {
            McpServerEditorDialog(initial = original, onDismiss = {}, onSave = { saved = it })
        }) { root ->
            click(root, "保存")
            assertEquals(original, saved)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h550dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpJsonMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun jsonModeRetainsTheBatchSaveContractInAShortViewport() {
        var saved: List<McpServerConfig>? = null
        render("round3-mcp-json", width = 360, height = 550, content = {
            McpServerEditorDialog(onDismiss = {}, onSave = { fail("JSON mode must use batch save") }, onSaveBatch = { saved = it })
        }) { root ->
            click(root, "JSON 导入")
            click(root, "导入 (1 个服务)")
            assertEquals("sqlite", requireNotNull(saved).single().name)
            assertEquals("uvx", requireNotNull(saved).single().command)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class McpDetailsMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun openingDetailsDoesNotProbeAndRetainsEditAndDeleteActions() {
        var edit = false
        var delete = false
        render("round3-mcp-details", content = {
            McpServerDetailDialog(McpServerConfig("fixture", "本地文档", description = "服务配置与工具权限", command = "uvx",
                args = listOf("mcp-fixture")), onDismiss = {}, onDelete = { delete = true },
                onEdit = { edit = true }, onTest = { error("Opening details must not probe the service") })
        }) { root ->
            click(root, "编辑")
            assertTrue(edit)
            assertFalse(delete)
            click(root, "删除服务")
            assertTrue(delete)
        }
    }
}
