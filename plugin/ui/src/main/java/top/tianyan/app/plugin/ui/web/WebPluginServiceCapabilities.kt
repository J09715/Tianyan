package top.tianyan.app.plugin.ui.web

import androidx.compose.ui.res.stringResource
import top.tianyan.app.plugin.runtime.PluginCapabilityInput
import top.tianyan.app.plugin.ui.R

/**
 * Host-registered read-only access to the page's own bound live service.
 *
 * The document never learns the loopback address or any credential: it names a relative sub-path in
 * the request payload and the native lease answers only from its own service. The single-shot reply
 * is bounded by the session result cap and the stream is pushed as bounded chunks under the same
 * request id, so a service can never flood a page.
 */
object WebPluginServiceCapabilities {
    fun request(source: () -> WebPluginAssetLease?): WebPluginCapability = WebPluginCapability(
        capability = PluginCapabilityInput.SERVICE_REQUEST,
        title = { stringResource(R.string.ui_web_service_request_title) },
        description = { stringResource(R.string.ui_web_service_request_detail) },
        execute = { _, _, call ->
            val path = requireNotNull(call.text) { "A service request needs a relative sub-path" }
            source()?.requestPath(path) ?: error("Service request unavailable")
        },
    )

    fun stream(source: () -> WebPluginAssetLease?): WebPluginCapability = WebPluginCapability(
        capability = PluginCapabilityInput.SERVICE_STREAM,
        title = { stringResource(R.string.ui_web_service_stream_title) },
        description = { stringResource(R.string.ui_web_service_stream_detail) },
        stream = { _, _, call, emit ->
            val path = requireNotNull(call.text) { "A service stream needs a relative sub-path" }
            if (source()?.streamPath(path, emit) != true) error("Service stream unavailable")
        },
    )
}
