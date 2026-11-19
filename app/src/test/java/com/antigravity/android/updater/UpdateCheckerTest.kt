package com.antigravity.android.updater

import com.antigravity.android.agent.ApkDownloadService
import com.antigravity.android.agent.AppInstallerManager
import com.antigravity.android.agent.DefaultPackageInstallerBridge
import com.antigravity.android.agent.InstallOutcome
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
import java.net.HttpURLConnection
import java.security.MessageDigest
import java.util.UUID

class UpdateCheckerTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "updater_test_" + UUID.randomUUID())
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

    // ==========================================
    // Tier 1: Happy-Path Update Checks (F12, F13)
    // ==========================================

    @Test
    fun testParseNativeJsonManifest_successful() {
        val jsonPayload = """
            {
              "versionCode": 203201,
              "versionName": "2.32.1",
              "minSupportedVersionCode": 100000,
              "releaseDate": "2026-11-15T08:00:00Z",
              "releaseNotes": "### Antigravity Mobile 2.32.1\n- In-App Updater",
              "apkUrl": "https://storage.googleapis.com/antigravity/antigravity-v2.32.1.apk",
              "fileSizeBytes": 45188204,
              "sha256": "4a72d3ee8e945c7e3f898c558c4f0b2f69f21f1e9c719e7a750ef91e4695914f",
              "isCritical": false
            }
        """.trimIndent()

        val manifest = UpdateParser.parseNativeManifest(jsonPayload)

        assertEquals(203201, manifest.versionCode)
        assertEquals("2.32.1", manifest.versionName)
        assertEquals(100000, manifest.minSupportedVersionCode)
        assertEquals(45188204L, manifest.fileSizeBytes)
        assertEquals("4a72d3ee8e945c7e3f898c558c4f0b2f69f21f1e9c719e7a750ef91e4695914f", manifest.sha256)
        assertFalse(manifest.isCritical)
    }

    @Test
    fun testParseGitHubReleaseApiJson_successful() {
        val githubJson = """
            {
              "tag_name": "v2.32.1",
              "name": "Antigravity Release 2.32.1",
              "body": "Official Antigravity 2.32.1 release notes",
              "assets": [
                {
                  "name": "antigravity-v2.32.1.apk",
                  "size": 42000000,
                  "browser_download_url": "https://github.com/DALI951/antigravity-android/releases/download/v2.32.1/antigravity-v2.32.1.apk"
                }
              ]
            }
        """.trimIndent()

        val manifest = UpdateParser.parseGitHubRelease(githubJson)

        assertEquals("2.32.1", manifest.versionName)
        assertEquals(232001, manifest.versionCode)
        assertEquals(42000000L, manifest.fileSizeBytes)
        assertTrue(manifest.apkUrl.endsWith(".apk"))
        assertEquals("Official Antigravity 2.32.1 release notes", manifest.releaseNotes)
    }

    @Test
    fun testEvaluate_newerVersionAvailable() {
        val manifest = UpdateManifest(
            versionCode = 203201,
            versionName = "2.32.1",
            apkUrl = "https://example.com/app.apk",
            sha256 = "dummy"
        )

        val result = UpdateParser.evaluate(
            localVersionCode = 100000,
            localVersionName = "1.0.0",
            manifest = manifest
        )

        assertTrue(result is UpdateCheckResult.AvailableForDownload)
        assertEquals(203201, (result as UpdateCheckResult.AvailableForDownload).manifest.versionCode)
    }

    @Test
    fun testEvaluate_upToDateReturnsIdle() {
        val manifest = UpdateManifest(
            versionCode = 100000,
            versionName = "1.0.0",
            apkUrl = "https://example.com/app.apk",
            sha256 = "dummy"
        )

        val result = UpdateParser.evaluate(
            localVersionCode = 100000,
            localVersionName = "1.0.0",
            manifest = manifest
        )

        assertTrue(result is UpdateCheckResult.Idle)
    }

    @Test
    fun testEvaluate_downgradePreventionTreatedAsUpToDate() {
        val olderManifest = UpdateManifest(
            versionCode = 90000,
            versionName = "0.9.0",
            apkUrl = "https://example.com/old.apk",
            sha256 = "dummy"
        )

        val result = UpdateParser.evaluate(
            localVersionCode = 100000,
            localVersionName = "1.0.0",
            manifest = olderManifest
        )

        assertTrue("Downgrade must be treated as Idle/UpToDate", result is UpdateCheckResult.Idle)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testSemVerParsingAndComparison() {
        val v1 = SemVer.parse("2.32.1")
        val v2 = SemVer.parse("v2.31.0")
        val v3 = SemVer.parse("2.32.1-beta")

        assertTrue(v1 > v2)
        assertEquals(v1, v3)
        assertEquals("2.32.1", v1.toString())
    }

    @Test
    fun testMandatoryCriticalUpdateEnforcement() {
        val manifest = UpdateManifest(
            versionCode = 203201,
            versionName = "2.32.1",
            minSupportedVersionCode = 150000,
            apkUrl = "https://example.com/critical.apk",
            sha256 = "dummy",
            isCritical = false
        )

        val result = UpdateParser.evaluate(
            localVersionCode = 100000,
            localVersionName = "1.0.0",
            manifest = manifest
        )

        assertTrue(result is UpdateCheckResult.AvailableForDownload)
        assertTrue("isCritical must be promoted to true when local < minSupported", (result as UpdateCheckResult.AvailableForDownload).manifest.isCritical)
    }

    @Test(expected = Exception::class)
    fun testMalformedJson_throwsException() {
        UpdateParser.parseNativeManifest("{ malformed json: missing quotes }")
    }

    @Test(expected = Exception::class)
    fun testGitHubReleaseWithoutApk_throwsException() {
        val jsonNoApk = """
            {
              "tag_name": "v1.0.0",
              "assets": [
                { "name": "source.zip", "size": 100, "browser_download_url": "http://zip" }
              ]
            }
        """.trimIndent()
        UpdateParser.parseGitHubRelease(jsonNoApk)
    }

    @Test
    fun testMockWebServer_manifestEndpointSuccess() {
        val manifestJson = """{"versionCode": 200, "versionName": "2.0.0", "apkUrl": "http://apk", "sha256": "abc"}"""
        mockServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK).setBody(manifestJson))

        val url = mockServer.url("/manifest.json")
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            assertEquals(200, response.code)
            val parsed = UpdateParser.parseNativeManifest(response.body!!.string())
            assertEquals(200, parsed.versionCode)
        }
    }

    @Test
    fun testMockWebServer_serverError500Handling() {
        mockServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR).setBody("Server Error"))

        val url = mockServer.url("/manifest.json")
        val client = OkHttpClient()
        val request = okhttp3.Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            assertEquals(500, response.code)
            assertFalse(response.isSuccessful)
        }
    }

    // ==========================================
    // Tier 3: Live UpdateChecker & UpdateRepository Integration
    // ==========================================

    @Test
    fun testUpdateChecker_checksGitHubReleasesEndpointAndExtractsSha256() = runBlocking {
        val sampleSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        val githubPayload = """
            {
              "tag_name": "v2.40.0",
              "name": "Antigravity Android 2.40.0",
              "body": "### What's New\n- Direct App Installer\nSHA-256: $sampleSha",
              "assets": [
                {
                  "name": "antigravity-v2.40.0.apk",
                  "size": 15240000,
                  "browser_download_url": "${mockServer.url("/releases/antigravity-v2.40.0.apk")}"
                }
              ]
            }
        """.trimIndent()
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(githubPayload))

        val checker = UpdateChecker(
            client = OkHttpClient(),
            customEndpointUrl = mockServer.url("/repos/DALI951/antigravity-android/releases/latest").toString()
        )
        val result = checker.checkForUpdates(localVersionCode = 1, localVersionName = "1.0.0")

        assertTrue(result is UpdateCheckResult.AvailableForDownload)
        val manifest = (result as UpdateCheckResult.AvailableForDownload).manifest
        assertEquals("2.40.0", manifest.versionName)
        assertEquals(240000, manifest.versionCode)
        assertEquals(sampleSha, manifest.sha256)
    }

    @Test
    fun testUpdateRepository_endToEndCheckDownloadVerifyAndInstall() = runBlocking {
        val apkBytes = "Antigravity Self-Update APK Binary v3.0.0".toByteArray(Charsets.UTF_8)
        val expectedSha = sha256Hex(apkBytes)
        val apkDownloadUrl = mockServer.url("/downloads/antigravity-v3.0.0.apk").toString()

        val githubReleaseJson = """
            {
              "tag_name": "v3.0.0",
              "body": "Antigravity 3.0.0 Release\nsha256: $expectedSha",
              "assets": [
                {
                  "name": "antigravity-v3.0.0.apk",
                  "size": ${apkBytes.size},
                  "browser_download_url": "$apkDownloadUrl"
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(githubReleaseJson))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(apkBytes)))

        val bridge = DefaultPackageInstallerBridge(installPermissionGranted = true)
        val installer = AppInstallerManager(
            downloadService = ApkDownloadService(defaultTargetDir = tempDir),
            installerBridge = bridge
        )
        val checker = UpdateChecker(
            customEndpointUrl = mockServer.url("/repos/DALI951/antigravity-android/releases/latest").toString()
        )
        val repository = UpdateRepository(
            updateChecker = checker,
            appInstallerManager = installer,
            currentVersionCode = 1,
            currentVersionName = "1.0.0"
        )

        val outcome = repository.checkAndAutoUpdate().getOrThrow()
        assertNotNull("Must return an InstallOutcome when update is available", outcome)
        assertTrue(outcome is InstallOutcome.CommittedSession)
        val committed = outcome as InstallOutcome.CommittedSession
        assertEquals(expectedSha, committed.sha256)
        assertTrue(committed.apkFile.exists())
        assertTrue(repository.updateStatus.value is UpdateStatus.InstalledOrLaunched)
    }
}
