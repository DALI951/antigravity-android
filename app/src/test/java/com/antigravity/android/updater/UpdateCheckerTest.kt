package com.antigravity.android.updater

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val minSupportedVersionCode: Int = 0,
    val releaseDate: String? = null,
    val releaseNotes: String = "",
    val apkUrl: String,
    val fileSizeBytes: Long = 0L,
    val sha256: String,
    val isCritical: Boolean = false
)

sealed class UpdateCheckResult {
    data class AvailableForDownload(val manifest: UpdateManifest) : UpdateCheckResult()
    data class Idle(val message: String) : UpdateCheckResult()
    data class Error(val message: String, val statusCode: Int? = null) : UpdateCheckResult()
}

data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    companion object {
        fun parse(version: String): SemVer {
            val cleaned = version.trim().removePrefix("v").removePrefix("V")
            val parts = cleaned.split(".").mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val maj = parts.getOrElse(0) { 0 }
            val min = parts.getOrElse(1) { 0 }
            val pat = parts.getOrElse(2) { 0 }
            return SemVer(maj, min, pat)
        }
    }

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        return patch.compareTo(other.patch)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

class UpdateParser {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parseNativeManifest(jsonString: String): UpdateManifest {
            return json.decodeFromString<UpdateManifest>(jsonString)
        }

        fun parseGitHubRelease(jsonString: String): UpdateManifest {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: "v0.0.0"
            val body = root["body"]?.jsonPrimitive?.content ?: ""
            val assets = root["assets"]?.jsonArray ?: error("No assets found in GitHub release")

            val apkAsset = assets.map { it.jsonObject }.firstOrNull {
                val name = it["name"]?.jsonPrimitive?.content ?: ""
                name.endsWith(".apk")
            } ?: error("No APK asset found in release")

            val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: ""
            val size = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

            val semVer = SemVer.parse(tagName)
            val versionCode = semVer.major * 100000 + semVer.minor * 1000 + semVer.patch

            return UpdateManifest(
                versionCode = versionCode,
                versionName = tagName.removePrefix("v"),
                apkUrl = apkUrl,
                fileSizeBytes = size,
                sha256 = "", // Extracted from release body or separate asset
                releaseNotes = body
            )
        }

        fun evaluate(localVersionCode: Int, localVersionName: String, manifest: UpdateManifest): UpdateCheckResult {
            // Check mandatory minimum version
            val isMandatory = localVersionCode < manifest.minSupportedVersionCode
            val finalManifest = if (isMandatory) manifest.copy(isCritical = true) else manifest

            // Check if remote is newer by versionCode
            if (manifest.versionCode > localVersionCode) {
                return UpdateCheckResult.AvailableForDownload(finalManifest)
            }

            // Fallback: compare SemVer
            val localSemVer = SemVer.parse(localVersionName)
            val remoteSemVer = SemVer.parse(manifest.versionName)
            if (remoteSemVer > localSemVer) {
                return UpdateCheckResult.AvailableForDownload(finalManifest)
            }

            // Up to date or downgrade prevention
            return UpdateCheckResult.Idle("Application is up to date (version $localVersionName)")
        }
    }
}

class UpdateCheckerTest {

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
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
            minSupportedVersionCode = 150000, // higher than local 100000
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
        val client = okhttp3.OkHttpClient()
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
        val client = okhttp3.OkHttpClient()
        val request = okhttp3.Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            assertEquals(500, response.code)
            assertFalse(response.isSuccessful)
        }
    }
}
