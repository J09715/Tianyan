package top.tianyan.app.harness.approval

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.AgentApprovalAuditDao
import top.tianyan.app.core.database.AgentApprovalAuditEntity
import top.tianyan.app.core.database.AgentApprovalAuditRepository
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.RoomHarnessSessionRepository
import top.tianyan.app.core.model.ApprovalMode
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.ApprovalPolicyEngine
import top.tianyan.app.harness.AskUserToolBackend
import top.tianyan.app.harness.ContextMemoryToolBackend
import top.tianyan.app.harness.DownloadToolBackend
import top.tianyan.app.harness.HarnessPathResolver
import top.tianyan.app.harness.HarnessServiceToolBackend
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.HostCapabilityToolBackend
import top.tianyan.app.harness.LinuxCommandToolBackend
import top.tianyan.app.harness.PromptAssetToolBackend
import top.tianyan.app.harness.ToolCall
import top.tianyan.app.harness.ToolExecutor
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.WorkspaceMutationSnapshots
import top.tianyan.app.harness.core.ToolCheckpoints
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.runtime.LinuxRuntime

/**
 * 审批审计台账（DSH 借鉴项）：会话授权豁免 / deferred 交接必须留下持久痕迹；
 * 审批仓库缺失（unavailable）必须 fail-closed 为失败结果，绝不静默放行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ApprovalAuditTrailTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var database: AppDatabase
    private lateinit var sessions: RoomHarnessSessionRepository
    private lateinit var approvals: AgentApprovalRepository
    private lateinit var audits: AgentApprovalAuditRepository
    private lateinit var grants: SessionApprovalGrants
    private lateinit var auditor: ApprovalAuditRecorder
    private lateinit var root: File
    private val call = ToolCall("call-write", 1, HarnessTool.WRITE,
        Json.parseToJsonElement("""{"path":"a.txt","content":"written"}""") as JsonObject, rawToolName = "write")

    @Before fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        root = temporary.newFolder("workspace")
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        sessions = RoomHarnessSessionRepository(database.harnessSessionDao())
        approvals = AgentApprovalRepository(database.agentApprovalDao())
        audits = AgentApprovalAuditRepository(database.agentApprovalAuditDao())
        grants = SessionApprovalGrants()
        auditor = ApprovalAuditRecorder(audits)
        seed()
    }

    @After fun tearDown() { database.close() }

    private suspend fun seed(mode: String = ApprovalMode.FULL_ACCESS.id) = sessions.upsert(
        HarnessSessionEntity("session", "test", 1, 1, null, approvalMode = mode, runMode = "build"),
    )

    private fun executor(approvalRepo: AgentApprovalRepository? = approvals): ToolExecutor {
        val paths = HarnessPathResolver()
        return ToolExecutor(
            WorkspaceFileAccess(root), paths, ApprovalPolicyEngine(paths), SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(unusedPort(), paths),
            downloadToolBackend = DownloadToolBackend(
                unusedPort<FileDownloader>(), WorkspaceFileAccess(root), WorkspaceMutationSnapshots(),
            ),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(paths), approvals),
            promptAssetToolBackend = PromptAssetToolBackend(),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
            approvalRepository = approvalRepo, sessionDao = sessions,
            sessionApprovalGrants = grants,
            approvalAuditor = auditor,
        )
    }

    @Test
    fun `session grant exemption executes and leaves audit trail`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        grants.grant("session", "write", call.args.toString(), "normal")
        val result = executor().execute(call, "session")
        assertTrue(result.success)
        assertTrue(File(root, "a.txt").exists())
        // 没有走审批请求，但豁免执行留下了持久审计
        assertTrue(approvals.pendingNow("session").isEmpty())
        val rows = audits.listForSession("session")
        assertEquals(1, rows.size)
        assertEquals(AgentApprovalAuditEntity.KIND_GRANT_EXEMPTED, rows.single().kind)
        assertEquals("write", rows.single().toolName)
        assertEquals(call.id, rows.single().toolCallId)
        assertNotNull(rows.single().grantKey)
    }

    @Test
    fun `deferred lane handoff leaves audit trail`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        val result = executor().execute(call, "session", allowApprovalRequest = false)
        assertTrue(result.approvalDeferred)
        assertFalse(result.success)
        val rows = audits.listForSession("session")
        assertEquals(1, rows.size)
        assertEquals(AgentApprovalAuditEntity.KIND_DEFERRED, rows.single().kind)
        assertEquals(call.id, rows.single().toolCallId)
    }

    @Test
    fun `missing approval repository fails closed instead of executing`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        val result = executor(approvalRepo = null).execute(call, "session")
        assertFalse(result.success)
        assertFalse(File(root, "a.txt").exists())
    }

    @Test
    fun `grant written records key and audit cleans with session`() = runBlocking {
        auditor.grantWritten(
            sessionId = "session", toolName = "write",
            argumentsJson = call.args.toString(), riskLevel = "normal", approvalRequestId = "req_1",
        )
        val rows = audits.listForSession("session")
        assertEquals(1, rows.size)
        assertEquals(AgentApprovalAuditEntity.KIND_GRANT_WRITTEN, rows.single().kind)
        assertEquals("req_1", rows.single().approvalRequestId)
        assertNotNull(rows.single().grantKey)
        auditor.deleteForSession("session")
        assertTrue(audits.listForSession("session").isEmpty())
    }

    @Test
    fun `audit insert failure never blocks the tool call`() = runBlocking {
        seed(ApprovalMode.REQUEST.id)
        grants.grant("session", "write", call.args.toString(), "normal")
        val broken = ApprovalAuditRecorder(AgentApprovalAuditRepository(FailingAuditDao))
        val paths = HarnessPathResolver()
        val executor = ToolExecutor(
            WorkspaceFileAccess(root), paths, ApprovalPolicyEngine(paths), SecretRedactor(),
            hostToolBackend = HostCapabilityToolBackend(secretRedactor = SecretRedactor()),
            linuxCommandToolBackend = LinuxCommandToolBackend(unusedPort(), paths),
            downloadToolBackend = DownloadToolBackend(
                unusedPort<FileDownloader>(), WorkspaceFileAccess(root), WorkspaceMutationSnapshots(),
            ),
            contextMemoryToolBackend = ContextMemoryToolBackend(),
            askUserToolBackend = AskUserToolBackend(ApprovalPolicyEngine(paths), approvals),
            promptAssetToolBackend = PromptAssetToolBackend(),
            harnessServiceToolBackend = HarnessServiceToolBackend(),
            capabilityToolGateway = CapabilityToolGateway(null) { _, _, _, _ -> error("unexpected host dispatch") },
            approvalRepository = approvals, sessionDao = sessions,
            sessionApprovalGrants = grants,
            approvalAuditor = broken,
        )
        val result = executor.execute(call, "session")
        assertTrue(result.success)
        assertTrue(File(root, "a.txt").exists())
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T : Any> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, _, _ -> error("unexpected backend use") } as T

    private object FailingAuditDao : AgentApprovalAuditDao {
        override suspend fun insert(entry: AgentApprovalAuditEntity) = error("audit storage down")
        override fun observeForSession(sessionId: String) = kotlinx.coroutines.flow.MutableStateFlow(emptyList<AgentApprovalAuditEntity>())
        override suspend fun listForSession(sessionId: String, limit: Int) = emptyList<AgentApprovalAuditEntity>()
        override suspend fun countForSession(sessionId: String) = 0
        override suspend fun deleteForSession(sessionId: String) = Unit
    }
}
