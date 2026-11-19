package com.antigravity.android.agent

import com.antigravity.android.updater.ChecksumMismatchException
import com.antigravity.android.updater.DownloadProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class AppInstallerManagerTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tempDir: File
    private lateinit var bridge: DefaultPackageInstallerBridge
    private lateinit var downloadService: ApkDownloadService
    private lateinit var installerManager: AppInstallerManager

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "app_installer_test_" + UUID.randomUUID())
        tempDir.mkdirs()

        bridge = DefaultPackageInstallerBridge(
            hostPackageName = "com.antigravity.android",
            installPermissionGranted = true
        )
        downloadService = ApkDownloadService(
            client = OkHttpClient(),
            defaultTargetDir = tempDir,
            githubApiBaseUrl = mockServer.url("/github").toString().removeSuffix("/"),
            fdroidApiBaseUrl = mockServer.url("/fdroid/api/v1").toString().removeSuffix("/"),
            fdroidRepoBaseUrl = mockServer.url("/fdroid/repo").toString().removeSuffix("/")
        )
        installerManager = AppInstallerManager(
            context = null,
            downloadService = downloadService,
            installerBridge = bridge
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        tempDir.deleteRecursively()
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun testInstallFromDirectUrl_streamsProgressVerifiesSha256AndCommitsSession() = runBlocking {
        val apkBytes = "Direct URL APK Binary Content Payload".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(apkBytes)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(apkBytes))
        )

        val progresses = mutableListOf<DownloadProgress>()
        val result = installerManager.installFromUrl(
            url = mockServer.url("/downloads/custom-tool.apk").toString(),
            appName = "CustomTool",
            packageName = "com.example.customtool",
            versionName = "1.4.0",
            versionCode = 140,
            expectedSha256 = expectedSha,
            useSessionApi = true,
            onProgress = { progresses.add(it) }
        )

        assertTrue("Direct URL install must succeed", result.isSuccess)
        assertTrue("Progress callbacks must be emitted", progresses.isNotEmpty())
        assertEquals(100, progresses.last().percentage)

        val outcome = result.getOrThrow()
        assertTrue(outcome is InstallOutcome.CommittedSession)
        val committed = outcome as InstallOutcome.CommittedSession
        assertEquals(expectedSha, committed.sha256)
        assertTrue(committed.apkFile.exists())
        assertEquals(apkBytes.size.toLong(), bridge.committedSessions[committed.sessionId])
        assertTrue(committed.installIntentSpec.uriString.contains("com.antigravity.android.fileprovider"))

        val history = installerManager.installHistory.value
        assertEquals(1, history.size)
        assertEquals("CustomTool", history.first().appName)
        assertEquals(expectedSha, history.first().sha256)
    }

    @Test
    fun testInstallFromGitHubRelease_resolvesLatestApkAndInstalls() = runBlocking {
        val apkBytes = "Termux GitHub Release APK Binary".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(apkBytes)
        val apkUrl = mockServer.url("/github-assets/termux-v0.118.1.apk").toString()

        val releaseJson = """
            {
              "tag_name": "v0.118.1",
              "name": "Termux v0.118.1",
              "body": "Termux release\nSHA-256: $expectedSha",
              "assets": [
                {
                  "name": "termux-app_v0.118.1+github-debug_universal.apk",
                  "size": ${apkBytes.size},
                  "browser_download_url": "$apkUrl"
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(releaseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val outcome = installerManager.installFromGitHub("termux/termux-app").getOrThrow()
        assertTrue(outcome is InstallOutcome.CommittedSession)
        val committed = outcome as InstallOutcome.CommittedSession
        assertEquals(expectedSha, committed.sha256)
        assertEquals("0.118.1", committed.artifact?.versionName)
        assertEquals("com.termux", committed.artifact?.packageName)
    }

    @Test
    fun testInstallFromFdroid_resolvesSuggestedVersionAndInstalls() = runBlocking {
        val apkBytes = "F-Droid Resolved Package APK Bytes".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(apkBytes)

        val fdroidMetaJson = """
            {
              "packageName": "org.schabi.newpipe",
              "suggestedVersionCode": 998,
              "packages": [
                { "versionName": "0.27.2", "versionCode": 998 }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(fdroidMetaJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val outcome = installerManager.installFromFdroid(
            packageName = "org.schabi.newpipe",
            appName = "NewPipe"
        ).getOrThrow()

        assertTrue(outcome is InstallOutcome.CommittedSession)
        val committed = outcome as InstallOutcome.CommittedSession
        assertEquals(expectedSha, committed.sha256)
        assertEquals("0.27.2", committed.artifact?.versionName)
        assertEquals(998, committed.artifact?.versionCode)
    }

    @Test
    fun testPermissionDenied_routesToManageUnknownAppSourcesSettings() = runBlocking {
        bridge.installPermissionGranted = false
        val apkBytes = "Test APK Requiring Permission".toByteArray(Charsets.UTF_8)

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val outcome = installerManager.installFromUrl(
            url = mockServer.url("/app.apk").toString(),
            appName = "GatedApp"
        ).getOrThrow()

        assertTrue("Must return PermissionRequired when canRequestPackageInstalls is false", outcome is InstallOutcome.PermissionRequired)
        val permReq = outcome as InstallOutcome.PermissionRequired
        assertEquals("android.settings.MANAGE_UNKNOWN_APP_SOURCES", permReq.settingsIntentSpec.action)
        assertEquals("package:com.antigravity.android", permReq.settingsIntentSpec.packageUriString)
        assertEquals(1, bridge.openedSettingsSpecs.size)
        assertEquals(InstallStage.PERMISSION_REQUIRED, installerManager.latestInstallState.value?.stage)
    }

    @Test
    fun testChecksumMismatch_failsAndCleansUpTmpFile() = runBlocking {
        val apkBytes = "Tampered APK Payload".toByteArray(Charsets.UTF_8)
        val wrongSha = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val result = installerManager.installFromUrl(
            url = mockServer.url("/tampered.apk").toString(),
            appName = "TamperedApp",
            versionCode = 77,
            expectedSha256 = wrongSha
        )

        assertTrue("Must fail on SHA-256 mismatch", result.isFailure)
        assertTrue(result.exceptionOrNull() is ChecksumMismatchException)
        assertEquals(InstallStage.FAILED, installerManager.latestInstallState.value?.stage)

        val tmpFiles = tempDir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()
        assertTrue("All temporary .tmp files must be deleted on checksum failure", tmpFiles.isEmpty())
    }

    @Test
    fun testInstallLocalApk_validatesAndInstallsAgentBuiltApk() {
        val localApk = File(tempDir, "agent_ci_build.apk")
        val payload = "On-the-fly Antigravity Agent Built APK".toByteArray(Charsets.UTF_8)
        localApk.writeBytes(payload)
        val sha = sha256Hex(payload)

        val outcome = installerManager.installLocalApk(
            apkFile = localApk,
            expectedSha256 = sha,
            appName = "AgentBuiltApp",
            packageName = "com.antigravity.generated.app"
        ).getOrThrow()

        assertTrue(outcome is InstallOutcome.CommittedSession)
        val committed = outcome as InstallOutcome.CommittedSession
        assertEquals(sha, committed.sha256)
        assertNotNull(committed.sessionId)
    }

    @Test
    fun testSearchCatalog_findsCuratedDeveloperApps() {
        val matches = installerManager.searchCatalog("termux")
        assertFalse(matches.isEmpty())
        assertEquals("com.termux", matches.first().packageName)
    }
}
