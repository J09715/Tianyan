package top.tianyan.app.navigation

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.service.AgentForegroundService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AgentNotificationIntentExtractorTest {

    private val fakeRepo = object : BotGroupSessionRepository {
        override suspend fun sessionIdFor(botId: String, groupId: String): String? = null
        override suspend fun groupIdForSession(sessionId: String): String? =
            if (sessionId == "group_session_1") "group_tech" else null
        override suspend fun botIdForSession(sessionId: String): String? = null
        override suspend fun listSessionIdsForBot(botId: String): List<String> = emptyList()
        override suspend fun listSessionIdsForGroup(groupId: String): List<String> = emptyList()
        override suspend fun save(botId: String, groupId: String, sessionId: String) = Unit
    }

    @Test
    fun `extracts group target for group session notification`() = runBlocking {
        val intent = Intent().apply {
            data = Uri.parse("tianyan://agent-notification/group_session_1/open")
            putExtra(AgentForegroundService.EXTRA_SESSION_ID, "group_session_1")
        }
        val target = AgentNotificationIntentExtractor.extractTarget(intent, fakeRepo)
        assertNotNull(target)
        assertEquals("group_session_1", target?.sessionId)
        assertEquals("group_tech", target?.groupId)
    }

    @Test
    fun `extracts standalone session target for standalone notification`() = runBlocking {
        val intent = Intent().apply {
            data = Uri.parse("tianyan://agent-notification/standalone_session/open")
            putExtra(AgentForegroundService.EXTRA_SESSION_ID, "standalone_session")
        }
        val target = AgentNotificationIntentExtractor.extractTarget(intent, fakeRepo)
        assertNotNull(target)
        assertEquals("standalone_session", target?.sessionId)
        assertNull(target?.groupId)
    }

    @Test
    fun `summary notification without session returns all with null ids`() = runBlocking {
        val intent = Intent().apply {
            data = Uri.parse("tianyan://agent-notification/all/open")
        }
        val target = AgentNotificationIntentExtractor.extractTarget(intent, fakeRepo)
        assertNotNull(target)
        assertNull(target?.sessionId)
        assertNull(target?.groupId)
    }

    @Test
    fun `non notification intent returns null`() = runBlocking {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        assertNull(AgentNotificationIntentExtractor.extractTarget(intent, fakeRepo))
        assertNull(AgentNotificationIntentExtractor.extractTarget(null, fakeRepo))
    }
}
