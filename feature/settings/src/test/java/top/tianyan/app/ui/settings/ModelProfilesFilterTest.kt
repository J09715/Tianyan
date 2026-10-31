package top.tianyan.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import top.tianyan.app.core.database.AiModelEntity

class ModelProfilesFilterTest {
    private val models = listOf(
        AiModelEntity("a", "日常助手", "OpenAI", "gpt-4o,gpt-5-mini", isActive = true, createdAt = 0),
        AiModelEntity("b", "开发调试", "DeepSeek", "deepseek-chat", createdAt = 0),
    )
    @Test fun matchesProfileProviderAndIndividualModelIgnoringCase() {
        for (query in listOf("日常", " openai ", "5-MINI"))
            assertEquals(listOf(models[0]), filterModelProfiles(models, query, false))
    }
    @Test fun defaultFilterIsCombinedWithSearch() {
        assertEquals(listOf(models[0]), filterModelProfiles(models, "", true))
        assertEquals(emptyList<AiModelEntity>(), filterModelProfiles(models, "deepseek", true))
        assertEquals(models, filterModelProfiles(models, " ", false))
    }
}
