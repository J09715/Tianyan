package top.tianyan.app.core.database.repository

/** Bot 在每个群聊中使用的独立会话映射。 */
interface BotGroupSessionRepository {
    suspend fun sessionIdFor(botId: String, groupId: String): String?
    suspend fun groupIdForSession(sessionId: String): String?
    suspend fun botIdForSession(sessionId: String): String?
    suspend fun listSessionIdsForBot(botId: String): List<String>
    suspend fun listSessionIdsForGroup(groupId: String): List<String>
    suspend fun save(botId: String, groupId: String, sessionId: String)
}
