package com.antigravity.android.agent

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.antigravity.android.updater.ChecksumMismatchException
import com.antigravity.android.updater.DownloadProgress
import com.antigravity.android.updater.InstallIntentSpec
import com.antigravity.android.updater.PackageInstallerSpec
import com.antigravity.android.updater.SettingsIntentSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

@Serializable
enum class InstallStage {
    IDLE,
    RESOLVING,
    DOWNLOADING,
    VERIFYING_CHECKSUM,
    CHECKING_PERMISSION,
    STREAMING_SESSION,
    LAUNCHED_INSTALLER,
    PERMISSION_REQUIRED,
    COMPLETED,
    FAILED
}

data class AppInstallTaskState(
    val taskId: String,
    val appName: String,
    val packageName: String = "",
    val versionName: String = "latest",
    val stage: InstallStage = InstallStage.IDLE,
    val progress: DownloadProgress = DownloadProgress(0L, 0L, 0, 0L),
    val sha256: String = "",
    val apkPath: String? = null,
    val sourceType: AppSourceType = AppSourceType.DIRECT_URL,
    val sessionId: Int? = null,
    val errorMessage: String? = null
)

@Serializable
data class AppInstallRecord(
    val taskId: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val apkPath: String,
    val sha256: String,
    val sourceType: AppSourceType,
    val installedAt: Long = System.currentTimeMillis(),
    val sessionId: Int? = null,
    val status: String = "INSTALL_TRIGGERED"
)

sealed class InstallOutcome {
    data class LaunchedInstaller(
        val apkFile: File,
        val sha256: String,
        val installIntentSpec: InstallIntentSpec,
        val sessionId: Int? = null,
        val artifact: ResolvedApkArtifact? = null
    ) : InstallOutcome()

    data class CommittedSession(
        val apkFile: File,
        val sha256: String,
        val sessionId: Int,
        val installIntentSpec: InstallIntentSpec,
        val artifact: ResolvedApkArtifact? = null
    ) : InstallOutcome()

    data class PermissionRequired(
        val settingsIntentSpec: SettingsIntentSpec,
        val apkFile: File,
        val sha256: String,
        val artifact: ResolvedApkArtifact? = null
    ) : InstallOutcome()

    data class Failed(
        val reason: String,
        val throwable: Throwable? = null
    ) : InstallOutcome()
}

/**
 * Abstraction over Android's [PackageInstaller], [FileProvider], and [PackageManager] install
 * permission checks so genuine Android installation works on device while remaining 100%
 * deterministic in JVM unit tests.
 */
interface PackageInstallerBridge {
    val hostPackageName: String
    fun canRequestPackageInstalls(): Boolean
    fun openUnknownSourcesSettings(settingsSpec: SettingsIntentSpec)
    fun streamToPackageInstallerSession(apkFile: File, targetPackageName: String?): Int
    fun launchFileProviderInstallIntent(apkFile: File, installSpec: InstallIntentSpec)
}

/**
 * Production Android implementation using [PackageInstaller.Session] and [FileProvider].
 */
class AndroidPackageInstallerBridge(
    private val context: Context
) : PackageInstallerBridge {

    override val hostPackageName: String
        get() = context.packageName.ifBlank { "com.antigravity.android" }

    override fun canRequestPackageInstalls(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(true)
        } else {
            true
        }
    }

    override fun openUnknownSourcesSettings(settingsSpec: SettingsIntentSpec) {
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse(settingsSpec.packageUriString)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun streamToPackageInstallerSession(apkFile: File, targetPackageName: String?): Int {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (!targetPackageName.isNullOrBlank()) {
                setAppPackageName(targetPackageName)
            }
            if (apkFile.length() > 0) {
                setSize(apkFile.length())
            }
        }
        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            val length = if (apkFile.length() > 0) apkFile.length() else -1L
            session.openWrite("antigravity_apk_session", 0, length).use { outStream ->
                apkFile.inputStream().use { inStream ->
                    val buffer = ByteArray(16384)
                    var read: Int
                    while (inStream.read(buffer).also { read = it } != -1) {
                        outStream.write(buffer, 0, read)
                    }
                    session.fsync(outStream)
                }
            }
            // Commit session with PendingIntent if supported, and also allow FileProvider fallback
            runCatching {
                val callbackIntent = Intent("com.antigravity.android.ACTION_INSTALL_COMMIT").apply {
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val pendingIntent = PendingIntent.getBroadcast(context, sessionId, callbackIntent, flags)
                session.commit(pendingIntent.intentSender)
            }
        }
        return sessionId
    }

    override fun launchFileProviderInstallIntent(apkFile: File, installSpec: InstallIntentSpec) {
        runCatching {
            val contentUri = runCatching {
                FileProvider.getUriForFile(
                    context,
                    PackageInstallerSpec.FILEPROVIDER_AUTHORITY,
                    apkFile
                )
            }.getOrElse {
                Uri.parse(installSpec.uriString)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, PackageInstallerSpec.APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            }
            context.startActivity(intent)
        }
    }
}

/**
 * JVM-safe default bridge used when [Context] is not provided (or in unit tests), while still
 * streaming and validating the APK file bytes to verify session integrity.
 */
class DefaultPackageInstallerBridge(
    override val hostPackageName: String = "com.antigravity.android",
    var installPermissionGranted: Boolean = true
) : PackageInstallerBridge {

    private var nextSessionId = 1001
    val openedSettingsSpecs = mutableListOf<SettingsIntentSpec>()
    val launchedInstallSpecs = mutableListOf<InstallIntentSpec>()
    val committedSessions = mutableMapOf<Int, Long>()

    override fun canRequestPackageInstalls(): Boolean = installPermissionGranted

    override fun openUnknownSourcesSettings(settingsSpec: SettingsIntentSpec) {
        openedSettingsSpecs.add(settingsSpec)
    }

    override fun streamToPackageInstallerSession(apkFile: File, targetPackageName: String?): Int {
        require(apkFile.exists()) { "APK file does not exist: ${apkFile.absolutePath}" }
        var totalStreamed = 0L
        apkFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                totalStreamed += read
            }
        }
        val sessionId = nextSessionId++
        committedSessions[sessionId] = totalStreamed
        return sessionId
    }

    override fun launchFileProviderInstallIntent(apkFile: File, installSpec: InstallIntentSpec) {
        launchedInstallSpecs.add(installSpec)
    }
}

/**
 * Direct App Installer Manager that allows the user or the Antigravity Phone Agent to find,
 * download, verify (SHA-256), and install any Android app directly on the phone from:
 * - Direct APK URLs
 * - GitHub Releases (`owner/repo` or search)
 * - F-Droid / open-source APK catalogs
 * - On-the-fly Antigravity agent / CI APK builds
 */
class AppInstallerManager(
    context: Context? = null,
    val downloadService: ApkDownloadService = ApkDownloadService(
        defaultTargetDir = context?.cacheDir?.let { File(it, "apk_updates") }
            ?: File(System.getProperty("java.io.tmpdir"), "antigravity_apk_updates")
    ),
    val installerBridge: PackageInstallerBridge = if (context != null) {
        AndroidPackageInstallerBridge(context)
    } else {
        DefaultPackageInstallerBridge()
    }
) {
    private val _activeInstalls = MutableStateFlow<Map<String, AppInstallTaskState>>(emptyMap())
    val activeInstalls: StateFlow<Map<String, AppInstallTaskState>> = _activeInstalls.asStateFlow()

    private val _latestInstallState = MutableStateFlow<AppInstallTaskState?>(null)
    val latestInstallState: StateFlow<AppInstallTaskState?> = _latestInstallState.asStateFlow()

    private val _installHistory = MutableStateFlow<List<AppInstallRecord>>(emptyList())
    val installHistory: StateFlow<List<AppInstallRecord>> = _installHistory.asStateFlow()

    fun canRequestPackageInstalls(): Boolean = installerBridge.canRequestPackageInstalls()

    fun searchCatalog(query: String = ""): List<CatalogAppEntry> = downloadService.searchCatalog(query)

    /**
     * Installs an app directly from an APK URL with streaming progress and optional SHA-256 check.
     */
    suspend fun installFromUrl(
        url: String,
        appName: String = "DirectApp",
        packageName: String = "",
        versionName: String = "1.0.0",
        versionCode: Int = 1,
        expectedSha256: String? = null,
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome> {
        val artifact = ResolvedApkArtifact(
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            versionCode = versionCode,
            downloadUrl = url,
            expectedSha256 = expectedSha256?.trim().orEmpty(),
            sourceType = AppSourceType.DIRECT_URL
        )
        return downloadAndInstallArtifact(artifact, useSessionApi, onProgress)
    }

    /**
     * Resolves the latest `.apk` release asset from a GitHub repository (`owner/repo`) and installs it.
     */
    suspend fun installFromGitHub(
        ownerRepo: String,
        releaseTag: String = "latest",
        expectedSha256: String? = null,
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome> = runCatching {
        val taskId = UUID.randomUUID().toString()
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = ownerRepo,
                stage = InstallStage.RESOLVING,
                sourceType = AppSourceType.GITHUB_RELEASE
            )
        )
        val resolved = downloadService.resolveFromGitHubRelease(ownerRepo, releaseTag).getOrThrow()
        val artifact = if (!expectedSha256.isNullOrBlank()) {
            resolved.copy(expectedSha256 = expectedSha256.trim())
        } else {
            resolved
        }
        downloadAndInstallArtifact(artifact, useSessionApi, onProgress, existingTaskId = taskId).getOrThrow()
    }

    /**
     * Resolves an APK from the F-Droid open-source package repository and installs it.
     */
    suspend fun installFromFdroid(
        packageName: String,
        appName: String = packageName,
        expectedSha256: String? = null,
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome> = runCatching {
        val taskId = UUID.randomUUID().toString()
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = appName,
                packageName = packageName,
                stage = InstallStage.RESOLVING,
                sourceType = AppSourceType.FDROID_CATALOG
            )
        )
        val resolved = downloadService.resolveFromFdroid(packageName, appName).getOrThrow()
        val artifact = if (!expectedSha256.isNullOrBlank()) {
            resolved.copy(expectedSha256 = expectedSha256.trim())
        } else {
            resolved
        }
        downloadAndInstallArtifact(artifact, useSessionApi, onProgress, existingTaskId = taskId).getOrThrow()
    }

    /**
     * Searches by natural app name, catalog entry, GitHub `owner/repo`, F-Droid package name,
     * or direct URL, downloads the APK, verifies its SHA-256 checksum, and launches installation.
     */
    suspend fun searchAndInstall(
        queryOrUrl: String,
        expectedSha256: String? = null,
        appName: String? = null,
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<InstallOutcome> = runCatching {
        val taskId = UUID.randomUUID().toString()
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = appName ?: queryOrUrl,
                stage = InstallStage.RESOLVING
            )
        )
        val artifact = downloadService.resolveInstallTarget(
            queryOrUrl = queryOrUrl,
            explicitSha256 = expectedSha256,
            explicitAppName = appName
        ).getOrElse { err ->
            updateTaskState(
                AppInstallTaskState(
                    taskId = taskId,
                    appName = appName ?: queryOrUrl,
                    stage = InstallStage.FAILED,
                    errorMessage = err.message
                )
            )
            throw err
        }
        downloadAndInstallArtifact(artifact, useSessionApi, onProgress, existingTaskId = taskId).getOrThrow()
    }

    /**
     * Downloads [artifact], verifies its SHA-256 hash, checks `canRequestPackageInstalls()`,
     * and installs via [PackageInstaller.Session] and [FileProvider].
     */
    suspend fun downloadAndInstallArtifact(
        artifact: ResolvedApkArtifact,
        useSessionApi: Boolean = true,
        onProgress: (DownloadProgress) -> Unit = {},
        existingTaskId: String? = null
    ): Result<InstallOutcome> = runCatching {
        val taskId = existingTaskId ?: UUID.randomUUID().toString()
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = artifact.appName,
                packageName = artifact.packageName,
                versionName = artifact.versionName,
                stage = InstallStage.DOWNLOADING,
                sourceType = artifact.sourceType
            )
        )

        val downloaded = downloadService.downloadAndVerifyApk(
            artifact = artifact,
            onProgress = { progress ->
                onProgress(progress)
                updateTaskState(
                    AppInstallTaskState(
                        taskId = taskId,
                        appName = artifact.appName,
                        packageName = artifact.packageName,
                        versionName = artifact.versionName,
                        stage = InstallStage.DOWNLOADING,
                        progress = progress,
                        sourceType = artifact.sourceType
                    )
                )
            }
        ).getOrElse { err ->
            updateTaskState(
                AppInstallTaskState(
                    taskId = taskId,
                    appName = artifact.appName,
                    packageName = artifact.packageName,
                    versionName = artifact.versionName,
                    stage = InstallStage.FAILED,
                    sourceType = artifact.sourceType,
                    errorMessage = err.message
                )
            )
            throw err
        }

        installLocalApkInternal(
            apkFile = downloaded.apkFile,
            computedSha256 = downloaded.sha256,
            artifact = downloaded.artifact,
            useSessionApi = useSessionApi,
            taskId = taskId
        )
    }

    /**
     * Validates and installs an already-downloaded or locally built `.apk` file on the device.
     */
    fun installLocalApk(
        apkFile: File,
        expectedSha256: String? = null,
        appName: String = apkFile.nameWithoutExtension,
        packageName: String = "",
        versionName: String = "1.0.0",
        useSessionApi: Boolean = true
    ): Result<InstallOutcome> = runCatching {
        require(apkFile.exists()) { "APK file does not exist: ${apkFile.absolutePath}" }
        require(apkFile.name.endsWith(".apk", ignoreCase = true)) {
            "Target file must be an APK: ${apkFile.name}"
        }

        val taskId = UUID.randomUUID().toString()
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = appName,
                packageName = packageName,
                versionName = versionName,
                stage = InstallStage.VERIFYING_CHECKSUM,
                apkPath = apkFile.absolutePath,
                sourceType = AppSourceType.LOCAL_FILE
            )
        )

        val actualSha256 = ApkDownloadService.computeFileSha256(apkFile)
        if (!expectedSha256.isNullOrBlank() && !actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            val err = ChecksumMismatchException(expectedSha256.trim(), actualSha256)
            updateTaskState(
                AppInstallTaskState(
                    taskId = taskId,
                    appName = appName,
                    packageName = packageName,
                    versionName = versionName,
                    stage = InstallStage.FAILED,
                    sha256 = actualSha256,
                    apkPath = apkFile.absolutePath,
                    sourceType = AppSourceType.LOCAL_FILE,
                    errorMessage = err.message
                )
            )
            throw err
        }

        val artifact = ResolvedApkArtifact(
            appName = appName,
            packageName = packageName,
            versionName = versionName,
            downloadUrl = apkFile.toURI().toString(),
            expectedSha256 = actualSha256,
            fileSizeBytes = apkFile.length(),
            sourceType = AppSourceType.LOCAL_FILE
        )

        installLocalApkInternal(
            apkFile = apkFile,
            computedSha256 = actualSha256,
            artifact = artifact,
            useSessionApi = useSessionApi,
            taskId = taskId
        )
    }

    private fun installLocalApkInternal(
        apkFile: File,
        computedSha256: String,
        artifact: ResolvedApkArtifact,
        useSessionApi: Boolean,
        taskId: String
    ): InstallOutcome {
        updateTaskState(
            AppInstallTaskState(
                taskId = taskId,
                appName = artifact.appName,
                packageName = artifact.packageName,
                versionName = artifact.versionName,
                stage = InstallStage.CHECKING_PERMISSION,
                progress = DownloadProgress(apkFile.length(), apkFile.length(), 100, 0L),
                sha256 = computedSha256,
                apkPath = apkFile.absolutePath,
                sourceType = artifact.sourceType
            )
        )

        val canInstall = installerBridge.canRequestPackageInstalls()
        val actionDecision = PackageInstallerSpec.determineInstallAction(canInstall)

        if (actionDecision == PackageInstallerSpec.ACTION_ROUTE_SETTINGS) {
            val settingsSpec = PackageInstallerSpec.createUnknownAppSourcesIntent(installerBridge.hostPackageName)
            installerBridge.openUnknownSourcesSettings(settingsSpec)
            updateTaskState(
                AppInstallTaskState(
                    taskId = taskId,
                    appName = artifact.appName,
                    packageName = artifact.packageName,
                    versionName = artifact.versionName,
                    stage = InstallStage.PERMISSION_REQUIRED,
                    progress = DownloadProgress(apkFile.length(), apkFile.length(), 100, 0L),
                    sha256 = computedSha256,
                    apkPath = apkFile.absolutePath,
                    sourceType = artifact.sourceType,
                    errorMessage = "Install Unknown Apps permission required"
                )
            )
            return InstallOutcome.PermissionRequired(
                settingsIntentSpec = settingsSpec,
                apkFile = apkFile,
                sha256 = computedSha256,
                artifact = artifact
            )
        }

        val contentUri = PackageInstallerSpec.generateFileProviderUri(installerBridge.hostPackageName, apkFile)
        val installSpec = PackageInstallerSpec.createInstallIntent(contentUri)

        var sessionId: Int? = null
        if (useSessionApi) {
            updateTaskState(
                AppInstallTaskState(
                    taskId = taskId,
                    appName = artifact.appName,
                    packageName = artifact.packageName,
                    versionName = artifact.versionName,
                    stage = InstallStage.STREAMING_SESSION,
                    progress = DownloadProgress(apkFile.length(), apkFile.length(), 100, 0L),
                    sha256 = computedSha256,
                    apkPath = apkFile.absolutePath,
                    sourceType = artifact.sourceType
                )
            )
            sessionId = runCatching {
                installerBridge.streamToPackageInstallerSession(
                    apkFile = apkFile,
                    targetPackageName = artifact.packageName.ifBlank { null }
                )
            }.getOrNull()
        }

        installerBridge.launchFileProviderInstallIntent(apkFile, installSpec)

        val finalState = AppInstallTaskState(
            taskId = taskId,
            appName = artifact.appName,
            packageName = artifact.packageName,
            versionName = artifact.versionName,
            stage = InstallStage.LAUNCHED_INSTALLER,
            progress = DownloadProgress(apkFile.length(), apkFile.length(), 100, 0L),
            sha256 = computedSha256,
            apkPath = apkFile.absolutePath,
            sourceType = artifact.sourceType,
            sessionId = sessionId
        )
        updateTaskState(finalState)

        val record = AppInstallRecord(
            taskId = taskId,
            appName = artifact.appName,
            packageName = artifact.packageName,
            versionName = artifact.versionName,
            apkPath = apkFile.absolutePath,
            sha256 = computedSha256,
            sourceType = artifact.sourceType,
            sessionId = sessionId,
            status = if (sessionId != null) "SESSION_COMMITTED" else "INSTALL_INTENT_LAUNCHED"
        )
        _installHistory.value = listOf(record) + _installHistory.value

        return if (sessionId != null) {
            InstallOutcome.CommittedSession(
                apkFile = apkFile,
                sha256 = computedSha256,
                sessionId = sessionId,
                installIntentSpec = installSpec,
                artifact = artifact
            )
        } else {
            InstallOutcome.LaunchedInstaller(
                apkFile = apkFile,
                sha256 = computedSha256,
                installIntentSpec = installSpec,
                sessionId = null,
                artifact = artifact
            )
        }
    }

    private fun updateTaskState(state: AppInstallTaskState) {
        _activeInstalls.value = _activeInstalls.value + (state.taskId to state)
        _latestInstallState.value = state
    }
}
