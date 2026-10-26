package top.tianyan.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.ui.components.MainDestination

class MainPageNavigationTest {
    @Test fun coveringPagesDisableSidebarSwipesUntilTheyReturnToTheChatRoot() {
        for (page in listOf(SettingsDestination, AppearanceSettingsDestination, WorkspaceDestination,
            HomeDestination, TerminalDestination(), PluginManagerDestination)) {
            val stack = mutableListOf<NavKey>(AgentDestination)
            assertTrue(stack.canSwipeOpenNavigation())
            stack.add(page)
            assertFalse("Sidebar swipe must stay disabled over $page", stack.canSwipeOpenNavigation())
            stack.removeAt(stack.lastIndex)
            assertTrue(stack.canSwipeOpenNavigation())
        }
        assertFalse(emptyList<NavKey>().canSwipeOpenNavigation())
    }

    @Test fun projectAndExplorerReturnThroughTheOriginalChatHistory() {
        val stack = mutableListOf<NavKey>(AgentDestination)
        assertTrue(stack.openMainOverlay(MainDestination.Workspace))
        stack.add(WorkspaceExplorerDestination("demo"))
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(AgentDestination, WorkspaceDestination), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(AgentDestination), stack)
    }

    @Test fun systemPreservesTheCallingPageAndDoesNotDuplicateTheTopEntry() {
        val stack = mutableListOf<NavKey>(AgentDestination, ToolCenterDestination)
        assertTrue(stack.openMainOverlay(MainDestination.Home))
        assertTrue(stack.openMainOverlay(MainDestination.Home))
        assertEquals(listOf(AgentDestination, ToolCenterDestination, HomeDestination), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(ToolCenterDestination, stack.last())
    }

    @Test fun settingsAndItsSubpagesPopBackToTheCallingPage() {
        val stack = mutableListOf<NavKey>(AgentDestination, WorkspaceDestination)
        assertTrue(stack.openMainOverlay(MainDestination.Settings))
        stack.add(AppearanceSettingsDestination)
        stack.removeAt(stack.lastIndex)
        assertEquals(SettingsDestination, stack.last())
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(AgentDestination, WorkspaceDestination), stack)
    }

    @Test fun reopeningSettingsReturnsToItsExistingRootWithoutDuplicatingHistory() {
        val stack = mutableListOf<NavKey>(AgentDestination, SettingsDestination, AppearanceSettingsDestination)
        assertTrue(stack.openMainOverlay(MainDestination.Settings))
        assertTrue(stack.openMainOverlay(MainDestination.Settings))
        assertEquals(listOf(AgentDestination, SettingsDestination), stack)
        stack.removeAt(stack.lastIndex)
        assertEquals(listOf(AgentDestination), stack)
    }

    @Test fun settingsDeepLinksKeepAReversiblePathAndIgnoreRepeatedDelivery() {
        val stack = mutableListOf<NavKey>(AgentDestination)
        stack.openSettingsPath(SystemDevSettingsDestination, AdbLogcatDestination)
        stack.openSettingsPath(SystemDevSettingsDestination, AdbLogcatDestination)
        assertEquals(listOf(AgentDestination, SettingsDestination, SystemDevSettingsDestination, AdbLogcatDestination), stack)
        stack.openSettingsPath(AgentSettingsDestination)
        assertEquals(listOf(AgentDestination, SettingsDestination, AgentSettingsDestination), stack)
    }

    @Test fun chatKeepsItsReturnToConversationHandling() {
        val stack = mutableListOf<NavKey>(AgentDestination)
        assertFalse(stack.openMainOverlay(MainDestination.Agent))
        assertEquals(listOf(AgentDestination), stack)
    }
}
