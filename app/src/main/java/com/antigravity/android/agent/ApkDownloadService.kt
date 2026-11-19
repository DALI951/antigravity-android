package com.antigravity.android.agent

import com.antigravity.android.updater.ChecksumMismatchException
import com.antigravity.android.updater.DownloadProgress
import com.antigravity.android.updater.SemVer
import com.antigravity.android.updater.UpdateParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
enum class AppSourceType {
    DIRECT_URL,
    GITHUB_RELEASE,
    FDROID_CATALOG,
    AGENT_CI_BUILD,
    LOCAL_FILE
}

@Serializable
data class CatalogAppEntry(
    val id: String,
    val appName: String,
    val packageName: String,
    val description: String,
    val versionName: String = "latest",
    val sourceType: AppSourceType,
    val githubRepo: String? = null,
    val fdroidPackage: String? = null,
    val directApkUrl: String? = null,
    val expectedSha256: String? = null,
    val category: String = "Developer & Power Tools"
)

@Serializable
data class ResolvedApkArtifact(
    val appName: String,
    val packageName: String = "",
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
    val downloadUrl: String,
    val expectedSha256: String = "",
    val fileSizeBytes: Long = 0L,
    val releaseNotes: String = "",
    val sourceType: AppSourceType = AppSourceType.DIRECT_URL
)

data class DownloadedApkResult(
    val apkFile: File,
    val sha256: String,
    val bytesDownloaded: Long,
    val artifact: ResolvedApkArtifact
)

/**
 * Service that resolves app installation requests from direct APK URLs, GitHub Releases,
 * F-Droid package repositories, or Antigravity CI builds, and streams APK downloads with
 * real-time progress callbacks and SHA-256 verification.
 */
class ApkDownloadService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
    private val defaultTargetDir: File = File(System.getProperty("java.io.tmpdir"), "antigravity_apks"),
    private val githubApiBaseUrl: String = "https://api.github.com",
    private val fdroidApiBaseUrl: String = "https://f-droid.org/api/v1",
    private val fdroidRepoBaseUrl: String = "https://f-droid.org/repo",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        val CURATED_OPEN_CATALOG: List<CatalogAppEntry> = listOf(
            CatalogAppEntry(
                id = "antigravity-android",
                appName = "Antigravity Android",
                packageName = "com.antigravity.android",
                description = "Autonomous AI Phone Agent, Swarm Orchestrator & Direct App Installer",
                versionName = "2.32.1",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "DALI951/antigravity-android",
                category = "AI & Agents"
            ),
            CatalogAppEntry(
                id = "termux",
                appName = "Termux",
                packageName = "com.termux",
                description = "Android terminal emulator and Linux environment app",
                versionName = "0.118.1",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "termux/termux-app",
                fdroidPackage = "com.termux",
                category = "Developer & Terminal"
            ),
            CatalogAppEntry(
                id = "obtainium",
                appName = "Obtainium",
                packageName = "dev.imranr.obtainium",
                description = "Get Android app updates straight from the source releases",
                versionName = "1.1.28",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "ImranR98/Obtainium",
                category = "App Managers"
            ),
            CatalogAppEntry(
                id = "fdroid",
                appName = "F-Droid",
                packageName = "org.fdroid.fdroid",
                description = "Installable catalogue of FOSS (Free and Open Source Software) applications",
                versionName = "1.21.1",
                sourceType = AppSourceType.DIRECT_URL,
                directApkUrl = "https://f-droid.org/F-Droid.apk",
                fdroidPackage = "org.fdroid.fdroid",
                category = "App Managers"
            ),
            CatalogAppEntry(
                id = "keepassdx",
                appName = "KeePassDX",
                packageName = "com.kunzisoft.keepass.free",
                description = "Lightweight password manager and vault for Android",
                versionName = "4.0.6",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "Kunzisoft/KeePassDX",
                fdroidPackage = "com.kunzisoft.keepass.free",
                category = "Security"
            ),
            CatalogAppEntry(
                id = "syncthing-fork",
                appName = "Syncthing-Fork",
                packageName = "com.github.catfriend1.syncthingandroid",
                description = "Continuous decentralized file synchronization for Android",
                versionName = "1.28.0",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "Catfriend1/syncthing-android",
                fdroidPackage = "com.github.catfriend1.syncthingandroid",
                category = "File Sync"
            ),
            CatalogAppEntry(
                id = "newpipe",
                appName = "NewPipe",
                packageName = "org.schabi.newpipe",
                description = "Lightweight libre streaming front-end for Android",
                versionName = "0.27.2",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "TeamNewPipe/NewPipe",
                fdroidPackage = "org.schabi.newpipe",
                category = "Media"
            ),
            CatalogAppEntry(
                id = "thunderbird-android",
                appName = "Thunderbird / K-9 Mail",
                packageName = "net.thunderbird.android",
                description = "Open-source email client for Android",
                versionName = "8.0",
                sourceType = AppSourceType.GITHUB_RELEASE,
                githubRepo = "thunderbird/thunderbird-android",
                fdroidPackage = "net.thunderbird.android",
                category = "Communication"
            )
        )

        fun computeFileSha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sanitizeFileName(raw: String): String {
            return raw.trim()
                .lowercase()
                .replace(Regex("""[^a-z0-9._-]"""), "_")
                .trim('_')
                .ifBlank { "downloaded_app" }
        }
    }

    fun searchCatalog(query: String): List<CatalogAppEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return CURATED_OPEN_CATALOG
        return CURATED_OPEN_CATALOG.filter { entry ->
            entry.appName.contains(trimmed, ignoreCase = true) ||
                entry.packageName.contains(trimmed, ignoreCase = true) ||
                entry.id.contains(trimmed, ignoreCase = true) ||
                entry.description.contains(trimmed, ignoreCase = true) ||
                (entry.githubRepo?.contains(trimmed, ignoreCase = true) == true)
        }
    }

    /**
     * Resolves the latest APK asset from a GitHub repository's releases (`owner/repo`).
     */
    suspend fun resolveFromGitHubRelease(
        ownerRepo: String,
        releaseTag: String = "latest"
    ): Result<ResolvedApkArtifact> = withContext(ioDispatcher) {
        runCatching {
            val cleanedRepo = ownerRepo.trim()
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("/")
                .removeSuffix("/")
                .split("/")
                .take(2)
                .joinToString("/")

            require(cleanedRepo.contains("/")) { "Invalid GitHub owner/repo: $ownerRepo" }

            val endpoint = if (releaseTag.equals("latest", ignoreCase = true)) {
                "${githubApiBaseUrl.trimEnd('/')}/repos/$cleanedRepo/releases/latest"
            } else {
                "${githubApiBaseUrl.trimEnd('/')}/repos/$cleanedRepo/releases/tags/$releaseTag"
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Antigravity-Android-Agent")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("GitHub Releases API returned HTTP ${response.code} for $cleanedRepo")
                }
                val body = response.body?.string() ?: throw IOException("Empty GitHub release response")
                val manifest = UpdateParser.parseGitHubRelease(body)
                val repoName = cleanedRepo.substringAfter("/")
                ResolvedApkArtifact(
                    appName = repoName,
                    packageName = CURATED_OPEN_CATALOG.find {
                        it.githubRepo.equals(cleanedRepo, ignoreCase = true)
                    }?.packageName ?: "",
                    versionName = manifest.versionName,
                    versionCode = manifest.versionCode,
                    downloadUrl = manifest.apkUrl,
                    expectedSha256 = manifest.sha256,
                    fileSizeBytes = manifest.fileSizeBytes,
                    releaseNotes = manifest.releaseNotes,
                    sourceType = AppSourceType.GITHUB_RELEASE
                )
            }
        }
    }

    /**
     * Resolves an APK from the F-Droid repository API (`https://f-droid.org/api/v1/packages/{pkg}`).
     */
    suspend fun resolveFromFdroid(
        packageName: String,
        appName: String = packageName
    ): Result<ResolvedApkArtifact> = withContext(ioDispatcher) {
        runCatching {
            val cleanPkg = packageName.trim()
            require(cleanPkg.isNotBlank()) { "F-Droid package name must not be blank" }

            val endpoint = "${fdroidApiBaseUrl.trimEnd('/')}/packages/$cleanPkg"
            val request = Request.Builder()
                .url(endpoint)
                .header("Accept", "application/json")
                .header("User-Agent", "Antigravity-Android-Agent")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("F-Droid API returned HTTP ${response.code} for $cleanPkg")
                }
                val body = response.body?.string() ?: throw IOException("Empty F-Droid package response")
                val root = json.parseToJsonElement(body).jsonObject

                val suggestedCode = root["suggestedVersionCode"]?.jsonPrimitive?.intOrNull
                val packagesArray = root["packages"]?.jsonArray
                val chosenPkgObj = packagesArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull {
                        suggestedCode != null && it["versionCode"]?.jsonPrimitive?.intOrNull == suggestedCode
                    }
                    ?: packagesArray?.firstOrNull()?.jsonObject

                val versionCode = chosenPkgObj?.get("versionCode")?.jsonPrimitive?.intOrNull
                    ?: suggestedCode
                    ?: throw IOException("No valid versionCode found in F-Droid metadata for $cleanPkg")
                val versionName = chosenPkgObj?.get("versionName")?.jsonPrimitive?.content ?: versionCode.toString()

                val apkUrl = "${fdroidRepoBaseUrl.trimEnd('/')}/${cleanPkg}_$versionCode.apk"
                ResolvedApkArtifact(
                    appName = appName,
                    packageName = cleanPkg,
                    versionName = versionName,
                    versionCode = versionCode,
                    downloadUrl = apkUrl,
                    expectedSha256 = "",
                    sourceType = AppSourceType.FDROID_CATALOG
                )
            }
        }
    }

    /**
     * Intelligently resolves an app query, catalog name, GitHub `owner/repo`, F-Droid package ID,
     * or direct `http(s)://...apk` URL into a downloadable [ResolvedApkArtifact].
     */
    suspend fun resolveInstallTarget(
        queryOrUrl: String,
        explicitSha256: String? = null,
        explicitAppName: String? = null
    ): Result<ResolvedApkArtifact> = withContext(ioDispatcher) {
        runCatching {
            val trimmed = queryOrUrl.trim()
            require(trimmed.isNotEmpty()) { "App query or APK URL cannot be empty" }

            // 1. Direct HTTP/HTTPS URL
            if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                // Check if it's a GitHub repository URL (not a direct .apk release download link)
                if (trimmed.contains("github.com/", ignoreCase = true) && !trimmed.endsWith(".apk", ignoreCase = true)) {
                    val resolved = resolveFromGitHubRelease(trimmed).getOrThrow()
                    return@runCatching if (!explicitSha256.isNullOrBlank()) {
                        resolved.copy(expectedSha256 = explicitSha256)
                    } else {
                        resolved
                    }
                }

                val inferredName = explicitAppName
                    ?: trimmed.substringAfterLast('/').removeSuffix(".apk").ifBlank { "DirectApp" }
                return@runCatching ResolvedApkArtifact(
                    appName = inferredName,
                    versionName = "latest",
                    versionCode = 1,
                    downloadUrl = trimmed,
                    expectedSha256 = explicitSha256?.trim() ?: "",
                    sourceType = AppSourceType.DIRECT_URL
                )
            }

            // 2. Match against curated open catalog first
            val catalogMatch = CURATED_OPEN_CATALOG.firstOrNull { entry ->
                entry.id.equals(trimmed, ignoreCase = true) ||
                    entry.appName.equals(trimmed, ignoreCase = true) ||
                    entry.packageName.equals(trimmed, ignoreCase = true)
            } ?: searchCatalog(trimmed).firstOrNull()

            if (catalogMatch != null) {
                when {
                    !catalogMatch.directApkUrl.isNullOrBlank() -> {
                        return@runCatching ResolvedApkArtifact(
                            appName = catalogMatch.appName,
                            packageName = catalogMatch.packageName,
                            versionName = catalogMatch.versionName,
                            versionCode = SemVer.parse(catalogMatch.versionName).let {
                                it.major * 100000 + it.minor * 1000 + it.patch
                            }.coerceAtLeast(1),
                            downloadUrl = catalogMatch.directApkUrl,
                            expectedSha256 = explicitSha256 ?: catalogMatch.expectedSha256.orEmpty(),
                            sourceType = catalogMatch.sourceType
                        )
                    }
                    !catalogMatch.githubRepo.isNullOrBlank() -> {
                        val ghResult = resolveFromGitHubRelease(catalogMatch.githubRepo)
                        if (ghResult.isSuccess) {
                            val artifact = ghResult.getOrThrow()
                            return@runCatching artifact.copy(
                                appName = catalogMatch.appName,
                                packageName = catalogMatch.packageName,
                                expectedSha256 = explicitSha256 ?: artifact.expectedSha256
                            )
                        }
                    }
                    !catalogMatch.fdroidPackage.isNullOrBlank() -> {
                        val fdResult = resolveFromFdroid(catalogMatch.fdroidPackage, catalogMatch.appName)
                        if (fdResult.isSuccess) {
                            val artifact = fdResult.getOrThrow()
                            return@runCatching artifact.copy(
                                expectedSha256 = explicitSha256 ?: artifact.expectedSha256
                            )
                        }
                    }
                }
            }

            // 3. GitHub owner/repo pattern (e.g. "DALI951/antigravity-android")
            if (trimmed.matches(Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"""))) {
                val gh = resolveFromGitHubRelease(trimmed).getOrThrow()
                return@runCatching if (!explicitSha256.isNullOrBlank()) {
                    gh.copy(expectedSha256 = explicitSha256)
                } else {
                    gh
                }
            }

            // 4. Android package name pattern (e.g. "org.fdroid.fdroid" or "com.termux")
            if (trimmed.matches(Regex("""^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$"""))) {
                val fd = resolveFromFdroid(trimmed, explicitAppName ?: trimmed).getOrThrow()
                return@runCatching if (!explicitSha256.isNullOrBlank()) {
                    fd.copy(expectedSha256 = explicitSha256)
                } else {
                    fd
                }
            }

            throw IllegalArgumentException("Could not resolve APK source for query: '$queryOrUrl'")
        }
    }

    /**
     * Streams an APK download from [url] to [targetDir] while emitting [DownloadProgress] updates
     * and verifying the SHA-256 checksum if [expectedSha256] is non-blank.
     */
    fun streamApkDownload(
        url: String,
        targetDir: File = defaultTargetDir,
        fileNamePrefix: String = "update",
        versionCode: Int = 1,
        expectedSha256: String? = null
    ): Flow<DownloadProgress> = flow {
        if (!targetDir.exists()) targetDir.mkdirs()
        val safePrefix = sanitizeFileName(fileNamePrefix)
        val baseFileName = "${safePrefix}_$versionCode"
        val tmpFile = File(targetDir, "$baseFileName.apk.tmp")
        val finalApk = File(targetDir, "$baseFileName.apk")

        if (tmpFile.exists()) tmpFile.delete()
        if (finalApk.exists()) finalApk.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Antigravity-Android-Agent")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Unexpected HTTP response: ${response.code}")
                }
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

                if (bytesRead == 0L) {
                    emit(DownloadProgress(0L, 0L, 100, 0L))
                }

                val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!expectedSha256.isNullOrBlank() && !computedHash.equals(expectedSha256.trim(), ignoreCase = true)) {
                    if (tmpFile.exists()) tmpFile.delete()
                    throw ChecksumMismatchException(expectedSha256.trim(), computedHash)
                }

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
    }.flowOn(ioDispatcher)

    /**
     * Downloads and validates an APK for [artifact], invoking [onProgress] as chunks arrive,
     * and returns [DownloadedApkResult] containing the verified local `.apk` file and SHA-256 digest.
     */
    suspend fun downloadAndVerifyApk(
        artifact: ResolvedApkArtifact,
        targetDir: File = defaultTargetDir,
        fileNamePrefix: String = sanitizeFileName(artifact.appName.ifBlank { "update" }),
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<DownloadedApkResult> = withContext(ioDispatcher) {
        runCatching {
            if (!targetDir.exists()) targetDir.mkdirs()
            val safePrefix = sanitizeFileName(fileNamePrefix)
            val baseFileName = "${safePrefix}_${artifact.versionCode}"
            val tmpFile = File(targetDir, "$baseFileName.apk.tmp")
            val finalApk = File(targetDir, "$baseFileName.apk")

            if (tmpFile.exists()) tmpFile.delete()
            if (finalApk.exists()) finalApk.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder()
                .url(artifact.downloadUrl)
                .header("User-Agent", "Antigravity-Android-Agent")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("Unexpected HTTP response: ${response.code}")
                    }
                    val body = response.body ?: throw IOException("Empty response body")
                    val totalBytes = if (body.contentLength() > 0) body.contentLength() else artifact.fileSizeBytes
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

                                val percent = if (totalBytes > 0) {
                                    ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
                                val speed = if (elapsedSec > 0) (bytesRead / elapsedSec).toLong() else 0L

                                onProgress(DownloadProgress(bytesRead, totalBytes, percent, speed))
                            }
                            output.flush()
                        }
                    }

                    val computedHash = digest.digest().joinToString("") { "%02x".format(it) }
                    if (artifact.expectedSha256.isNotBlank() &&
                        !computedHash.equals(artifact.expectedSha256.trim(), ignoreCase = true)
                    ) {
                        if (tmpFile.exists()) tmpFile.delete()
                        throw ChecksumMismatchException(artifact.expectedSha256.trim(), computedHash)
                    }

                    val renamed = tmpFile.renameTo(finalApk)
                    if (!renamed) {
                        tmpFile.copyTo(finalApk, overwrite = true)
                        tmpFile.delete()
                    }

                    onProgress(DownloadProgress(bytesRead, bytesRead, 100, 0L))

                    DownloadedApkResult(
                        apkFile = finalApk,
                        sha256 = computedHash,
                        bytesDownloaded = bytesRead,
                        artifact = artifact.copy(
                            expectedSha256 = computedHash,
                            fileSizeBytes = bytesRead
                        )
                    )
                }
            } catch (e: Exception) {
                if (tmpFile.exists()) tmpFile.delete()
                throw e
            }
        }
    }
}
