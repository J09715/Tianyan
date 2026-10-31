package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAssignment

/**
 * A short visual kickoff for a parallel dispatch: when two or more assignments
 * start in the same wave, the assignees and the main bot sit at the meeting
 * table and take turns showing what they own.
 */
internal data class OfficeStandup(
    val ids: List<String>,
    val speakerId: String?,
    val titles: Map<String, String>,
    val hostId: String?,
)

internal fun officeStandup(
    assignments: List<BotAssignment>,
    members: List<Bot>,
    now: Long,
): OfficeStandup {
    val recent = assignments.filter { task ->
        task.status.isActive && now - task.startedAt in 0..STANDUP_WINDOW_MS
    }
    if (recent.size < 2) return OfficeStandup(emptyList(), null, emptyMap(), null)
    val newest = recent.maxOf { it.startedAt }
    val oldest = recent.minOf { it.startedAt }
    if (newest - oldest > STANDUP_SPREAD_MS) return OfficeStandup(emptyList(), null, emptyMap(), null)
    val memberIds = members.map { it.id }.toSet()
    val hostId = members.firstOrNull { it.isMainBot }?.id
    val assigned = recent.filter { it.botId in memberIds }
        .sortedByDescending { it.startedAt }
        .map { it.botId }
        .distinct()
        .take(MAX_MEETING)
    if (assigned.size < 2) return OfficeStandup(emptyList(), null, emptyMap(), null)
    val ids = (listOfNotNull(hostId) + assigned).distinct().take(MAX_MEETING)
    if (ids.size < 2) return OfficeStandup(emptyList(), null, emptyMap(), null)
    val titles = ids.filter { it != hostId }.associateWith { botId ->
        assignments.filter { it.botId == botId }.maxByOrNull { it.startedAt }?.title.orEmpty()
    }
    val speaker = ids[((now / 2_000L) % ids.size).toInt()]
    return OfficeStandup(
        ids = ids,
        speakerId = speaker,
        titles = titles,
        hostId = hostId?.takeIf { it in ids },
    )
}

private const val STANDUP_WINDOW_MS = 9_000L
private const val STANDUP_SPREAD_MS = 5_000L
private const val MAX_MEETING = 4
