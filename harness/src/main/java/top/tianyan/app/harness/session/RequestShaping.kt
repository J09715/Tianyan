package top.tianyan.app.harness.session

import top.tianyan.app.harness.ApiMessage

/**
 * 请求整形：把「本轮变化的内容」放到消息序列末尾追加，
 * 保证跨轮请求互为**字节前缀**。
 *
 * ## 为什么这个不变式是硬要求
 *
 * Provider 的前缀缓存按精确前缀匹配计费：请求 N 与 N+1 只要前 k 个字节相同，
 * 这 k 个字节就走缓存价；从第一个不同字节起，其后**全部**按原价重新 prefill。
 *
 * 所以真正要守的不变式不是「system prompt 稳定」，而是：
 *
 * > 第 N+1 轮的请求，其前 |请求 N| 个字节必须与请求 N 完全一致
 * > （除非这一轮明确做了压缩重写）。
 *
 * 只把 system prompt 拆成 frozen/dynamic 是不够的 —— 如果动态内容被合并进
 * 某条**历史消息**（例如"最后一条 user 消息"），那条消息下一轮就变成历史消息，
 * 重新计算时字节与上一轮不同，断裂点只是从位置 0 搬到了那里，缓存依然全失效。
 *
 * ## 正确做法（对齐 DSH 的 SystemPromptProjection）
 *
 * - 历史消息**永不改写**：上一轮发出去是什么字节，这一轮还是什么字节；
 * - 本轮的变化内容作为**独立的新消息**追加在最末尾；
 * - 只有明确的路由切换/压缩才允许原地重写。
 *
 * 单调递增的追加序列天然满足前缀不变式：第 N+1 轮的前缀恰好是第 N 轮的全部内容。
 */
object RequestShaping {

    /**
     * 把 [tail] 作为独立消息追加到 [messages] 末尾。
     *
     * 刻意**不修改** [messages] 中任何已有元素 —— 那正是破坏前缀不变式的做法。
     * [tail] 为空时不产生任何消息（避免追加空块导致字节与上一轮不同）。
     */
    fun appendTail(messages: List<ApiMessage>, tail: String): List<ApiMessage> {
        if (tail.isBlank()) return messages
        return messages + ApiMessage(role = "system", content = tail)
    }

    /**
     * 前缀不变式校验：判断 [current] 的前 |previous| 条消息是否与 [previous] 逐字节相同。
     *
     * 供测试与诊断使用。正常情况下（无压缩重写）应恒为 true；
     * 一旦为 false，说明某轮改写了历史消息，缓存从该点起全部失效。
     */
    fun preservesPrefix(previous: List<ApiMessage>, current: List<ApiMessage>): Boolean {
        if (previous.size > current.size) return false
        return previous.indices.all { i -> identical(previous[i], current[i]) }
    }

    private fun identical(a: ApiMessage, b: ApiMessage): Boolean =
        a.role == b.role &&
            a.content == b.content &&
            a.reasoning_content == b.reasoning_content &&
            a.tool_call_id == b.tool_call_id &&
            a.imageUrls == b.imageUrls &&
            a.tool_calls == b.tool_calls
}
