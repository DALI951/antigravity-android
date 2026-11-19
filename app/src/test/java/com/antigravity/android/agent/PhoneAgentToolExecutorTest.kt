package com.antigravity.android.agent

import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.LogEntry
import com.antigravity.android.data.model.SessionDetailState
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.StepType
import com.antigravity.android.data.model.ToolCall
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.domain.OrchestratorRepository
import com.antigravity.android.updater.UpdateChecker
import com.antigravity.android.updater.UpdateRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class FakeOrchestratorRepository : OrchestratorRepository {
    val sessionDetailFlow = MutableStateFlow(
        SessionDetailState(
            trajectory = CascadeTrajectory(cascadeId = "session-phone-1", status = SessionStatus.RUNNING)
        )
    )
    val sentMessages = mutableListOf<Pair<String, String>>()
    val acknowledgedActions = mutableListOf<Triple<String, Boolean, String>>()

    override val connectionState: StateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Connected("http://127.0.0.1:42100"))

    override fun observeSessions(): Flow<List<SessionSummary>> = emptyFlow()
    override fun observeActiveSession(sessionId: String): Flow<SessionDetailState> = sessionDetailFlow
    override fun observeAgentSwarm(sessionId: String): Flow<List<AgentNode>> = emptyFlow()
    override fun observeExecutionLogs(sessionId: String): Flow<LogEntry> = emptyFlow()

    override suspend fun startSession(prompt: String): Result<String> = Result.success("session-phone-1")

    override suspend fun sendUserMessage(sessionId: String, message: String): Result<Unit> {
        sentMessages.add(sessionId to message)
        return Result.success(Unit)
    }

    override suspend fun cancelSession(sessionId: String): Result<Unit> = Result.success(Unit)
    override suspend fun acknowledgeEdit(sessionId: String, editId: String, approved: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun acknowledgeAction(sessionId: String, approved: Boolean, feedback: String): Result<Unit> {
        acknowledgedActions.add(Triple(sessionId, approved, feedback))
        return Result.success(Unit)
    }

    override fun connect() {}
    override fun disconnect() {}
    override fun updateEndpoint(url: String, csrfToken: String) {}
}

class PhoneAgentToolExecutorTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tempRootDir: File
    private lateinit var apkDir: File
    private lateinit var workspaceDir: File
    private lateinit var installerBridge: DefaultPackageInstallerBridge
    private lateinit var deviceBridge: DefaultPhoneDeviceBridge
    private lateinit var appInstaller: AppInstallerManager
    private lateinit var updateRepository: UpdateRepository
    private lateinit var executor: PhoneAgentToolExecutor

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        tempRootDir = File(System.getProperty("java.io.tmpdir"), "phone_agent_test_" + UUID.randomUUID())
        apkDir = File(tempRootDir, "apks").apply { mkdirs() }
        workspaceDir = File(tempRootDir, "workspace").apply { mkdirs() }

        installerBridge = DefaultPackageInstallerBridge(installPermissionGranted = true)
        deviceBridge = DefaultPhoneDeviceBridge()
        val downloadService = ApkDownloadService(
            client = OkHttpClient(),
            defaultTargetDir = apkDir,
            githubApiBaseUrl = mockServer.url("/github").toString().removeSuffix("/"),
            fdroidApiBaseUrl = mockServer.url("/fdroid/api/v1").toString().removeSuffix("/"),
            fdroidRepoBaseUrl = mockServer.url("/fdroid/repo").toString().removeSuffix("/")
        )
        appInstaller = AppInstallerManager(
            context = null,
            downloadService = downloadService,
            installerBridge = installerBridge
        )
        updateRepository = UpdateRepository(
            context = null,
            updateChecker = UpdateChecker(
                customEndpointUrl = mockServer.url("/repos/DALI951/antigravity-android/releases/latest").toString()
            ),
            appInstallerManager = appInstaller
        )
        executor = PhoneAgentToolExecutor(
            context = null,
            appInstallerManager = appInstaller,
            updateRepository = updateRepository,
            deviceBridge = deviceBridge,
            workspaceDir = workspaceDir
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        tempRootDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // 1. Direct App Install Tool (`install_app`)
    // =========================================================================
    @Test
    fun testInstallAppTool_downloadsVerifiesAndInstalls() = runBlocking {
        val apkBytes = "Autonomous Phone Agent Direct App APK".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(apkBytes)
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val call = ToolCall(
            id = "call-install-1",
            toolName = PhoneAgentTools.TOOL_INSTALL_APP,
            argumentsJson = buildJsonObject {
                put("url", mockServer.url("/apps/super-agent.apk").toString())
                put("app_name", "SuperAgent")
                put("sha256", expectedSha)
            }.toString()
        )

        val result = executor.executeToolCall(call)
        assertFalse("install_app should succeed without error", result.isError)
        assertTrue(result.output.contains("installed_session_committed"))
        assertTrue(result.output.contains(expectedSha))
    }

    // =========================================================================
    // 2. List & Launch Installed Apps (`list_installed_apps`, `launch_app`)
    // =========================================================================
    @Test
    fun testListAndLaunchInstalledApps() = runBlocking {
        val listCall = ToolCall(
            id = "call-list-1",
            toolName = PhoneAgentTools.TOOL_LIST_INSTALLED_APPS,
            argumentsJson = buildJsonObject {
                put("filter", "Termux")
            }.toString()
        )
        val listResult = executor.executeToolCall(listCall)
        assertFalse(listResult.isError)
        assertTrue(listResult.output.contains("com.termux"))

        val launchCall = ToolCall(
            id = "call-launch-1",
            toolName = PhoneAgentTools.TOOL_LAUNCH_APP,
            argumentsJson = buildJsonObject {
                put("app_name", "Termux")
            }.toString()
        )
        val launchResult = executor.executeToolCall(launchCall)
        assertFalse(launchResult.isError)
        assertTrue(launchResult.output.contains("com.termux"))
        assertTrue(deviceBridge.launchedPackages.contains("com.termux"))
    }

    // =========================================================================
    // 3. Execute Shell Command (`execute_shell_command`)
    // =========================================================================
    @Test
    fun testExecuteShellCommand_capturesStdoutAndEnforcesSafetyGuardrails() = runBlocking {
        val echoCall = ToolCall(
            id = "call-shell-1",
            toolName = PhoneAgentTools.TOOL_EXECUTE_SHELL_COMMAND,
            argumentsJson = buildJsonObject {
                put("command", "echo AntigravityOnDeviceShellSuccess")
            }.toString()
        )
        val echoResult = executor.executeToolCall(echoCall)
        assertFalse("Echo command must succeed", echoResult.isError)
        assertTrue(echoResult.output.contains("AntigravityOnDeviceShellSuccess"))

        // Unsafe destructive command must be rejected
        val unsafeCall = ToolCall(
            id = "call-shell-unsafe",
            toolName = PhoneAgentTools.TOOL_EXECUTE_SHELL_COMMAND,
            argumentsJson = buildJsonObject {
                put("command", "rm -rf /")
            }.toString()
        )
        val unsafeResult = executor.executeToolCall(unsafeCall)
        assertTrue("Destructive command must be blocked", unsafeResult.isError)
        assertTrue(unsafeResult.output.contains("Blocked unsafe"))
    }

    // =========================================================================
    // 4. Open Deeplink or URL & System Actions (`open_deeplink_or_url`, `system_action`)
    // =========================================================================
    @Test
    fun testOpenDeeplinkAndSystemActions() = runBlocking {
        val deeplinkCall = ToolCall(
            id = "call-uri-1",
            toolName = PhoneAgentTools.TOOL_OPEN_DEEPLINK_OR_URL,
            argumentsJson = buildJsonObject {
                put("uri", "antigravity://swarm/board")
            }.toString()
        )
        val deeplinkResult = executor.executeToolCall(deeplinkCall)
        assertFalse(deeplinkResult.isError)
        assertTrue(deviceBridge.openedUris.contains("antigravity://swarm/board"))

        val alarmCall = ToolCall(
            id = "call-alarm-1",
            toolName = PhoneAgentTools.TOOL_SYSTEM_ACTION,
            argumentsJson = buildJsonObject {
                put("action", "set_alarm")
                put("hour", 7)
                put("minute", 30)
                put("title", "Standup Swarm")
            }.toString()
        )
        val alarmResult = executor.executeToolCall(alarmCall)
        assertFalse(alarmResult.isError)
        assertTrue(alarmResult.output.contains("07:30"))
    }

    // =========================================================================
    // 5. File Workspace Operations (`file_workspace_op`)
    // =========================================================================
    @Test
    fun testFileWorkspaceOperations_writeAppendReadListDeleteAndSandboxCheck() = runBlocking {
        // Write file
        val writeCall = ToolCall(
            id = "call-file-write",
            toolName = PhoneAgentTools.TOOL_FILE_WORKSPACE_OP,
            argumentsJson = buildJsonObject {
                put("operation", "write")
                put("path", "notes/plan.md")
                put("content", "# Phone Agent Plan\nStep 1: Direct APK Installer")
            }.toString()
        )
        val writeResult = executor.executeToolCall(writeCall)
        assertFalse(writeResult.isError)

        // Append file
        val appendCall = ToolCall(
            id = "call-file-append",
            toolName = PhoneAgentTools.TOOL_FILE_WORKSPACE_OP,
            argumentsJson = buildJsonObject {
                put("operation", "append")
                put("path", "notes/plan.md")
                put("content", "\nStep 2: Self-Updater")
            }.toString()
        )
        assertFalse(executor.executeToolCall(appendCall).isError)

        // Read file
        val readCall = ToolCall(
            id = "call-file-read",
            toolName = PhoneAgentTools.TOOL_FILE_WORKSPACE_OP,
            argumentsJson = buildJsonObject {
                put("operation", "read")
                put("path", "notes/plan.md")
            }.toString()
        )
        val readResult = executor.executeToolCall(readCall)
        assertFalse(readResult.isError)
        assertTrue(readResult.output.contains("Step 1: Direct APK Installer"))
        assertTrue(readResult.output.contains("Step 2: Self-Updater"))

        // List directory
        val listCall = ToolCall(
            id = "call-file-list",
            toolName = PhoneAgentTools.TOOL_FILE_WORKSPACE_OP,
            argumentsJson = buildJsonObject {
                put("operation", "list")
                put("path", "notes")
            }.toString()
        )
        val listResult = executor.executeToolCall(listCall)
        assertFalse(listResult.isError)
        assertTrue(listResult.output.contains("plan.md"))

        // Path traversal attempt outside workspace must fail
        val traversalCall = ToolCall(
            id = "call-file-traversal",
            toolName = PhoneAgentTools.TOOL_FILE_WORKSPACE_OP,
            argumentsJson = buildJsonObject {
                put("operation", "read")
                put("path", "../../outside_secret.txt")
            }.toString()
        )
        val traversalResult = executor.executeToolCall(traversalCall)
        assertTrue("Path traversal outside workspace must be blocked", traversalResult.isError)
    }

    // =========================================================================
    // 6. Natural-Language Phone Commands
    // =========================================================================
    @Test
    fun testNaturalLanguageCommandExecution() = runBlocking {
        val wifiReport = executor.executeNaturalLanguageCommand("Open Wi-Fi settings").getOrThrow()
        assertTrue(wifiReport.success)
        assertEquals(PhoneAgentTools.TOOL_SYSTEM_ACTION, wifiReport.toolName)

        val launchReport = executor.executeNaturalLanguageCommand("Launch Termux").getOrThrow()
        assertTrue(launchReport.success)
        assertEquals(PhoneAgentTools.TOOL_LAUNCH_APP, launchReport.toolName)
    }

    // =========================================================================
    // 7. Bidirectional OrchestratorRepository Binding
    // =========================================================================
    @Test
    fun testBidirectionalOrchestratorBinding_executesIncomingToolStepAndReturnsResult() = runBlocking {
        val fakeRepo = FakeOrchestratorRepository()
        val bindingJob = executor.bindToOrchestrator(fakeRepo, "session-phone-1")

        val incomingToolStep = Step(
            stepIndex = 1,
            type = StepType.TOOL_CALL,
            role = "assistant",
            content = "Launching Termux on phone",
            toolCall = ToolCall(
                id = "orch-tool-99",
                toolName = PhoneAgentTools.TOOL_LAUNCH_APP,
                argumentsJson = buildJsonObject {
                    put("package_name", "com.termux")
                }.toString()
            )
        )

        fakeRepo.sessionDetailFlow.value = SessionDetailState(
            trajectory = CascadeTrajectory(
                cascadeId = "session-phone-1",
                status = SessionStatus.RUNNING,
                steps = listOf(incomingToolStep)
            )
        )

        // Wait briefly for coroutine collector to execute tool and report back
        var attempts = 0
        while (fakeRepo.sentMessages.isEmpty() && attempts < 50) {
            delay(20)
            attempts++
        }

        bindingJob.cancel()

        assertEquals("Tool result must be sent back to OrchestratorRepository", 1, fakeRepo.sentMessages.size)
        val (sessionId, messagePayload) = fakeRepo.sentMessages.first()
        assertEquals("session-phone-1", sessionId)
        assertTrue(messagePayload.contains("PHONE_AGENT_TOOL_RESULT"))
        assertTrue(messagePayload.contains("orch-tool-99"))
        assertTrue(messagePayload.contains("com.termux"))

        assertEquals(1, fakeRepo.acknowledgedActions.size)
        assertTrue(fakeRepo.acknowledgedActions.first().second)
    }
}
