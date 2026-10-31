package top.tianyan.app.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelEditorCapabilitiesTest {
    @Test fun mixedModelsKeepVisionRegardlessOfSelectionOrder() {
        assertTrue(profileVisionInput(listOf("deepseek-chat", "gpt-4o")))
        assertTrue(profileVisionInput(listOf("gpt-4o", "deepseek-chat")))
    }

    @Test fun onlyExplicitlyTextOnlyProfilesDisableVision() {
        assertFalse(profileVisionInput(listOf("deepseek-chat", " deepseek-reasoner ", "")))
        assertTrue(profileVisionInput(listOf("deepseek-chat", "custom-vision-model")))
        assertTrue(profileVisionInput(emptyList()))
        assertTrue(profileVisionInput(listOf(" ")))
    }
}
