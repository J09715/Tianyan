package top.tianyan.app.runtime.webchat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Backward-compatible REST command parsing; no Harness dependency in runtime. */
internal object WebChatRunProtocol {
    data class Input(val text: String, val images: List<String>, val mode: WebChatInputMode)
    data class Failure(val status: Int, val message: String)

    fun failure(failure: Throwable): Failure = if (failure is IllegalArgumentException) {
        Failure(400, failure.message ?: "会话操作失败")
    } else {
        Failure(500, "会话操作失败")
    }

    fun parse(body: JsonObject): Input {
        val text = body["userMessage"]?.jsonPrimitive?.content.orEmpty()
        val images = body["attachments"]?.jsonArray.orEmpty().mapNotNull { item ->
            item.jsonObject["dataUrl"]?.jsonPrimitive?.content?.takeIf { it.startsWith("data:image/") }
        }
        require(text.isNotBlank() || images.isNotEmpty()) { "消息不能为空" }
        val modeId = body["inputMode"]?.jsonPrimitive?.content ?: WebChatInputMode.NEXT_RUN.id
        val mode = requireNotNull(WebChatInputMode.entries.find { it.id == modeId }) { "未知输入模式" }
        return Input(text, images, mode)
    }

    fun receiptJson(receipt: WebChatInputReceipt): JsonObject = buildJsonObject {
        put("disposition", receipt.disposition)
        receipt.taskId?.let { put("durableTaskId", it) }
        receipt.queue?.let { put("queue", it) }
        receipt.queueItemId?.let { put("queueItemId", it) }
    }
}
