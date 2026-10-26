package top.tianyan.app.core.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.repository.RoomBotMemberGroupRepository
import top.tianyan.app.core.model.bot.BotMemberGroup

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotMemberGroupPersistenceTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
        .allowMainThreadQueries().build()
    private val bots = db.personaBotDao()
    private val groups = db.botMemberGroupDao()
    private val repository = RoomBotMemberGroupRepository(groups)

    @After fun close() = db.close()

    private fun bot(id: String) = BotEntity(
        id, id, "role", "persona", "seed", null, null, "session-$id", false, "#3A5AFE", 1, 1,
    )

    @Test fun `saving a group replaces ordered membership atomically`() = runBlocking {
        bots.upsert(bot("a"))
        bots.upsert(bot("b"))
        bots.upsert(bot("c"))

        repository.saveGroup(BotMemberGroup("g", "开发组", 1, listOf("b", "a")))
        assertEquals(listOf("b", "a"), groups.listMembers("g").map { it.botId })
        assertEquals(listOf("b", "a"), repository.observeGroups().first().single().memberIds)

        repository.saveGroup(repository.observeGroups().first().single().copy(memberIds = listOf("a", "c")))
        assertEquals(listOf("a", "c"), groups.listMembers("g").map { it.botId })
        assertEquals("开发组", repository.observeGroups().first().single().name)
    }

    @Test fun `a bot can belong to multiple groups and deletion cleans every membership`() = runBlocking {
        bots.upsert(bot("a"))
        bots.upsert(bot("b"))
        repository.saveGroup(BotMemberGroup("g1", "开发组", 1, listOf("a", "b")))
        repository.saveGroup(BotMemberGroup("g2", "夜班组", 2, listOf("a")))

        bots.deleteById("a")

        assertEquals(listOf("b"), groups.listMembers("g1").map { it.botId })
        assertTrue(groups.listMembers("g2").isEmpty())
        assertNotNull(bots.findById("b"))
    }

    @Test fun `deleting a group keeps its bots`() = runBlocking {
        bots.upsert(bot("a"))
        repository.saveGroup(BotMemberGroup("g", "临时组", 1, listOf("a")))

        repository.deleteGroup("g")

        assertTrue(groups.observeGroups().first().isEmpty())
        assertTrue(groups.listMembers("g").isEmpty())
        assertNotNull(bots.findById("a"))
    }
}
