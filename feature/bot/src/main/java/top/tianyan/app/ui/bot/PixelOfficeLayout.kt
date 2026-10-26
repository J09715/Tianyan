package top.tianyan.app.ui.bot

import top.tianyan.app.core.model.bot.Bot
import top.tianyan.app.core.model.bot.BotAvatarState

/** Where a bot stands in the pixel office, in room pixels. Feet sit at [x], [y]. */
internal enum class OfficePose { STAND, SIT, TYPE, THINK }

/** Small, deterministic idle business so a waiting bot never looks frozen. */
internal enum class OfficeIdleAction { NONE, SIP, READ, STRETCH, PHONE, DOZE }

internal enum class OfficeSeatGroup { WORK, MEET, THINK, REST, COFFEE, FLOOR }

internal data class OfficeSeat(
    val id: String,
    val x: Int,
    val y: Int,
    val pose: OfficePose,
    val group: OfficeSeatGroup = OfficeSeatGroup.FLOOR,
    val poses: Set<OfficePose> = setOf(pose),
    val idleAction: OfficeIdleAction = OfficeIdleAction.NONE,
    val roamOffset: OfficeAnchor? = null,
)

internal data class OfficeActor(
    val botId: String,
    val name: String,
    val x: Int,
    val y: Int,
    val pose: OfficePose,
    val state: BotAvatarState,
    val waitSeconds: Int? = null,
    val idleAction: OfficeIdleAction = OfficeIdleAction.NONE,
)


internal data class OfficeWait(val sessionId: String, val createdAt: Long, val expiresAt: Long)

/** Shifts each bot onto its own beat so the room does not breathe in unison. */
internal fun officeFrameOffset(botId: String): Int = (botId.hashCode() and Int.MAX_VALUE) % 3

/**
 * Assigns each member a stable spot in the office. Active bots take the
 * monitors first, then the meeting-table hot desks; idle bots take sofas and
 * the rug; overflow uses the named aisle spots before the walkway fallback.
 */
internal object PixelOfficeLayout {
    fun place(
        members: List<Bot>,
        states: Map<String, BotAvatarState>,
        now: Long = 0L,
        standup: Set<String> = emptySet(),
        scene: OfficeSceneSpec = OfficeSceneSpec.Default,
    ): List<OfficeActor> {
        val seats = scene.allSeats.groupBy { it.group }
        val desks = seats[OfficeSeatGroup.WORK].orEmpty()
        val meeting = seats[OfficeSeatGroup.MEET].orEmpty()
        val board = seats[OfficeSeatGroup.THINK].orEmpty()
        val lounge = seats[OfficeSeatGroup.REST].orEmpty()
        val coffee = seats[OfficeSeatGroup.COFFEE].orEmpty()
        val floor = seats[OfficeSeatGroup.FLOOR].orEmpty()
        val namedIds = scene.allSeats.map { it.id }.toSet()
        val used = mutableSetOf<String>()
        var overflow = 0
        return members.sortedWith(
            compareBy({ if (it.id in standup) -1 else statePriority(states[it.id] ?: BotAvatarState.IDLE) }, { it.id }),
        )
            .map { bot ->
                val state = states[bot.id] ?: BotAvatarState.IDLE
                val inStandup = bot.id in standup
                val wanted = if (inStandup) OfficePose.SIT else poseFor(state)
                val preferred = if (inStandup) meeting else when (state) {
                    BotAvatarState.THINKING -> board
                    BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> desks
                    BotAvatarState.BLOCKED, BotAvatarState.FAILED -> coffee
                    BotAvatarState.IDLE, BotAvatarState.SUCCESS -> lounge
                    else -> floor
                }
                val fallback = if (inStandup) meeting + floor else when (state) {
                    BotAvatarState.THINKING,
                    BotAvatarState.EXECUTING,
                    BotAvatarState.ACTIVE,
                    -> meeting + floor
                    BotAvatarState.IDLE,
                    BotAvatarState.SUCCESS,
                    -> meeting + coffee + floor
                    else -> floor
                }
                val seat = (preferred + fallback).firstOrNull { it.id !in used } ?: run {
                    val anchor = scene.overflow.anchor(overflow++)
                    var index = used.size
                    while ("extra-$index" in namedIds || "extra-$index" in used) index++
                    OfficeSeat("extra-$index", anchor.x, anchor.y, OfficePose.STAND)
                }
                used += seat.id
                val roam = if (inStandup) null else idleRoam(seat, bot.id, state, now)
                val pose = if (roam != null) OfficePose.STAND else resolvePose(wanted, seat)
                OfficeActor(
                    botId = bot.id,
                    name = bot.name,
                    x = roam?.first ?: seat.x,
                    y = roam?.second ?: seat.y,
                    pose = pose,
                    state = state,
                    idleAction = if (inStandup || roam != null) OfficeIdleAction.NONE
                    else idleAction(bot, state, seat, now),
                )
            }
    }

    /** Marks bots whose session is waiting on an approval or a user question. */
    fun applyWaiting(
        actors: List<OfficeActor>,
        sessionOf: Map<String, String>,
        waits: List<OfficeWait>,
        now: Long,
    ): List<OfficeActor> {
        if (waits.isEmpty()) return actors.map { it.copy(waitSeconds = null) }
        return actors.map { actor ->
            val session = sessionOf[actor.botId]
            val matched = waits.filter { it.sessionId == session }
            if (session == null || matched.isEmpty()) actor.copy(waitSeconds = null)
            else actor.copy(waitSeconds = matched.minOf { waitSeconds(it, now) })
        }
    }

    fun waitSeconds(wait: OfficeWait, now: Long): Int {
        val remaining = ((wait.expiresAt - now) / 1000L).toInt()
        if (wait.expiresAt == Long.MAX_VALUE || remaining > 24 * 3600) {
            return (60 - ((now - wait.createdAt) / 1000L).toInt()).coerceIn(0, 60)
        }
        return remaining.coerceAtLeast(0)
    }

    /** At most two speech bubbles: the selected bot, then waiting-approval first, then whoever is busiest. */
    fun speakerIds(actors: List<OfficeActor>, selectedId: String?): List<String> {
        if (actors.isEmpty()) return emptyList()
        // 等审批的成员最需要被看到（DS Bot 状态优先级：等批准 > 在干活 > 空闲）。
        val busy = actors.filter { it.state != BotAvatarState.IDLE || it.waitSeconds != null }
            .sortedWith(compareBy({ if (it.waitSeconds != null) -1 else 0 }, { stateRank(it.state) }))
            .map { it.botId }
        val ordered = buildList {
            if (selectedId != null && actors.any { it.botId == selectedId }) add(selectedId)
            busy.forEach { if (it !in this) add(it) }
            if (isEmpty()) add(actors.first().botId)
        }
        return ordered.take(2)
    }
    private fun poseFor(state: BotAvatarState): OfficePose = when (state) {
        BotAvatarState.THINKING -> OfficePose.THINK
        BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> OfficePose.TYPE
        BotAvatarState.IDLE, BotAvatarState.SUCCESS -> OfficePose.SIT
        BotAvatarState.BLOCKED, BotAvatarState.FAILED -> OfficePose.STAND
        else -> OfficePose.STAND
    }

    private fun resolvePose(wanted: OfficePose, seat: OfficeSeat): OfficePose = when {
        wanted in seat.poses -> wanted
        wanted == OfficePose.THINK && OfficePose.SIT in seat.poses -> OfficePose.SIT
        else -> OfficePose.STAND
    }

    private fun idleAction(
        bot: Bot,
        state: BotAvatarState,
        seat: OfficeSeat,
        now: Long,
    ): OfficeIdleAction {
        if (state != BotAvatarState.IDLE) return OfficeIdleAction.NONE
        if (seat.idleAction != OfficeIdleAction.NONE) return seat.idleAction
        if (now <= 0L) return OfficeIdleAction.NONE
        val slot = (((now / 9_000L) + (bot.id.hashCode() and Int.MAX_VALUE)) % 6L).toInt()
        return when (slot) {
            0 -> OfficeIdleAction.SIP
            1 -> OfficeIdleAction.READ
            2 -> OfficeIdleAction.STRETCH
            3 -> OfficeIdleAction.PHONE
            4 -> OfficeIdleAction.DOZE
            else -> OfficeIdleAction.NONE
        }
    }

    /** A short, deterministic walk to a nearby aisle during an idle cycle. */
    private fun idleRoam(
        seat: OfficeSeat,
        botId: String,
        state: BotAvatarState,
        now: Long,
    ): Pair<Int, Int>? {
        if (state != BotAvatarState.IDLE || now <= 0L) return null
        val offset = seat.roamOffset ?: return null
        val phase = ((now / 1000L + (botId.hashCode() and Int.MAX_VALUE)) % 48L).toInt()
        return if (phase in 26..33) seat.x + offset.x to seat.y + offset.y else null
    }

    private fun statePriority(state: BotAvatarState): Int = when (state) {
        BotAvatarState.EXECUTING, BotAvatarState.ACTIVE -> 0
        BotAvatarState.THINKING -> 1
        BotAvatarState.BLOCKED, BotAvatarState.FAILED -> 2
        BotAvatarState.SUCCESS -> 3
        BotAvatarState.IDLE -> 4
    }

    private fun stateRank(state: BotAvatarState): Int = when (state) {
        BotAvatarState.ACTIVE -> 0
        BotAvatarState.EXECUTING -> 1
        BotAvatarState.THINKING -> 2
        BotAvatarState.BLOCKED -> 3
        BotAvatarState.FAILED -> 4
        BotAvatarState.SUCCESS -> 5
        BotAvatarState.IDLE -> 6
    }
}
