package top.tianyan.app.harness.session

import top.tianyan.app.core.database.HarnessEntryEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话条目 payload 的当前格式版本（借鉴 DSH 的 session format version）。
 *
 * 语义：
 * - 写入方永远落 [CURRENT]；`payloadVersion` 列是 entry 级的格式声明；
 * - 读取方遇 `payloadVersion > CURRENT`（应用被降级）按腐坏处理，绝不带着未知格式盲解；
 * - `payloadVersion < CURRENT` 时经 [SessionPayloadMigrations] 的相邻迁移链逐版升级后再解码。
 *
 * 迁移链规则（对齐 DSH 的 N→N+1 相邻边）：每步只跨一个版本，链上不允许有缺口；
 * 读到无法到达 CURRENT 的版本即失败。目前 CURRENT = 1（链为空），首次改格式时
 * 新增一个 Step 并把 CURRENT 提到 2。
 */
object HarnessEntryPayloadVersion {
    const val CURRENT: Int = 1
}

/** 单个 N→N+1 的 payload 迁移步骤；[transform] 必须是纯函数（旧 JSON → 新 JSON）。 */
class PayloadMigrationStep(
    val fromVersion: Int,
    val toVersion: Int,
    private val transform: (payloadJson: String) -> String,
) {
    init {
        require(toVersion == fromVersion + 1) { "迁移步骤必须相邻：$fromVersion -> $toVersion" }
    }

    fun apply(payloadJson: String): String = transform(payloadJson)
}

/**
 * 相邻迁移链：`upgrade(payload, from, to)` 逐版应用，链上缺边即抛 [NoSuchElementException]。
 * 默认无步骤（CURRENT=1）；测试可注入合成步骤验证链语义。
 */
class SessionPayloadMigrations(private val steps: List<PayloadMigrationStep> = emptyList()) {

    fun upgrade(payloadJson: String, fromVersion: Int, toVersion: Int = HarnessEntryPayloadVersion.CURRENT): String {
        require(toVersion >= fromVersion) { "目标版本不可低于来源版本：$fromVersion -> $toVersion" }
        var current = payloadJson
        var version = fromVersion
        while (version < toVersion) {
            val step = steps.firstOrNull { it.fromVersion == version }
                ?: throw NoSuchElementException("缺少 $version -> ${version + 1} 的迁移步骤")
            current = step.apply(current)
            version++
        }
        return current
    }
}

/** 一条被跳过投影的腐坏条目记录（解码失败 / 版本不可识别）。 */
data class CorruptEntryRecord(
    val sessionId: String,
    val entryId: String,
    val entryType: String,
    val payloadVersion: Int,
    val cause: String,
    val recordedAt: Long = System.currentTimeMillis(),
)

/**
 * 腐坏条目台账（借鉴 DSH 的 fail-closed：读不懂的条目绝不静默消失）。
 *
 * 投影侧仍然跳过无法解码的条目（移动端不能因单条坏数据拒绝整段会话加载），
 * 但每次跳过都在此登记并可查询——「静默丢史」升级为「显式丢史」。
 * 纯内存、进程级：诊断用途，不参与持久化与恢复。
 */
class SessionCorruptionLedger(
    private val maxSessions: Int = 64,
    private val maxRecordsPerSession: Int = 32,
) {
    private val records = ConcurrentHashMap<String, ArrayDeque<CorruptEntryRecord>>()

    fun record(entry: HarnessEntryEntity, cause: String) {
        val deque = records.getOrPut(entry.sessionId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(
                CorruptEntryRecord(
                    sessionId = entry.sessionId,
                    entryId = entry.id,
                    entryType = entry.entryType,
                    payloadVersion = entry.payloadVersion,
                    cause = cause,
                ),
            )
            while (deque.size > maxRecordsPerSession) deque.removeFirst()
        }
        while (records.size > maxSessions) {
            // 淘汰最近一次登记最早的会话；队列已被并发清空的跳过
            val oldest = records.entries
                .filter { it.value.isNotEmpty() }
                .minByOrNull { deque -> synchronized(deque.value) { deque.value.last().recordedAt } }
                ?: break
            records.remove(oldest.key, oldest.value)
        }
    }

    /** 某会话的腐坏记录（登记顺序，最多 [maxRecordsPerSession] 条）。 */
    fun snapshotFor(sessionId: String): List<CorruptEntryRecord> =
        records[sessionId]?.let { deque -> synchronized(deque) { deque.toList() } }.orEmpty()

    fun clearSession(sessionId: String) {
        records.remove(sessionId)
    }

    fun totalSessions(): Int = records.size
}
