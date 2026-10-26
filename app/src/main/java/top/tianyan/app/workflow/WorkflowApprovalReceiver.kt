package top.tianyan.app.workflow

import org.koin.core.component.inject
import org.koin.core.component.KoinComponent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import top.tianyan.app.harness.workflow.WorkflowRunManager

/** 通知栏审批按钮回调：批准/拒绝后让对应工作流节点继续推进。 */
class WorkflowApprovalReceiver : BroadcastReceiver(), KoinComponent {

    val runManager: WorkflowRunManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECIDE) return
        val executionId = intent.getStringExtra(EXTRA_EXECUTION_ID) ?: return
        val nodeId = intent.getStringExtra(EXTRA_NODE_ID) ?: return
        val approved = intent.getBooleanExtra(EXTRA_APPROVED, false)
        val accepted = runCatching { runManager.decide(executionId, nodeId, approved) }
            .onFailure { Log.w(TAG, "工作流审批处理失败", it) }
            .getOrDefault(false)
        if (!accepted) {
            Log.w(TAG, "审批提交未找到对应运行/节点：$executionId/$nodeId")
        }
    }

    companion object {
        const val ACTION_DECIDE = "top.tianyan.app.action.WORKFLOW_APPROVAL_DECIDE"
        const val EXTRA_EXECUTION_ID = "extra_workflow_execution_id"
        const val EXTRA_NODE_ID = "extra_workflow_node_id"
        const val EXTRA_APPROVED = "extra_workflow_approved"
        private const val TAG = "WorkflowApprovalRx"
    }
}
