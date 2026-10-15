package top.tianyan.app.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 内置快捷短语的初始化与升级补齐。
 *
 * 背景：ensureInitialized() 原先只在 count() == 0 时写默认集。已装用户的库里
 * 早就有行，新增的内置短语（本次是红队演练那几条）永远插不进去——设置页多出一个
 * 「红队演练」分类，点进去却一条都没有。这里锁死两条语义：空库写全量，
 * 老库只补缺失 id 且不动用户已有行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuickPhraseInitializationTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomQuickPhraseRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomQuickPhraseRepository(database.quickPhraseDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun emptyDatabaseGetsEveryDefaultPhrase() = runBlocking {
        repository.ensureInitialized()
        val ids = repository.getAll().map { it.id }
        assertEquals(RoomQuickPhraseRepository.defaultQuickPhrases.size, ids.size)
        assertTrue("red-team presets missing", ids.contains("builtin_redteam_preflight"))
    }

    /** 红队演练的预设必须挂到 RED_TEAM，否则在红队会话里一条都不会被匹配出来。 */
    @Test
    fun redTeamPresetsAreScopedToRedTeamSessions() {
        val redTeam = RoomQuickPhraseRepository.defaultQuickPhrases.filter {
            it.targetProjectType == "RED_TEAM"
        }
        assertTrue("no red-team presets declared", redTeam.size >= 4)
        redTeam.forEach { phrase ->
            assertTrue("blank content: ${phrase.id}", phrase.content.isNotBlank())
            assertTrue("blank description: ${phrase.id}", phrase.description.isNotBlank())
        }
    }

    /** 老库升级：只补缺失的内置项，用户改过的行与禁用状态都不能被覆盖。 */
    @Test
    fun upgradeAddsOnlyMissingBuiltinsAndKeepsUserEdits() = runBlocking {
        val dao = database.quickPhraseDao()
        dao.upsertAll(
            listOf(
                QuickPhraseEntity.fromDomain(
                    RoomQuickPhraseRepository.defaultQuickPhrases.first { it.id == "builtin_run" }
                        .copy(content = "/run --user-edited", isEnabled = false),
                ),
            ),
        )

        repository.ensureInitialized()

        val all = repository.getAll()
        assertEquals(RoomQuickPhraseRepository.defaultQuickPhrases.size, all.size)
        val run = all.first { it.id == "builtin_run" }
        assertEquals("/run --user-edited", run.content)
        assertTrue("disabled state must survive upgrade", !run.isEnabled)
        assertTrue(all.any { it.id == "builtin_redteam_report" })
    }

    /** 重复调用必须幂等：每次启动都会跑，多插一次就多一条重复项。 */
    @Test
    fun repeatedInitializationIsIdempotent() = runBlocking {
        repository.ensureInitialized()
        val first = repository.getAll().size
        repository.ensureInitialized()
        assertEquals(first, repository.getAll().size)
    }

    /** 内置短语 id 不得重复：重复 id 在 upsertAll 下会互相覆盖，静默少一条。 */
    @Test
    fun builtinIdsAreUnique() {
        val ids = RoomQuickPhraseRepository.defaultQuickPhrases.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
