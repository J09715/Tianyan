package top.tianyan.app.harness

import java.security.MessageDigest
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration.Companion.milliseconds

/**
 * 进程内的 Key 轮询与滑动窗口限流器。它只保留 Key 的 SHA-256 标识和请求时间，
 * 不持久化、记录或暴露 Key 明文。
 */
internal class ApiKeyScheduler(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    data class Selection(val key: String?, val waitMillis: Long = 0L)

    private data class KeyState(
        val requestTimes: ArrayDeque<Long> = ArrayDeque(),
        var cooldownUntil: Long = 0L,
    )

    private val states = mutableMapOf<String, KeyState>()
    private val cursors = mutableMapOf<String, Int>()
    // 会话 → 上次成功使用的 Key（LRU，访问即移到队尾）。多 Key 池下保持同一会话的上游身份稳定：
    // 逐轮换 Key 会击穿前缀缓存，一轮中途换服务商还会造成风格/能力漂移。
    private val sessionKeys = LinkedHashMap<String, String>()

    @Synchronized
    fun select(
        keys: List<String>,
        rpmLimit: Int,
        excluded: Set<String> = emptySet(),
        sessionKey: String? = null,
    ): Selection {
        val normalized = keys.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty() || normalized.all(excluded::contains)) return Selection(null)

        val now = nowMillis()
        val poolId = normalized.joinToString(separator = "|") { fingerprint(it) }
        // 粘性优先：本会话上次用过的 Key 仍在池内、未被排除、未冷却且本分钟未超限时直接复用。
        val sticky = sessionKey?.let { sessionKeys[it] }
        if (sticky != null && sticky in normalized && sticky !in excluded) {
            val state = states.getOrPut(fingerprint(sticky)) { KeyState() }
            if (state.cooldownUntil <= now) {
                pruneWindow(state, now)
                if (rpmLimit <= 0 || state.requestTimes.size < rpmLimit) {
                    state.requestTimes.addLast(now)
                    rememberSessionKey(sessionKey, sticky)
                    return Selection(sticky)
                }
            }
        }

        val start = (cursors[poolId] ?: 0).mod(normalized.size)
        var shortestWait = Long.MAX_VALUE

        repeat(normalized.size) { offset ->
            val index = (start + offset) % normalized.size
            val key = normalized[index]
            if (key in excluded) return@repeat
            val state = states.getOrPut(fingerprint(key)) { KeyState() }
            if (state.cooldownUntil > now) {
                shortestWait = minOf(shortestWait, state.cooldownUntil - now)
                return@repeat
            }

            pruneWindow(state, now)
            if (rpmLimit <= 0 || state.requestTimes.size < rpmLimit) {
                state.requestTimes.addLast(now)
                cursors[poolId] = (index + 1) % normalized.size
                rememberSessionKey(sessionKey, key)
                return Selection(key)
            }
            shortestWait = minOf(shortestWait, state.requestTimes.first() + WINDOW_MILLIS - now)
        }
        return Selection(null, shortestWait.takeIf { it != Long.MAX_VALUE }?.coerceAtLeast(1L) ?: 0L)
    }

    /** 记录会话粘性绑定，超上限时按最近最少使用淘汰最早的会话。 */
    private fun rememberSessionKey(sessionKey: String?, key: String) {
        if (sessionKey.isNullOrEmpty()) return
        sessionKeys.remove(sessionKey)
        sessionKeys[sessionKey] = key
        while (sessionKeys.size > MAX_SESSION_BINDINGS) {
            sessionKeys.remove(sessionKeys.keys.first())
        }
    }

    @Synchronized
    fun markRateLimited(key: String, retryAfterSeconds: Long?) {
        val retryMillis = (retryAfterSeconds ?: DEFAULT_COOLDOWN_SECONDS)
            .coerceIn(1L, MAX_COOLDOWN_SECONDS) * 1_000L
        val state = states.getOrPut(fingerprint(key)) { KeyState() }
        state.cooldownUntil = maxOf(state.cooldownUntil, nowMillis() + retryMillis)
    }

    private fun pruneWindow(state: KeyState, now: Long) {
        val cutoff = now - WINDOW_MILLIS
        while (state.requestTimes.isNotEmpty() && state.requestTimes.first() <= cutoff) {
            state.requestTimes.removeFirst()
        }
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val WINDOW_MILLIS = 60_000L
        const val DEFAULT_COOLDOWN_SECONDS = 60L
        const val MAX_COOLDOWN_SECONDS = 300L
        const val MAX_SESSION_BINDINGS = 200
    }
}

/** 执行一次带轮询的请求；429 Key 在本轮被排除并进入冷却，其他 Key 随即接管。 */
internal suspend fun <T> executeWithRotatedApiKey(
    model: ModelConfig,
    scheduler: ApiKeyScheduler,
    sessionKey: String? = null,
    request: suspend (ModelConfig) -> T,
): T {
    val keys = model.apiKeys.ifEmpty { listOfNotNull(model.apiKey) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
    if (keys.isEmpty()) return request(model.copy(apiKey = null))

    val excluded = mutableSetOf<String>()
    var lastRateLimit: LlmRateLimitException? = null
    while (true) {
        // 重试前确认协程仍存活：用户已点"停止"时不再用下一个 Key 重发请求
        currentCoroutineContext().ensureActive()
        val selection = scheduler.select(keys, model.requestsPerMinutePerKey, excluded, sessionKey)
        val selectedKey = selection.key
        if (selectedKey == null) {
            if (excluded.size >= keys.size) throw requireNotNull(lastRateLimit)
            if (selection.waitMillis > 0L) {
                delay(selection.waitMillis.milliseconds)
                continue
            }
            throw lastRateLimit ?: IllegalStateException("当前模型没有可用的 API Key")
        }
        try {
            return request(model.copy(apiKey = selectedKey))
        } catch (error: LlmRateLimitException) {
            if (!error.allowImmediateKeyRotation) {
                scheduler.markRateLimited(selectedKey, error.retryAfterSeconds)
                throw error
            }
            // 没有其它可轮换的 Key 时，唯一可用 Key 不进入冷却，直接上抛交由上层退避重试：
            // 否则该 Key 冷却期间本进程的所有请求都会被卡住，单 Key 场景无法自愈。
            if (keys.count { it !in excluded && it != selectedKey } == 0) throw error
            scheduler.markRateLimited(selectedKey, error.retryAfterSeconds)
            excluded += selectedKey
            lastRateLimit = error
        }
    }
}
