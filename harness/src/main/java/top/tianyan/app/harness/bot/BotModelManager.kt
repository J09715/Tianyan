package top.tianyan.app.harness.bot

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.model.bot.Bot

/** Shared by member settings and self-service tools; never exposes credentials or changes global activation. */
class BotModelManager(
    private val models: AiModelRepository,
    private val bots: PersonaBotRepository,
    private val sessions: HarnessSessionRepository,
    private val groups: BotGroupSessionRepository,
    private val assignments: BotAssignmentRepository,
) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    internal suspend fun <T> withBotLock(botId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(botId) { Mutex() }.withLock { block() }

    suspend fun catalog() = models.observeAll().first()

    suspend fun validate(profileId: String?, variant: String?): Pair<String?, String?> {
        val id = profileId?.trim()?.takeIf(String::isNotBlank)
        val requested = variant?.trim()?.takeIf(String::isNotBlank)
        if (id == null) {
            require(requested == null) { "请先选择已添加的供应商档案" }
            return null to null
        }
        val profile = requireNotNull(models.findById(id)) { "供应商档案已不存在，请重新选择" }
        val canonical = requested?.let { value ->
            requireNotNull(profile.model.split(',').map(String::trim).firstOrNull { it.equals(value, true) }) {
                "该供应商档案未添加模型：$value"
            }
        }
        return profile.id to canonical
    }

    suspend fun select(botId: String, profileId: String?, variant: String?): Bot = withBotLock(botId) {
        val current = requireNotNull(bots.getBot(botId)) { "成员不存在" }
        check(current.allowModelSelection) { "模型已锁定，请由用户在成员详情中开启「允许成员自行切换模型」" }
        val (id, concrete) = validate(profileId, variant)
        val updated = current.copy(modelId = id, modelVariant = concrete, providerId = null, updatedAt = System.currentTimeMillis())
        bots.saveBot(updated)
        syncSessions(updated)
        updated
    }

    suspend fun syncSessions(bot: Bot) {
        val ids = listOf(bot.sessionId) + groups.listSessionIdsForBot(bot.id) +
            assignments.assignmentsForBot(bot.id).filter { it.independent }.mapNotNull { it.sessionId }
        ids.distinct().forEach { id ->
            sessions.setModelSelection(id, bot.modelId, bot.modelVariant, bot.updatedAt)
        }
    }
}
