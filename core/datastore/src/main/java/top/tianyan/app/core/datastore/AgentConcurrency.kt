package top.tianyan.app.core.datastore

/**
 * 全局并发 Agent 回合数的取值契约。
 *
 * 单独成文件而不是写在 SettingsDataStore.companion 里：那个文件已到行数棘轮上限，
 * 而这三个数字是**跨模块契约** —— 设置页、写入钳位、协调器读取三处必须一致，
 * 独立出来便于对照，也避免任何人只改一处造成「设置里能调、实际不生效」。
 *
 * 为什么上界是 10：对应「群聊里 10 个成员同时开工」这一真实场景。
 * 再高没有实际收益 —— 单机同时跑十几个回合，瓶颈会先落在 CPU/内存与上游 API 限流上，
 * 排队反而比硬并发更稳。
 */
object AgentConcurrency {
    /** 默认值：保守。移动端内存、网络与上游限流都吃紧，让用户按需上调。 */
    const val DEFAULT = 2
    const val MIN = 1
    const val MAX = 10
}
