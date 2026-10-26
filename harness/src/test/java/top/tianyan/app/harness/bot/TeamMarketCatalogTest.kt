package top.tianyan.app.harness.bot

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.bot.market.BotMarketRepository
import top.tianyan.app.harness.bot.market.GroupTeamTemplates

class TeamMarketCatalogTest {
    @Test fun `individual market omits imperial team while importer can resolve every template member`() {
        val individual = BotMarketRepository.curatedItems()
        assertFalse(individual.any { it.departmentId == "sanshengliubu" })
        assertTrue(individual.any { it.id == "market.product-manager" })
        val bundled = BotMarketRepository.curatedItems(includeTemplateMembers = true).associateBy { it.id }
        GroupTeamTemplates.all.forEach { template ->
            template.members.forEach { member -> assertNotNull("${template.id}: ${member.marketId}", bundled[member.marketId]) }
        }
        val imperial = GroupTeamTemplates.all.single { it.id == "sansheng" }
        assertEquals(10, imperial.members.size)
        assertEquals(10, imperial.members.map { bundled.getValue(it.marketId).id }.toSet().size)
        assertEquals(6, imperial.members.count { it.supervisorKey == "shangshu" })
    }
}
