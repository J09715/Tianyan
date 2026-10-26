package top.tianyan.app.harness.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.core.database.RequestDiagnosticsRepository

enum class RequestArchiveStatus { MEMORY_ONLY, LOADING, SAVING, SAVED, ERROR }

class RequestDiagnosticsPersistenceException : IllegalStateException("Request diagnostics could not be saved")

@Serializable
private data class ArchiveEnvelope(val version: Int = 1, val sessions: Map<String, List<RequestContextSnapshot>>)

/** One writer, a conflated signal, and bounded immutable snapshots; never queues request bodies. */
internal class RequestDiagnosticsArchive(private val repository: RequestDiagnosticsRepository?) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val json = Json { encodeDefaults = true }
    private var revision = 0L
    private var loading = repository != null
    private val removed = mutableSetOf<String>()
    private val mutableSnapshots = MutableStateFlow<Map<String, List<RequestContextSnapshot>>>(emptyMap())
    private val mutableStatus = MutableStateFlow(if (loading) RequestArchiveStatus.LOADING else RequestArchiveStatus.MEMORY_ONLY)
    private val completed = MutableStateFlow(Completion(-1, false))
    val snapshots = mutableSnapshots.asStateFlow()
    val status = mutableStatus.asStateFlow()

    init {
        if (repository != null) scope.launch {
            val restored = try {
                repository.read()?.let { decode(it) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableStatus.value = RequestArchiveStatus.ERROR
                emptyMap()
            }
            synchronized(lock) {
                val combined = restored.filterKeys { it !in removed }.toMutableMap()
                mutableSnapshots.value.forEach { (id, fresh) ->
                    val history = (combined.remove(id).orEmpty() + fresh).takeLast(RequestDiagnosticsStore.MAX_REQUESTS_PER_SESSION)
                    combined[id] = history
                }
                mutableSnapshots.value = bounded(combined)
                loading = false
                removed.clear()
                if (revision == 0L && mutableStatus.value != RequestArchiveStatus.ERROR) mutableStatus.value = RequestArchiveStatus.SAVED
            }
            if (revision == 0L) completed.value = Completion(0, mutableStatus.value != RequestArchiveStatus.ERROR)
            for (signal in signals) {
                val (target, state) = synchronized(lock) { revision to mutableSnapshots.value }
                val success = try {
                    repository.write(json.encodeToString(ArchiveEnvelope.serializer(), ArchiveEnvelope(sessions = state)))
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) { false }
                synchronized(lock) {
                    mutableStatus.value = if (!success) RequestArchiveStatus.ERROR
                        else if (revision == target) RequestArchiveStatus.SAVED else RequestArchiveStatus.SAVING
                    completed.value = Completion(target, success)
                }
            }
        }
    }

    fun append(snapshot: RequestContextSnapshot) = synchronized(lock) {
        // A deleted session cannot be repopulated by a late callback in the same process.
        if (snapshot.sessionId in deletedSessions) return@synchronized
        val existing = mutableSnapshots.value
        val history = (existing[snapshot.sessionId].orEmpty() + snapshot).takeLast(RequestDiagnosticsStore.MAX_REQUESTS_PER_SESSION)
        mutableSnapshots.value = bounded((existing - snapshot.sessionId) + (snapshot.sessionId to history))
        changed()
    }

    private val deletedSessions = mutableSetOf<String>()

    fun remove(sessionId: String) = synchronized(lock) {
        deletedSessions += sessionId
        if (loading) removed += sessionId
        mutableSnapshots.value = mutableSnapshots.value - sessionId
        changed()
    }

    private fun changed() {
        revision++
        if (repository != null) {
            mutableStatus.value = RequestArchiveStatus.SAVING
            signals.trySend(Unit)
        }
    }

    suspend fun awaitPersistence() {
        if (repository == null) return
        val target = synchronized(lock) { revision }
        if (!completed.first { it.revision >= target }.success) throw RequestDiagnosticsPersistenceException()
    }

    fun close() { scope.cancel(); signals.close() }

    private fun bounded(sessions: Map<String, List<RequestContextSnapshot>>) =
        sessions.entries.toList().takeLast(RequestDiagnosticsStore.MAX_SESSIONS).associate { it.key to it.value }

    private fun decode(value: String): Map<String, List<RequestContextSnapshot>> {
        val envelope = json.decodeFromString(ArchiveEnvelope.serializer(), value)
        check(envelope.version == 1)
        check(envelope.sessions.size <= RequestDiagnosticsStore.MAX_SESSIONS)
        envelope.sessions.forEach { (id, requests) ->
            check(id.length <= 256 && requests.size <= RequestDiagnosticsStore.MAX_REQUESTS_PER_SESSION)
            requests.forEach { request ->
                check(request.sessionId == id && request.operationId.length <= 256 && request.protocol.length <= 256)
                check(request.sections.size <= RequestDiagnosticsStore.MAX_SECTIONS && request.omittedSectionCount >= 0)
                check(request.sections.sumOf { it.preview.length } <= RequestDiagnosticsStore.MAX_PREVIEW_CHARS)
                request.sections.forEach { section ->
                    check(section.label.length <= 256 && section.preview.length <= RequestDiagnosticsStore.MAX_SECTION_CHARS)
                    check(section.fingerprint == null || section.fingerprint.matches(Regex("[a-f0-9]{64}")))
                }
            }
        }
        return envelope.sessions
    }

    private data class Completion(val revision: Long, val success: Boolean)
}
