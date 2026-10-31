package top.tianyan.app.core.database

/**
 * 会话红队绑定的写入入口。
 *
 * 写成**顶层扩展**而不是接口成员：`HarnessSessionRepository` 所在文件已到行数棘轮上限，
 * 而这段逻辑只依赖接口已有的 [HarnessSessionRepository.findById] /
 * [HarnessSessionRepository.upsert]，不需要成为接口契约的一部分
 * （否则每个测试替身都得实现它）。
 *
 * 一次写入 mode/target/scope/phase 四列：分几次改会出现「目标已换、范围还是旧的」
 * 中间态，而后续所有越界校验都挂在这个范围上。
 */
suspend fun HarnessSessionRepository.updateRedTeamBinding(
    id: String,
    mode: String,
    target: String?,
    scope: String,
    phase: String,
    updatedAt: Long,
) {
    val session = findById(id) ?: return
    upsert(
        session.copy(
            redTeamMode = mode,
            redTeamTarget = target,
            redTeamScope = scope,
            redTeamPhase = phase,
            updatedAt = updatedAt,
        ),
    )
}
