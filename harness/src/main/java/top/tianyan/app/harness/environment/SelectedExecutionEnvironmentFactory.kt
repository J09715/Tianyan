package top.tianyan.app.harness.environment

import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.remote.SshConnector

/** Explicit inherited distributions take precedence over the current global selection. */
class SelectedExecutionEnvironmentFactory(
    private val local: ExecutionEnvironmentFactory,
    private val servers: RemoteServerRepository,
    private val connector: SshConnector,
    private val targets: SessionExecutionTargets = SessionExecutionTargets(servers),
) : ExecutionEnvironmentFactory {
    override suspend fun capture(workspace: String) = targets.capture(workspace)
    override suspend fun capture(workspace: String, distribution: String?) = targets.capture(workspace, distribution)
    override suspend fun restore(owner: String, workspace: String, targetJson: String) = targets.restore(owner, workspace, targetJson)
    override suspend fun bind(owner: String, workspace: String) { targets.resolve(owner, workspace) }
    override suspend fun open(owner: String, workspace: String, distribution: String?): ExecutionEnvironment {
        val target = targets.resolve(owner, workspace, distribution)
        val standaloneScope = owner.takeIf { it.startsWith("mcp:") }?.substringAfter("@environment:", "").orEmpty()
        if (standaloneScope.isNotEmpty()) check((target.remote?.let { top.tianyan.app.runtime.remote.remoteConnectionScope(it) }
                ?: "local:${target.distribution}") == standaloneScope) {
            "当前服务器已改变，请重新测试 MCP 连接"
        }
        val expected = target.remote ?: return local.open(owner, workspace, target.distribution)
        val (server, credentials) = servers.connection(expected.id)
        check(server == expected) { "绑定的连接档案已更改，请新建会话；不会切换到其他服务器" }
        // Project identifiers map beneath the configured remote root; phone absolute paths never cross over.
        val relative = workspace.removePrefix("/workspace").trim('/')
        require(!workspace.startsWith('/') || workspace == "/workspace" || workspace.startsWith("/workspace/")) { "非法远程项目路径" }
        require(relative.split('/').none { it == ".." } && '\u0000' !in relative)
        val cwd = server.workspace.trimEnd('/') + relative.takeIf { it.isNotBlank() }?.let { "/$it" }.orEmpty()
        return connector.openEnvironment(server, credentials, ExecutionEnvironmentId("ssh", target.distribution, cwd, owner), workspace.ifBlank { "/workspace" })
    }
}
