package com.antigravity.android.e2e

import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.FieldDiff
import com.antigravity.android.data.model.MessageDiff
import com.antigravity.android.data.model.RepeatedDiff
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SingularValue
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.network.BackoffCalculator
import com.antigravity.android.data.network.ConnectFrameBuilder
import com.antigravity.android.data.network.ConnectFrameEnvelope
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.domain.StateDiffReducer
import com.antigravity.android.model.AgentHierarchyBuilder
import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.countActiveAgents
import com.antigravity.android.model.CircularLogBuffer
import com.antigravity.android.model.LogEntry
import com.antigravity.android.updater.ChecksumMismatchException
import com.antigravity.android.updater.PackageInstallerSpec
import com.antigravity.android.updater.TestDownloadEngine
import com.antigravity.android.updater.UpdateCheckResult
import com.antigravity.android.updater.UpdateManifest
import com.antigravity.android.updater.UpdateParser
import com.antigravity.android.viewmodel.CodeEditApproval
import com.antigravity.android.viewmodel.SessionStateHolder
import com.antigravity.android.viewmodel.SessionTab
import com.antigravity.android.viewmodel.UpdateState
import com.antigravity.android.viewmodel.UpdateStateHolder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class MockWebServerE2ETest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tempDir: File
    private val httpClient = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val testCsrfToken = "62a9c73c-e20f-4d31-bbfc-03ebb744f9b9"

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "e2e_antigravity_" + UUID.randomUUID())
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        tempDir.deleteRecursively()
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }

    // =========================================================================
    // Tier 4, Scenario 1: Complete New Session Lifecycle with Real-Time Streaming
    // =========================================================================
    @Test
    fun testScenario1_CompleteSessionLifecycleStreaming() {
        // 1. Prepare Mock Daemon StartCascade response
        val startCascadeResponse = buildJsonObject {
            put("cascadeId", "cascade-android-e2e-001")
            put("status", "RUNNING")
        }.toString()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(startCascadeResponse)
        )

        // 2. Client formats Connect Start frame
        val startFrame = ConnectFrameBuilder.createStartFrame(
            procedure = "/exa.language_server_pb.LanguageServerService/StartCascade",
            csrfToken = testCsrfToken,
            payload = buildJsonObject { put("prompt", "Build Antigravity Android Native Client") }
        )

        val frameJson = json.encodeToString(ConnectFrameEnvelope.serializer(), startFrame)

        // 3. Dispatch to Mock Daemon
        val request = Request.Builder()
            .url(mockServer.url("/exa.language_server_pb.LanguageServerService/StartCascade"))
            .addHeader("Content-Type", "application/json")
            .addHeader(ConnectFrameBuilder.HEADER_CSRF, testCsrfToken)
            .post(frameJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        assertTrue("StartCascade must return 200 OK", response.isSuccessful)

        // 4. Session & Swarm State setup
        val sessionHolder = SessionStateHolder("cascade-android-e2e-001")
        val logBuffer = CircularLogBuffer()

        val subagentBackend = AgentNode(
            agentId = "sub-backend",
            name = "Backend Spec Miner",
            role = AgentRole.SPEC_MINER,
            status = AgentStatus.RUNNING,
            parentAgentId = "root",
            nestingDepth = 1
        )
        val subagentUpdater = AgentNode(
            agentId = "sub-updater",
            name = "Updater Spec Miner",
            role = AgentRole.SPEC_MINER,
            status = AgentStatus.RUNNING,
            parentAgentId = "root",
            nestingDepth = 1
        )
        val rootNode = AgentNode(
            agentId = "root",
            name = "Root Orchestrator",
            role = AgentRole.ORCHESTRATOR,
            status = AgentStatus.RUNNING,
            nestingDepth = 0,
            children = listOf(subagentBackend, subagentUpdater)
        )
        sessionHolder.updateSwarm(listOf(rootNode))

        logBuffer.append(LogEntry(level = "INFO", tag = "Orchestrator", message = "Spawned 2 subagents"))
        logBuffer.append(LogEntry(level = "TOOL", tag = "Miner", message = "Extracting proto definitions"))

        // 5. Differential streaming updates
        val diff1 = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        updateSteps = listOf(
                            Step(stepIndex = 0, role = "user", content = "Build Antigravity Android Native Client"),
                            Step(stepIndex = 1, role = "assistant", content = "Analyzing requirements...")
                        )
                    )
                )
            )
        )
        val state1 = StateDiffReducer.reduce(sessionHolder.state.trajectory, diff1)

        val diff2 = MessageDiff(
            fieldDiffs = listOf(
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STEPS,
                    updateRepeated = RepeatedDiff(
                        tokenAppends = mapOf(1 to " Extracted Connect-RPC & In-App Updater specs.")
                    )
                ),
                FieldDiff(
                    fieldNumber = StateDiffReducer.FIELD_STATUS,
                    updateSingular = SingularValue(stringVal = "COMPLETED")
                )
            )
        )
        val state2 = StateDiffReducer.reduce(state1, diff2)

        // Verify full state consistency
        assertEquals(SessionStatus.COMPLETED, state2.status)
        assertEquals(2, state2.steps.size)
        assertEquals("Analyzing requirements... Extracted Connect-RPC & In-App Updater specs.", state2.steps[1].content)
        assertEquals(2, logBuffer.size())
        assertEquals(3, rootNode.countActiveAgents())
    }

    // =========================================================================
    // Tier 4, Scenario 2: Bidirectional Approval of Code Diff with User Feedback
    // =========================================================================
    @Test
    fun testScenario2_BidirectionalApprovalOfCodeDiff() {
        val sessionHolder = SessionStateHolder("cascade-android-e2e-002")

        // 1. Session enters pending approval state
        val proposedApproval = CodeEditApproval(
            editId = "edit-proposal-99",
            filePath = "app/src/main/java/com/antigravity/android/updater/DownloadEngine.kt",
            diffText = "+ fun verifySha256(bytes: ByteArray): Boolean"
        )
        sessionHolder.setPendingApproval(proposedApproval)
        assertNotNull(sessionHolder.state.pendingApproval)

        // 2. Mock daemon response for approval RPC
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"acknowledged": true, "editId": "edit-proposal-99"}""")
        )

        // 3. User reviews and approves
        val userDecision = sessionHolder.resolveApproval("edit-proposal-99", approved = true)
        assertTrue("User decision must be accepted", userDecision)
        assertNull("Pending approval must be cleared", sessionHolder.state.pendingApproval)

        // 4. Send Connect-RPC frame for AcknowledgeCascadeCodeEdit
        val approvalFrame = ConnectFrameBuilder.createStartFrame(
            procedure = "/exa.language_server_pb.LanguageServerService/AcknowledgeCascadeCodeEdit",
            csrfToken = testCsrfToken,
            payload = buildJsonObject {
                put("cascadeId", "cascade-android-e2e-002")
                put("editId", "edit-proposal-99")
                put("accept", true)
                put("writtenFeedback", "Approved. SHA-256 verification verified.")
            }
        )

        val frameJson = json.encodeToString(ConnectFrameEnvelope.serializer(), approvalFrame)

        val request = Request.Builder()
            .url(mockServer.url("/exa.language_server_pb.LanguageServerService/AcknowledgeCascadeCodeEdit"))
            .addHeader("Content-Type", "application/json")
            .addHeader(ConnectFrameBuilder.HEADER_CSRF, testCsrfToken)
            .post(frameJson.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        assertTrue("Approval RPC must succeed with HTTP 200", response.isSuccessful)
    }

    // =========================================================================
    // Tier 4, Scenario 3: End-to-End In-App Update Check, Download, Verify & Install
    // =========================================================================
    @Test
    fun testScenario3_EndToEndInAppUpdateDelivery() = runBlocking {
        val dummyApkBytes = "Production Antigravity Android APK Binary Payload".toByteArray(Charsets.UTF_8)
        val apkChecksum = sha256Hex(dummyApkBytes)

        // 1. Mock Server enqueues Manifest response
        val manifestJson = """
            {
              "versionCode": 203201,
              "versionName": "2.32.1",
              "minSupportedVersionCode": 100000,
              "releaseDate": "2026-11-15T09:00:00Z",
              "releaseNotes": "Full feature parity release",
              "apkUrl": "${mockServer.url("/releases/antigravity-v2.32.1.apk")}",
              "fileSizeBytes": ${dummyApkBytes.size},
              "sha256": "$apkChecksum",
              "isCritical": false
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(manifestJson))

        // 2. Client checks update manifest
        val manifestRequest = Request.Builder().url(mockServer.url("/manifest.json")).build()
        val manifestResponse = httpClient.newCall(manifestRequest).execute()
        assertTrue(manifestResponse.isSuccessful)

        val parsedManifest = UpdateParser.parseNativeManifest(manifestResponse.body!!.string())
        val checkResult = UpdateParser.evaluate(localVersionCode = 100000, localVersionName = "1.0.0", manifest = parsedManifest)

        assertTrue(checkResult is UpdateCheckResult.AvailableForDownload)
        val updateHolder = UpdateStateHolder()
        updateHolder.onUpdateAvailable(parsedManifest)
        assertTrue(updateHolder.uiState.isBannerVisible)

        // 3. Mock Server enqueues APK download stream
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(dummyApkBytes))
        )

        // 4. Download and streaming SHA-256 validation
        updateHolder.onStartDownloading()
        val downloadEngine = TestDownloadEngine(httpClient)
        val progresses = downloadEngine.downloadApkStream(
            url = parsedManifest.apkUrl,
            targetDir = tempDir,
            expectedSha256 = parsedManifest.sha256,
            versionCode = parsedManifest.versionCode
        ).toList()

        assertTrue(progresses.isNotEmpty())
        assertEquals(100, progresses.last().percentage)

        val finalApkFile = File(tempDir, "update_203201.apk")
        assertTrue("Downloaded and verified APK file must exist", finalApkFile.exists())
        assertEquals(dummyApkBytes.size.toLong(), finalApkFile.length())

        updateHolder.onReady(finalApkFile.absolutePath)
        assertTrue(updateHolder.uiState.state is UpdateState.Ready)

        // 5. FileProvider & Package Installer Intent creation
        val contentUri = PackageInstallerSpec.generateFileProviderUri("com.antigravity.android", finalApkFile)
        val installIntent = PackageInstallerSpec.createInstallIntent(contentUri)

        assertEquals("android.intent.action.VIEW", installIntent.action)
        assertEquals("application/vnd.android.package-archive", installIntent.mimeType)
        assertTrue(installIntent.uriString.contains("com.antigravity.android.fileprovider"))
    }

    // =========================================================================
    // Tier 4, Scenario 4: Network Failure, Exponential Backoff, and Reconnection
    // =========================================================================
    @Test
    fun testScenario4_NetworkFailureAndExponentialBackoff() {
        val backoff = BackoffCalculator(initialDelayMs = 500L, multiplier = 1.5, maxDelayMs = 30000L)
        var status: ConnectionStatus = ConnectionStatus.Connected("http://127.0.0.1:49784")

        // 1. Socket severed
        status = ConnectionStatus.Reconnecting(attempt = 1, delayMs = backoff.calculateDelay(1))
        assertEquals(500L, (status as ConnectionStatus.Reconnecting).delayMs)

        // 2. Retry 2
        status = ConnectionStatus.Reconnecting(attempt = 2, delayMs = backoff.calculateDelay(2))
        assertEquals(750L, (status as ConnectionStatus.Reconnecting).delayMs)

        // 3. Retry 3
        status = ConnectionStatus.Reconnecting(attempt = 3, delayMs = backoff.calculateDelay(3))
        assertEquals(1125L, (status as ConnectionStatus.Reconnecting).delayMs)

        // 4. Successful reconnection
        status = ConnectionStatus.Connected("http://127.0.0.1:49784")
        assertEquals("http://127.0.0.1:49784", (status as ConnectionStatus.Connected).endpoint)
    }

    // =========================================================================
    // Tier 4, Scenario 5: Corrupt APK Download Detection & Rollback
    // =========================================================================
    @Test
    fun testScenario5_CorruptApkDetectionAndRollback() = runBlocking {
        val validBytes = "Genuine APK Bytes".toByteArray(Charsets.UTF_8)
        val alteredBytes = "Tampered / Corrupted Malicious APK Bytes".toByteArray(Charsets.UTF_8)

        val genuineSha = sha256Hex(validBytes)

        // Server serves altered bytes with mismatching genuine SHA
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(alteredBytes))
        )

        val downloadEngine = TestDownloadEngine(httpClient)
        val updateHolder = UpdateStateHolder()

        var caughtMismatch = false
        try {
            downloadEngine.downloadApkStream(
                url = mockServer.url("/corrupt.apk").toString(),
                targetDir = tempDir,
                expectedSha256 = genuineSha,
                versionCode = 203299
            ).toList()
        } catch (e: ChecksumMismatchException) {
            caughtMismatch = true
            updateHolder.onFailed(e.message ?: "Checksum mismatch")
        }

        assertTrue("Must detect checksum mismatch", caughtMismatch)
        assertTrue(updateHolder.uiState.state is UpdateState.Failed)

        val tmpFile = File(tempDir, "update_203299.apk.tmp")
        val finalApk = File(tempDir, "update_203299.apk")
        assertFalse("Corrupted .tmp file must be immediately deleted", tmpFile.exists())
        assertFalse("Corrupted final APK must not exist", finalApk.exists())
    }
}
