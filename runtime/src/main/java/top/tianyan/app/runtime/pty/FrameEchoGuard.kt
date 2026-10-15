package top.tianyan.app.runtime.pty

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * PTY 行规程回显防护：识别并吞掉「宿主自己写出去、又被内核回显读回来」的帧。
 *
 * 协议会话（ACP / MCP STDIO）的读循环把每一行都当作引擎的输出解析。当 PTY 的
 * termios 没被关成 raw 时——native forkpty 库加载失败退到 script 后端、guest 内
 * stty 不可用、或某些内核行为差异——宿主写进 master 的 JSON 请求帧会被行规程
 * 原样回显。读循环拿到它，看到带 `method` 就当成引擎主动发起的反向请求，回一条
 * -32601；那条错误响应再被回显、id 命中挂起的请求，握手就此失败。
 *
 * 这是第二道防线。[SessionConfig.rawTerminal] 是第一道，负责从根上关掉 ECHO；
 * 这里保证即便第一道在某个后端上没落地，协议层也不会被自己的回显打穿。
 *
 * 只比对最近 [capacity] 帧：引擎恰好复述同一段 JSON 的概率极低，且容量有限，
 * 不会长期误杀。
 */
class FrameEchoGuard(private val capacity: Int = DEFAULT_CAPACITY) {

    private val recent = ConcurrentLinkedQueue<String>()

    /** 写帧前登记，供后续读到的行做比对。 */
    fun record(payload: String) {
        recent.add(payload)
        while (recent.size > capacity) {
            recent.poll()
        }
    }

    /**
     * 命中即说明该行是刚写出去的帧被回显，调用方必须直接丢弃。
     * 命中后会移除该条目，一条回显只被吞一次。
     */
    fun isEcho(line: String): Boolean = recent.remove(line)

    companion object {
        const val DEFAULT_CAPACITY = 64
    }
}
