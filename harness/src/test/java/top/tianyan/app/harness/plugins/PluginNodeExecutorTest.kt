package top.tianyan.app.harness.plugins

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.workflow.WorkflowApprovalBroker
import top.tianyan.app.plugin.api.*

class PluginNodeExecutorTest {
    private val context = WorkflowRuntimeContext("run", "flow", "/workspace/fixed")
    private val node = WorkflowNode("action", WorkflowNodeType.PLUGIN_ACTION, "Plugin", config = mapOf("plugin" to "example.nodes", "extension" to "echo", "arguments" to "{\"text\":\"literal\"}"))
    private fun registry(action: suspend (PluginWorkflowRequest) -> PluginToolOutput) = HarnessPluginRegistry(listOf(HarnessPlugin(
        PluginDescriptor("example.nodes", "1.0.0", extensions = listOf(PluginExtension("echo", PluginExtensionPoint.WORKFLOW))),
        nodes = listOf(PluginWorkflowNode("echo", "Echo", Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""").jsonObject, action)),
    )))
    @Test fun missingNodeOrInvalidArgumentsNeverReachApprovalOrExecute() = runTest {
        val broker = WorkflowApprovalBroker()
        val executor = PluginNodeExecutor(registry { error("must not execute") }, broker, SecretRedactor())
        listOf(node.copy(config = node.config + ("extension" to "missing")), node.copy(config = node.config + ("arguments" to "[]")), node.copy(config = node.config + ("arguments" to "{}"))).forEach {
            assertEquals(NodeRunStatus.FAILED, executor.execute(it, context) { _, _ -> }.status)
        }
        assertNull(broker.currentRequest.value)
    }
    @Test fun approvalTokenBindsCallbackAndWorkspace() = runTest {
        var executions = 0
        val broker = WorkflowApprovalBroker()
        val executor = PluginNodeExecutor(registry { request -> executions++; assertEquals("/workspace/fixed", request.context.workspacePath); PluginToolOutput(true, "done") }, broker, SecretRedactor())
        val result = async { executor.execute(node, context) { _, _ -> } }
        yield(); val request = broker.currentRequest.value!!
        assertEquals(0, executions)
        assertFalse(broker.decide("run", "action", WorkflowApprovalDecision(true, reviewToken = "old")))
        assertTrue(broker.decide("run", "action", WorkflowApprovalDecision(true, reviewToken = request.reviewToken)))
        assertEquals(NodeRunStatus.SUCCESS, result.await().status); assertEquals(1, executions)
    }
    @Test fun denialAndCancellationCannotInvokeCallback() = runTest {
        val broker = WorkflowApprovalBroker()
        val executor = PluginNodeExecutor(registry { error("must not execute") }, broker, SecretRedactor())
        val first = async { executor.execute(node, context) { _, _ -> } }
        yield(); broker.decide("run", "action", WorkflowApprovalDecision(false)); assertEquals(NodeRunStatus.FAILED, first.await().status)
        val second = async { executor.execute(node, context) { _, _ -> } }
        yield(); second.cancelAndJoin(); assertNull(broker.currentRequest.value)
    }
    @Test fun callbackFailureCannotExposeExceptionOrReportSuccess() = runTest {
        val broker = WorkflowApprovalBroker()
        val executor = PluginNodeExecutor(registry { error("private-key") }, broker, SecretRedactor())
        val result = async { executor.execute(node, context) { _, _ -> } }
        yield(); broker.decide("run", "action", WorkflowApprovalDecision(true, reviewToken = broker.currentRequest.value!!.reviewToken))
        val output = result.await(); assertEquals(NodeRunStatus.FAILED, output.status); assertFalse(output.error.orEmpty().contains("private-key"))
    }
}
