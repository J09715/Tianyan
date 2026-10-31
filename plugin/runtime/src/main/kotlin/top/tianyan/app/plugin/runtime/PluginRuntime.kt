package top.tianyan.app.plugin.runtime

import java.util.Collections
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.tianyan.app.plugin.api.PluginCatalog

/** Serialized lifecycle operations; admission remains synchronous and closes before draining. */
class PluginRuntime(
    override val catalog: PluginCatalog,
    activators: Map<String, suspend (PluginActivation) -> Unit>,
    private val selection: PluginSelectionStore,
    private val context: CoroutineContext = Dispatchers.Default,
    private val timeoutMillis: Long = 10_000,
) : PluginController {
    private val operations = Mutex()
    private val activators = activators.toMap()
    private val controlScope = CoroutineScope(context.minusKey(Job) + SupervisorJob())
    private val monitor = Any()
    private val activations = mutableMapOf<String, PluginActivation>()
    private val failures = mutableMapOf<String, PluginFailure>()
    @Volatile private var disabled = emptySet<String>()
    private var nextGeneration = 0L
    private var closed = false
    private val _states = MutableStateFlow<Map<String, PluginStatus>>(Collections.unmodifiableMap(catalog.plugins.associate { it.id to PluginStatus() }))
    override val states = _states.asStateFlow()
    private val _initialized = MutableStateFlow(false)
    override val initialized = _initialized.asStateFlow()
    private val _preferenceFailure = MutableStateFlow(false)
    override val preferenceFailure = _preferenceFailure.asStateFlow()
    private val ancestors = buildMap<String, Set<String>> {
        catalog.plugins.forEach { plugin ->
            put(plugin.id, plugin.dependencies.flatMap { getValue(it) + it }.toSet())
        }
    }

    init {
        require(timeoutMillis > 0)
        require(activators.keys == catalog.plugins.map { it.id }.toSet()) { "Plugin activators differ from catalog" }
    }

    override suspend fun initialize() = operations.withLock {
        if (_initialized.value || closed) return@withLock
        val stored = try { selection.disabledPlugins().toSet() } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _preferenceFailure.value = true
            return@withLock
        }
        disabled = stored
        _preferenceFailure.value = false
        // Finish bounded transitions even if the UI caller disappears mid-operation.
        withContext(NonCancellable) { reconcile() }
        _initialized.value = true
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) = operations.withLock {
        require(catalog.plugin(id) != null) { "Unknown plugin" }
        if (!_initialized.value || closed) return@withLock
        withContext(NonCancellable) {
            try { selection.setEnabled(id, enabled) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _preferenceFailure.value = true
                return@withContext
            }
            _preferenceFailure.value = false
            disabled = if (enabled) disabled - id else disabled + id
            if (enabled && id in failures) {
                if (stop(id)) failures.remove(id)
            }
            reconcile()
        }
    }

    override fun acquire(id: String): PluginLease? = synchronized(monitor) {
        if (closed || _states.value[id]?.phase != PluginPhase.RUNNING) return null
        if (ancestors[id].orEmpty().any { _states.value[it]?.phase != PluginPhase.RUNNING || activations[it]?.hasFailed != false }) return null
        activations[id]?.takeUnless { it.hasFailed }?.acquire()
    }

    override suspend fun shutdown(): Boolean = operations.withLock {
        synchronized(monitor) { closed = true }
        withContext(NonCancellable) {
            var success = true
            catalog.plugins.asReversed().forEach { if (!stop(it.id)) success = false }
            if (success) controlScope.cancel()
            success
        }
    }

    private suspend fun reconcile() {
        val desired = mutableSetOf<String>()
        catalog.plugins.forEach { plugin ->
            if (plugin.id !in disabled && plugin.id !in failures && plugin.dependencies.all { it in desired }) desired += plugin.id
        }
        catalog.plugins.asReversed().forEach {
            if (it.id !in desired && stop(it.id) && (it.id in disabled || failures[it.id] == PluginFailure.RELEASE)) failures.remove(it.id)
        }
        catalog.plugins.forEach { plugin ->
            val id = plugin.id
            when {
                id in failures -> update(id, PluginPhase.FAILED, failures[id])
                id in disabled -> update(id, PluginPhase.STOPPED)
                plugin.dependencies.any { _states.value[it]?.phase != PluginPhase.RUNNING } -> update(id, PluginPhase.BLOCKED)
                _states.value[id]?.phase != PluginPhase.RUNNING -> start(id)
                else -> update(id, PluginPhase.RUNNING)
            }
        }
    }

    private suspend fun start(id: String) {
        val generation = ++nextGeneration
        val activation = PluginActivation(id, generation, context) {
            synchronized(monitor) {
                if (!closed && activations[id]?.generation == generation) {
                    activations[id]?.stopAccepting()
                    update(id, PluginPhase.FAILED, PluginFailure.START)
                }
            }
            controlScope.launch {
                operations.withLock {
                    if (!closed && synchronized(monitor) { activations[id]?.generation == generation }) {
                        failures[id] = PluginFailure.START
                        withContext(NonCancellable) { reconcile() }
                    }
                }
            }
        }
        synchronized(monitor) { activations[id] = activation }
        update(id, PluginPhase.STARTING, generation = activation.generation)
        try {
            withTimeout(timeoutMillis) { activators.getValue(id)(activation) }
            synchronized(monitor) {
                check(!activation.hasFailed) { "Plugin activation failed" }
                update(id, PluginPhase.RUNNING)
            }
        } catch (_: Exception) {
            failures[id] = PluginFailure.START
            stop(id)
            update(id, PluginPhase.FAILED, failures[id])
        }
    }

    private suspend fun stop(id: String): Boolean {
        val activation = synchronized(monitor) { activations[id] } ?: return true
        val retainedDependent = synchronized(monitor) {
            activations.keys.any { it != id && id in ancestors.getValue(it) }
        }
        if (retainedDependent) {
            failures.putIfAbsent(id, PluginFailure.RELEASE)
            update(id, PluginPhase.FAILED, failures[id])
            return false
        }
        update(id, PluginPhase.DRAINING)
        activation.stopAccepting()
        val released = try { activation.close(timeoutMillis) } catch (_: Exception) { false }
        if (released) {
            synchronized(monitor) { activations.remove(id) }
            update(id, PluginPhase.STOPPED)
        } else {
            failures.putIfAbsent(id, PluginFailure.RELEASE)
            update(id, PluginPhase.FAILED, failures[id])
        }
        return released
    }

    private fun update(id: String, phase: PluginPhase, failure: PluginFailure? = null, generation: Long? = null) = synchronized(monitor) {
        val previous = _states.value.getValue(id)
        _states.value = Collections.unmodifiableMap(_states.value + (id to previous.copy(
            phase = phase, selected = id !in disabled, generation = generation ?: previous.generation, failure = failure,
        )))
    }
}
