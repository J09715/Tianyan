package top.tianyan.app.plugin.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.tianyan.app.plugin.runtime.*
import top.tianyan.app.plugin.ui.PluginPageRequest
import top.tianyan.app.plugin.ui.R
import top.tianyan.app.ui.components.*

@Composable
internal fun WebPluginPage(plugin: WebPagePlugin, request: PluginPageRequest) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val themeCss = remember(colors) {
        fun cssColor(color: androidx.compose.ui.graphics.Color) = "#%06x".format(color.toArgb() and 0xffffff)
        ":root { --surface: ${cssColor(colors.surface)}; --on-surface: ${cssColor(colors.onSurface)}; " +
            "--primary: ${cssColor(colors.primary)}; --on-primary: ${cssColor(colors.onPrimary)}; " +
            "--container: ${cssColor(colors.surfaceContainerHigh)}; }"
    }
    val policy = remember(plugin, request.activation) { WebPluginPolicy() }
    val source = remember(plugin, request.activation, policy) { java.util.concurrent.atomic.AtomicReference<WebPluginAssetLease?>(null) }
    val bridge = remember(plugin, request.activation, policy) { WebPluginStreamBridge() }
    val streamCapabilities = remember(plugin) { plugin.capabilities.filter { it.stream != null }.map { it.capability }.toSet() }
    val session = remember(plugin, context, request.activation, policy) {
        val identity = PluginCapabilityIdentity(plugin.id, plugin.version, request.activation?.generation ?: 0,
            policy.origin, "android:local")
        PluginCapabilitySession(
            identity,
            plugin.capabilities.associate { capability ->
                capability.capability to suspend { call: PluginCapabilityCall ->
                    // A stream is still one ordinary single-shot call: chunks go back over the same
                    // request id and the final envelope marks the end, so the session keeps one
                    // in-flight call per document and revocation cancels the producer.
                    val stream = capability.stream
                    if (stream == null) capability.execute!!.invoke(context, identity, call) else {
                        stream.invoke(context, identity, call) { chunk ->
                            withContext(Dispatchers.Main) { bridge.push(chunk) }
                        }
                        ""
                    }
                }
            },
            isAvailable = { plugin.isAvailable() && source.get()?.isAvailable == true && request.activation?.let { it.pluginId == plugin.id && it.isAccepting } == true },
            oneShotCapabilities = plugin.capabilities.filter { !it.allowSession }.map { it.capability }.toSet(),
            longLivedCapabilities = plugin.capabilities.filter { it.allowSession }.map { it.capability }.toSet(),
        )
    }
    val prompt by session.prompt.collectAsState()
    val resources by produceState<Result<WebPluginResources>?>(null, plugin, policy, context, session) {
        value = null
        val sourceId = "web-source:${policy.origin}"
        var owner: WebPluginSourceOwner? = null
        try {
            val activation = requireNotNull(request.activation)
            // Do not acquire services if this device cannot host a verified message origin.
            check(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            owner = WebPluginSourceOwner(activation, sourceId)
            withContext(Dispatchers.IO) {
                // Factories clean up cancellation before returning; register before dispatching back.
                val lease = owner!!.open { plugin.openSource?.invoke(context) ?: WebPluginAssetLease(plugin.loadAssets(context)) }
                source.set(lease)
                val assets = lease.loadAssets() + ("theme.css" to themeCss.toByteArray(Charsets.UTF_8))
                value = Result.success(WebPluginResources(policy, assets, lease::resolveAsset))
            }
            while (plugin.isAvailable() && source.get()?.isAvailable == true && activation.isAccepting) delay(250)
            session.close()
            value = Result.failure(IllegalStateException("Web plugin source unavailable"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.close()
            value = Result.failure(IllegalStateException("Web plugin assets unavailable"))
        } finally {
            source.set(null)
            withContext(NonCancellable + Dispatchers.IO) {
                owner?.close()
            }
        }
    }
    var view by remember(session) { mutableStateOf<WebPluginView?>(null) }
    DisposableEffect(session) {
        onDispose { bridge.close(); session.close(); view?.dispose(); view = null }
    }
    Scaffold(topBar = { RuntimeTopBar(plugin.title(), request.onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RuntimeCard(Modifier.fillMaxWidth().padding(12.dp)) {
                if (plugin.openSource != null) Text(plugin.description())
                Text(stringResource(R.string.ui_web_plugin_scope))
                RuntimeTextButton(onClick = { session.revoke() }) {
                    Text(stringResource(R.string.ui_web_plugin_revoke))
                }
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                Text(stringResource(R.string.ui_web_plugin_unavailable), Modifier.padding(16.dp))
            } else if (resources == null) {
                Text(stringResource(R.string.ui_web_plugin_loading), Modifier.padding(16.dp))
            } else if (resources?.isFailure == true) {
                Text(stringResource(R.string.ui_web_plugin_load_failed), Modifier.padding(16.dp))
            } else key(session) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    factory = { androidContext ->
                        WebPluginView(androidContext, policy, session, scope, resources!!.getOrThrow(), bridge, streamCapabilities)
                            .also { view = it }
                    },
                    onRelease = { it.dispose() },
                )
            }
        }
    }
    prompt?.let { pending ->
        val capability = plugin.capabilities.single { it.capability == pending.call.capability }
        RuntimeAlertDialog(
            onDismissRequest = { session.decide(pending.token, PluginGrantChoice.DENY) },
            title = { Text(capability.title()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(capability.description())
                    pending.call.text?.let { text ->
                        val preview = if (pending.call.capability.action in PluginCapabilityInput.SERVICE_ACTIONS) {
                            R.string.ui_web_plugin_service_preview
                        } else R.string.ui_web_plugin_text_preview
                        Text(stringResource(preview, PluginCapabilityInput.preview(text)))
                    }
                    Text("${pending.identity.pluginId} · ${pending.identity.version}\n${pending.call.capability.action}\n${pending.call.capability.target}\n${pending.identity.environment}")
                }
            },
            confirmButton = {
                Column {
                    RuntimeTextButton(onClick = { session.decide(pending.token, PluginGrantChoice.ONCE) }) {
                        Text(stringResource(R.string.ui_web_plugin_allow_once))
                    }
                    if (capability.allowSession) RuntimeTextButton(onClick = { session.decide(pending.token, PluginGrantChoice.SESSION) }) {
                        Text(stringResource(R.string.ui_web_plugin_allow_session))
                    }
                }
            },
            dismissButton = {
                RuntimeTextButton(onClick = { session.decide(pending.token, PluginGrantChoice.DENY) }) {
                    Text(stringResource(R.string.ui_web_plugin_deny))
                }
            },
        )
    }
}
