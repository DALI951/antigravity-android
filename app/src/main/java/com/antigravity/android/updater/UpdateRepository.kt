package com.antigravity.android.updater

import android.content.Context
import com.antigravity.android.agent.AppSourceType
import com.antigravity.android.agent.InstallOutcome
import com.antigravity.android.agent.ResolvedApkArtifact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

typealias AppInstallerManager = com.antigravity.android.agent.AppInstallerManager
typealias ApkDownloadService = com.antigravity.android.agent.ApkDownloadService

@Serializable
data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long,
    val percentage: Int,
    val speedBytesPerSec: Long = 0L
)

class ChecksumMismatchException(val expected: String, val actual: String) :
    Exception("Checksum mismatch! Expected: $expected, Actual: $actual")

@Serializable
data class InstallIntentSpec(
    val action: String,
    val uriString: String,
    val mimeType: String,
    val flags: Int
)

@Serializable
data class SettingsIntentSpec(
    val action: String,
    val packageUriString: String
)

class PackageInstallerSpec {
    companion object {
        const val FILEPROVIDER_AUTHORITY = "com.antigravity.android.fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_INSTALL_PACKAGE = "android.intent.action.INSTALL_PACKAGE"
        const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

        const val ACTION_LAUNCH_INSTALL = "LAUNCH_INSTALL"
        const val ACTION_ROUTE_SETTINGS = "ROUTE_SETTINGS"

        fun determineInstallAction(canRequestInstalls: Boolean): String {
            return if (canRequestInstalls) ACTION_LAUNCH_INSTALL else ACTION_ROUTE_SETTINGS
        }

        fun generateFileProviderUri(packageName: String, apkFile: File): String {
            require(apkFile.name.endsWith(".apk", ignoreCase = true)) {
                "Target file must be an APK: ${apkFile.name}"
            }
            return "content://$packageName.fileprovider/apk_updates/${apkFile.name}"
        }

        fun createInstallIntent(contentUriString: String): InstallIntentSpec {
            val flags = FLAG_ACTIVITY_NEW_TASK or FLAG_GRANT_READ_URI_PERMISSION
            return InstallIntentSpec(
                action = ACTION_VIEW,
                uriString = contentUriString,
                mimeType = APK_MIME_TYPE,
                flags = flags
            )
        }

        fun createUnknownAppSourcesIntent(packageName: String): SettingsIntentSpec {
            return SettingsIntentSpec(
                action = ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                packageUriString = "package:$packageName"
            )
        }
    }
}

/**
 * Streaming download engine with SHA-256 verification and atomic `.tmp` -> `.apk` rename.
 */
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
                if (expectedSha256.isNotBlank() && !computedHash.equals(expectedSha256, ignoreCase = true)) {
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

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpToDate(val currentVersionName: String, val currentVersionCode: Int) : UpdateStatus()
    data class UpdateAvailable(val manifest: UpdateManifest) : UpdateStatus()
    data class Downloading(val manifest: UpdateManifest, val progress: DownloadProgress) : UpdateStatus()
    data class ReadyToInstall(
        val manifest: UpdateManifest,
        val apkFile: File,
        val sha256: String,
        val installIntentSpec: InstallIntentSpec
    ) : UpdateStatus()
    data class InstalledOrLaunched(
        val manifest: UpdateManifest,
        val apkFile: File,
        val sha256: String,
        val sessionId: Int? = null
    ) : UpdateStatus()
    data class PermissionRequired(
        val manifest: UpdateManifest,
        val settingsIntentSpec: SettingsIntentSpec,
        val apkFile: File
    ) : UpdateStatus()
    data class Failed(val error: String, val statusCode: Int? = null) : UpdateStatus()
}

/**
 * In-App Self-Updater Repository that checks GitHub Releases (`DALI951/antigravity-android`
 * or custom manifest endpoint) for new Antigravity Android releases, streams the APK download
 * with SHA-256 verification, and triggers native installation via [AppInstallerManager].
 */
class UpdateRepository(
    context: Context? = null,
    val updateChecker: UpdateChecker = UpdateChecker(),
    val appInstallerManager: com.antigravity.android.agent.AppInstallerManager =
        com.antigravity.android.agent.AppInstallerManager(context = context),
    private val currentVersionCode: Int = 1,
    private val currentVersionName: String = "1.0.0"
) {
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _latestManifest = MutableStateFlow<UpdateManifest?>(null)
    val latestManifest: StateFlow<UpdateManifest?> = _latestManifest.asStateFlow()

    /**
     * Checks GitHub Releases (`DALI951/antigravity-android`) or [overrideUrl] for a newer release.
     */
    suspend fun checkForUpdates(
        localVersionCode: Int = currentVersionCode,
        localVersionName: String = currentVersionName,
        overrideUrl: String? = null
    ): UpdateCheckResult {
        _updateStatus.value = UpdateStatus.Checking
        val result = updateChecker.checkForUpdates(
            localVersionCode = localVersionCode,
            localVersionName = localVersionName,
            overrideUrl = overrideUrl
        )
        when (result) {
            is UpdateCheckResult.AvailableForDownload -> {
                _latestManifest.value = result.manifest
                _updateStatus.value = UpdateStatus.UpdateAvailable(result.manifest)
            }
            is UpdateCheckResult.Idle -> {
                _updateStatus.value = UpdateStatus.UpToDate(localVersionName, localVersionCode)
            }
            is UpdateCheckResult.Error -> {
                _updateStatus.value = UpdateStatus.Failed(result.message, result.statusCode)
            }
        }
        return result
    }

    /**
     * Downloads the APK specified in [manifest] and triggers installation via [AppInstallerManager].
     */
    suspend fun downloadAndInstallUpdate(
        manifest: UpdateManifest = _latestManifest.value
            ?: error("No UpdateManifest available to download"),
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome> {
        _latestManifest.value = manifest
        _updateStatus.value = UpdateStatus.Downloading(
            manifest = manifest,
            progress = DownloadProgress(0L, manifest.fileSizeBytes, 0, 0L)
        )

        val artifact = ResolvedApkArtifact(
            appName = "Antigravity Android",
            packageName = "com.antigravity.android",
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            downloadUrl = manifest.apkUrl,
            expectedSha256 = manifest.sha256,
            fileSizeBytes = manifest.fileSizeBytes,
            releaseNotes = manifest.releaseNotes,
            sourceType = AppSourceType.GITHUB_RELEASE
        )

        val installResult = appInstallerManager.downloadAndInstallArtifact(
            artifact = artifact,
            useSessionApi = useSessionApi,
            onProgress = { progress ->
                _updateStatus.value = UpdateStatus.Downloading(manifest, progress)
                onProgress(progress)
            }
        )

        installResult.onSuccess { outcome ->
            when (outcome) {
                is InstallOutcome.CommittedSession -> {
                    _updateStatus.value = UpdateStatus.InstalledOrLaunched(
                        manifest = manifest,
                        apkFile = outcome.apkFile,
                        sha256 = outcome.sha256,
                        sessionId = outcome.sessionId
                    )
                }
                is InstallOutcome.LaunchedInstaller -> {
                    _updateStatus.value = UpdateStatus.ReadyToInstall(
                        manifest = manifest,
                        apkFile = outcome.apkFile,
                        sha256 = outcome.sha256,
                        installIntentSpec = outcome.installIntentSpec
                    )
                }
                is InstallOutcome.PermissionRequired -> {
                    _updateStatus.value = UpdateStatus.PermissionRequired(
                        manifest = manifest,
                        settingsIntentSpec = outcome.settingsIntentSpec,
                        apkFile = outcome.apkFile
                    )
                }
                is InstallOutcome.Failed -> {
                    _updateStatus.value = UpdateStatus.Failed(outcome.reason)
                }
            }
        }.onFailure { err ->
            _updateStatus.value = UpdateStatus.Failed(err.message ?: "Failed to download update")
        }

        return installResult
    }

    /**
     * Checks for updates and, if a newer release is found, immediately downloads and installs it.
     */
    suspend fun checkAndAutoUpdate(
        localVersionCode: Int = currentVersionCode,
        localVersionName: String = currentVersionName,
        overrideUrl: String? = null,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome?> = runCatching {
        when (val check = checkForUpdates(localVersionCode, localVersionName, overrideUrl)) {
            is UpdateCheckResult.AvailableForDownload -> {
                downloadAndInstallUpdate(check.manifest, onProgress = onProgress).getOrThrow()
            }
            is UpdateCheckResult.Idle -> null
            is UpdateCheckResult.Error -> throw IOException(check.message)
        }
    }

    fun reset() {
        _updateStatus.value = UpdateStatus.Idle
    }
}
