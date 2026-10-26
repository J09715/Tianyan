package top.tianyan.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Immutable node in a session conversation tree. */
@Entity(
    tableName = "harness_entries",
    indices = [
        Index(value = ["id"], unique = true),
        Index(value = ["sessionId", "sequence"]),
        Index(value = ["sessionId", "parentId"]),
    ],
)
data class HarnessEntryEntity(
    @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
    val id: String,
    val sessionId: String,
    val parentId: String?,
    val createdAt: Long,
    /** message | compaction | branch_summary | custom */
    val entryType: String,
    /** user | assistant | tool_call | tool_result, or an application custom type. */
    val customType: String? = null,
    val payloadJson: String,
    /**
     * payload 的格式版本（借鉴 DSH session format version）。
     * 写入方恒为当前版本；读取方对更高版本按腐坏处理（应用降级）、更低版本走相邻迁移链。
     * 旧数据迁移时统一回填 1（ = 当前格式）。
     */
    @ColumnInfo(defaultValue = "1") val payloadVersion: Int = 1,
)

/**
 * 用量统计的 SQL 聚合行（按会话 + 消息类型分组）。
 *
 * 存在的意义：避免把整张 harness_entries（含巨大 payloadJson）读进内存造成 OOM。
 * 所有数值均在 SQLite 侧用 json_extract / LENGTH 算出，Kotlin 只接收几十行标量。
 */
data class UsageAggregateRow(
    val sessionId: String,
    val customType: String?,
    val entryCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val textChars: Long,
    val reasoningChars: Long,
)

/**
 * 按「本地日 + 会话 + 类型」聚合的用量行（供热力图/趋势使用，同样不携带 payloadJson）。
 *
 * [localEpochDay] 是把 `createdAt` 加上调用方传入的时区偏移后再按 86400000ms 取整，
 * 与 `Instant.atZone(zone).toLocalDate().toEpochDay()` 同口径（同一偏移下）。
 * Token 列与 [UsageAggregateRow] 使用同一套 json_extract + json_valid 守卫。
 */
data class DailyCountRow(
    val localEpochDay: Long,
    val sessionId: String,
    val customType: String?,
    val entryCount: Int,
    val promptTokens: Long,
    val completionTokens: Long,
    val cachedTokens: Long,
    val textChars: Long,
    val reasoningChars: Long,
)
