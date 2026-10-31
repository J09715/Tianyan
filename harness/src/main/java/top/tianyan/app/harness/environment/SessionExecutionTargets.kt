package top.tianyan.app.harness.environment

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.SessionExecutionTarget

/** Persist before allocation. Children resolve the parent's durable identity, never today's default. */
class SessionExecutionTargets(
    private val servers: RemoteServerRepository,
    private val sessions: HarnessSessionRepository? = null,
    private val localDistribution: suspend () -> String = { "debian" },
) {
    private val mutex = Mutex()
    private val transient = mutableMapOf<String, SessionExecutionTarget>()
    suspend fun capture(workspace: String): String = Json.encodeToString(SessionExecutionTarget.serializer(), selection(workspace, null))
    suspend fun capture(workspace: String, distribution: String?): String =
        Json.encodeToString(SessionExecutionTarget.serializer(), selection(workspace, distribution))
    suspend fun restore(owner: String, workspace: String, targetJson: String) = mutex.withLock {
        require(owner.startsWith("terminal:") && "::" !in owner) { "仅独立终端可恢复此执行目标" }
        val target = Json.decodeFromString(SessionExecutionTarget.serializer(), targetJson)
        target.validate()
        check(target.workspace == workspace) { "终端工作区与记录不一致" }
        check(transient[owner] == null || transient[owner] == target) { "终端已绑定其他执行环境" }
        transient[owner] = target
    }
    private suspend fun selection(workspace: String, inherited: String?): SessionExecutionTarget {
        val distribution = inherited ?: servers.activeId.value?.let { "ssh:$it" } ?: localDistribution()
        val remote = if (distribution.startsWith("ssh:")) servers.servers.value.singleOrNull { it.distributionId == distribution }
            ?: error("绑定的远程服务器不可用，请明确选择执行环境") else null
        return SessionExecutionTarget(distribution, workspace, remote).also { it.validate() }
    }

    suspend fun resolve(owner: String, workspace: String, inherited: String? = null): SessionExecutionTarget = mutex.withLock {
        val root = owner.substringBefore("::")
        val session = sessions?.findById(root)
        check(sessions == null || session != null || root.startsWith("mcp:") || root.startsWith("anonymous:") || root.startsWith("terminal:")) {
            "会话已移除或尚未保存，拒绝创建执行环境"
        }
        val saved = session?.executionTargetJson
        check(saved != "restore-required") { "导入的会话未记录执行环境，请新建会话以明确选择系统" }
        val target = saved?.takeUnless { it == "unbound" }?.let { Json.decodeFromString(SessionExecutionTarget.serializer(), it) }
            ?: transient[root] ?: run {
                // Before SSH existed all historical chats ran locally. Never use today's remote default.
                val selected = selection(workspace, if (saved == "unbound") localDistribution() else inherited)
                if (session != null) {
                    val encoded = Json.encodeToString(SessionExecutionTarget.serializer(), selected)
                    val stored = sessions!!.bindExecutionTarget(root, encoded) ?: error("会话已移除，拒绝绑定执行环境")
                    Json.decodeFromString(SessionExecutionTarget.serializer(), stored)
                } else selected.also { transient[root] = it }
            }
        target.validate()
        check(target.workspace == workspace) { "会话已绑定其他工作区，请新建会话" }
        check(inherited == null || inherited == target.distribution) { "子任务与父会话执行目标不一致" }
        target
    }
}
