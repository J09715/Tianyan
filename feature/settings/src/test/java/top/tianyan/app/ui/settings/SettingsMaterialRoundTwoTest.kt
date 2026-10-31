package top.tianyan.app.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.tianyan.app.core.database.AiModelEntity
import top.tianyan.app.core.model.AiModelProfileExport
import top.tianyan.app.core.model.AiProfileImportMode
import top.tianyan.app.feature.settings.R

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h800dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun searchEntryRemainsARealNavigationAction() {
        var searched = false
        render("round2-settings-home", height = 800, content = {
            CompositionLocalProvider(LocalInspectionMode provides true) {
            SettingsScreen(onNavigate = {}, onOpenAgentEco = {}, onOpenLinuxEnv = {}, onOpenAppearance = {},
                onOpenSystemDev = {}, onOpenAboutCommunity = {}, onOpenSearch = { searched = true }, viewModel = null)
            }
        }) { root ->
            click(root, RuntimeEnvironment.getApplication().getString(R.string.personal_search))
            assertTrue(searched)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h800dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelProfilesMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun searchAndDefaultFilterPreserveTheActivationTarget() {
        var activated: String? = null
        val profiles = listOf(
            AiModelEntity("openai", "日常助手", "OpenAI", "gpt-4o,gpt-5-mini", baseUrl = "https://api.openai.com/v1",
                isActive = true, createdAt = 0, contextTokens = 128_000),
            AiModelEntity("deepseek", "开发与调试", "DeepSeek", "deepseek-chat", baseUrl = "https://api.deepseek.com/v1",
                createdAt = 0, contextTokens = 128_000),
        )
        render("round2-model-profiles", dark = true, scale = 1.3f, width = 360, height = 800, content = {
            ModelProfilesContent(Modifier.fillMaxSize(), models = profiles, onCreate = {}, onEdit = {},
                onActivate = { activated = it }, onDelete = {}, onExportSingle = {})
        }) { root ->
            val defaultFilter = nodes(root).single {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "全局默认" } &&
                    (it.config.getOrNull(SemanticsProperties.Selected) != null ||
                        it.config.getOrNull(SemanticsProperties.ToggleableState) != null)
            }
            assertTrue(requireNotNull(defaultFilter.config.getOrNull(SemanticsActions.OnClick)?.action).invoke())
            settle()
            assertFalse(hasText(root, "开发与调试"))
            click(root, "全部")
            val search = nodes(root).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(search.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString("DEEPSEEK")))
            settle()
            click(root, "设为默认")
            assertEquals("deepseek", activated)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w393dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelImportMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun previewRemainsReadOnlyUntilTheSecondConfirmation() {
        val raw = "{\"fixture\":true}"
        var imported: Pair<String, AiProfileImportMode>? = null
        render("round2-model-import", content = {
            ModelImportDialog(models = emptyList(), onDismiss = {}, parseProfiles = {
                assertEquals(raw, it)
                Result.success(listOf(AiModelProfileExport(name = "离线导入测试", provider = "OpenAI", model = "gpt-4o")))
            }, onImportJson = { value, mode -> imported = value to mode })
        }) { root ->
            val input = nodes(root).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(input.config.getOrNull(SemanticsActions.SetText)?.action).invoke(AnnotatedString(raw)))
            settle()
            click(root, RuntimeEnvironment.getApplication().getString(R.string.profile_transfer_preview))
            assertNull(imported)
            assertTrue(hasText(root, "新增副本"))
            snapshot(root, 393, 700, "round2-model-import-preview")
            click(root, RuntimeEnvironment.getApplication().getString(R.string.profile_transfer_confirm))
            assertEquals(raw to AiProfileImportMode.COPY, imported)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h700dp-mdpi", manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModelExportMaterialRenderTest : ModelEditorRenderSupport() {
    @Test fun defaultExportExcludesCredentialsAndBusyStateDisablesDismissal() {
        val generated = CompletableDeferred<String>()
        val requested = CompletableDeferred<Boolean>()
        var credentials: Boolean? = null
        var dismissed = false
        render("round2-model-export", dark = true, scale = 1.3f, width = 360, height = 700, content = {
            ModelExportDialog("导出模型档案", "日常助手 · OpenAI", onDismiss = { dismissed = true }, onGenerateJson = {
                credentials = it
                requested.complete(it)
                generated.await()
            })
        }) { root ->
            val choice = nodes(root).single { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
            assertEquals(ToggleableState.Off, choice.config.getOrNull(SemanticsProperties.ToggleableState))
            click(root, "复制 JSON")
            assertEquals(false, runBlocking { withTimeout(5_000) { requested.await() } })
            assertEquals(false, credentials)
            val close = nodes(root).single { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("关闭") }
            assertNotNull(close.config.getOrNull(SemanticsProperties.Disabled))
            assertFalse(dismissed)
            generated.complete("{\"models\":[]}")
            repeat(100) { if (!dismissed) { Thread.sleep(10); settle() } }
            assertTrue(dismissed)
        }
    }
}
