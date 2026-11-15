package com.antigravity.android.updater

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percentage: Int,
    val speedBytesPerSec: Long = 0L
)

class ChecksumMismatchException(val expected: String, val actual: String) :
    Exception("Checksum mismatch! Expected: $expected, Actual: $actual")

class TestDownloadEngine(private val client: OkHttpClient = OkHttpClient()) {

    fun downloadApkStream(
        url: String,
        targetDir: File,
        expectedSha256: String,
        versionCode: Int
    ): Flow<DownloadProgress> = flow {
        if (!targetDir.exists()) targetDir.mkdirs()
        val tmpFile = File(targetDir, "update_$versionCode.apk.tmp")
        val finalApk = File(targetDir, "update_$versionCode.apk")

        if (tmpFile.exists()) tmpFile.delete()
        if (finalApk.exists()) finalApk.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(url).build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected HTTP response: ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                var bytesRead = 0L
                val startTime = System.currentTimeMillis()

                body.byteStream().use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            bytesRead += read

                            val percent = if (totalBytes > 0) ((bytesRead * 100) / totalBytes).toInt() else 0
                            val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                            val speed = if (elapsedSec > 0) (bytesRead / elapsedSec).toLong() else 0L

                            emit(DownloadProgress(bytesRead, totalBytes, percent, speed))
                        }
                        output.flush()
                    }
                }

                // Verify SHA-256
                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!computedHash.equals(expectedSha256, ignoreCase = true)) {
                    // Immediate purge of corrupted temporary file
                    if (tmpFile.exists()) tmpFile.delete()
                    throw ChecksumMismatchException(expectedSha256, computedHash)
                }

                // Atomic rename to final .apk
                val renamed = tmpFile.renameTo(finalApk)
                if (!renamed) {
                    tmpFile.copyTo(finalApk, overwrite = true)
                    tmpFile.delete()
                }
            }
        } catch (e: Exception) {
            if (tmpFile.exists()) tmpFile.delete()
            throw e
        }
    }
}

class DownloadEngineTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var tempDir: File
    private val testEngine = TestDownloadEngine()

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_updates_" + UUID.randomUUID())
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        tempDir.deleteRecursively()
    }

    private fun calculateSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }

    // ==========================================
    // Tier 1: Happy-Path Streaming & Hash Verification (F14, F15)
    // ==========================================

    @Test
    fun testSuccessfulDownloadAndSha256Verification() = runBlocking {
        val dummyPayload = "Antigravity Mock Android APK Payload Data Bytes".toByteArray(Charsets.UTF_8)
        val expectedSha = calculateSha256(dummyPayload)

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Buffer().write(dummyPayload))
        )

        val progressList = mutableListOf<DownloadProgress>()
        testEngine.downloadApkStream(
            url = mockServer.url("/app.apk").toString(),
            targetDir = tempDir,
            expectedSha256 = expectedSha,
            versionCode = 203201
        ).collect { progress ->
            progressList.add(progress)
        }

        assertTrue(progressList.isNotEmpty())
        assertEquals(dummyPayload.size.toLong(), progressList.last().bytesRead)
        assertEquals(100, progressList.last().percentage)

        val finalApk = File(tempDir, "update_203201.apk")
        assertTrue("Final APK file must exist", finalApk.exists())
        assertEquals(dummyPayload.size.toLong(), finalApk.length())

        val tmpFile = File(tempDir, "update_203201.apk.tmp")
        assertFalse(".tmp file must be cleaned up / renamed", tmpFile.exists())
    }

    @Test
    fun testCaseInsensitiveSha256Comparison() = runBlocking {
        val dummyPayload = "Upper case test bytes".toByteArray(Charsets.UTF_8)
        val expectedShaLower = calculateSha256(dummyPayload)
        val expectedShaUpper = expectedShaLower.uppercase()

        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(dummyPayload))
        )

        testEngine.downloadApkStream(
            url = mockServer.url("/app.apk").toString(),
            targetDir = tempDir,
            expectedSha256 = expectedShaUpper,
            versionCode = 203202
        ).collect {}

        val finalApk = File(tempDir, "update_203202.apk")
        assertTrue(finalApk.exists())
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testChecksumMismatch_immediatelyDeletesCorruptedTmpFile() = runBlocking {
        val dummyPayload = "Corrupted Payload".toByteArray(Charsets.UTF_8)
        val wrongSha = "0000000000000000000000000000000000000000000000000000000000000000"

        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(dummyPayload))
        )

        var thrown = false
        try {
            testEngine.downloadApkStream(
                url = mockServer.url("/app.apk").toString(),
                targetDir = tempDir,
                expectedSha256 = wrongSha,
                versionCode = 203203
            ).collect {}
        } catch (e: ChecksumMismatchException) {
            thrown = true
        }

        assertTrue("Must throw ChecksumMismatchException", thrown)
        val tmpFile = File(tempDir, "update_203203.apk.tmp")
        val finalApk = File(tempDir, "update_203203.apk")
        assertFalse("Corrupted .tmp file must be purged", tmpFile.exists())
        assertFalse("Final APK must not be created on mismatch", finalApk.exists())
    }

    @Test
    fun testServerDisconnect_cleansUpPartialTmpFile() = runBlocking {
        // Enqueue truncated stream
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setChunkedBody("Incomplete chunk", 5)
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END)
        )

        var caught = false
        try {
            testEngine.downloadApkStream(
                url = mockServer.url("/truncated.apk").toString(),
                targetDir = tempDir,
                expectedSha256 = "dummy",
                versionCode = 203204
            ).collect {}
        } catch (e: Exception) {
            caught = true
        }

        assertTrue("Must throw network / IO exception", caught)
        val tmpFile = File(tempDir, "update_203204.apk.tmp")
        assertFalse("Partial .tmp file must be deleted on failure", tmpFile.exists())
    }

    @Test
    fun testZeroBytePayload_handledCleanly() = runBlocking {
        val emptyBytes = ByteArray(0)
        val expectedSha = calculateSha256(emptyBytes)

        mockServer.enqueue(
            MockResponse().setResponseCode(200).setBody(Buffer().write(emptyBytes))
        )

        testEngine.downloadApkStream(
            url = mockServer.url("/empty.apk").toString(),
            targetDir = tempDir,
            expectedSha256 = expectedSha,
            versionCode = 203205
        ).collect {}

        val finalApk = File(tempDir, "update_203205.apk")
        assertTrue(finalApk.exists())
        assertEquals(0L, finalApk.length())
    }
}
