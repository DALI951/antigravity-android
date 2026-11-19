package com.antigravity.android.updater

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

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
        private val sha256Regex = Regex("""(?i)(?:sha256|sha-256)[\s:=`]*([0-9a-f]{64})""")

        fun parseNativeManifest(jsonString: String): UpdateManifest {
            return json.decodeFromString<UpdateManifest>(jsonString)
        }

        fun parseGitHubRelease(jsonString: String): UpdateManifest {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val tagName = root["tag_name"]?.jsonPrimitive?.content ?: "v0.0.0"
            val body = root["body"]?.jsonPrimitive?.content ?: ""
            val publishedAt = root["published_at"]?.jsonPrimitive?.content
            val assets = root["assets"]?.jsonArray ?: error("No assets found in GitHub release")

            val apkAsset = assets.map { it.jsonObject }.firstOrNull {
                val name = it["name"]?.jsonPrimitive?.content ?: ""
                name.endsWith(".apk", ignoreCase = true)
            } ?: error("No APK asset found in release")

            val apkUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.content ?: ""
            val size = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

            val semVer = SemVer.parse(tagName)
            val versionCode = semVer.major * 100000 + semVer.minor * 1000 + semVer.patch
            val extractedSha = extractSha256FromNotes(body)

            return UpdateManifest(
                versionCode = versionCode,
                versionName = tagName.removePrefix("v").removePrefix("V"),
                releaseDate = publishedAt,
                apkUrl = apkUrl,
                fileSizeBytes = size,
                sha256 = extractedSha,
                releaseNotes = body
            )
        }

        fun extractSha256FromNotes(notes: String): String {
            val match = sha256Regex.find(notes)
            return match?.groupValues?.getOrNull(1)?.lowercase() ?: ""
        }

        fun parseAuto(jsonString: String): UpdateManifest {
            val root = json.parseToJsonElement(jsonString).jsonObject
            return if (root.containsKey("tag_name") || root.containsKey("assets")) {
                parseGitHubRelease(jsonString)
            } else {
                parseNativeManifest(jsonString)
            }
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

/**
 * Checks GitHub Releases (`DALI951/antigravity-android` by default) or a custom update manifest
 * endpoint for newer Antigravity Android APK releases.
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val githubRepo: String = DEFAULT_GITHUB_REPO,
    private val customEndpointUrl: String? = null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val DEFAULT_GITHUB_REPO = "DALI951/antigravity-android"
        const val GITHUB_API_BASE = "https://api.github.com"

        fun buildGitHubLatestReleaseUrl(ownerRepo: String = DEFAULT_GITHUB_REPO): String {
            val cleaned = ownerRepo.trim().removePrefix("/").removeSuffix("/")
            return "$GITHUB_API_BASE/repos/$cleaned/releases/latest"
        }
    }

    val endpointUrl: String
        get() = customEndpointUrl ?: buildGitHubLatestReleaseUrl(githubRepo)

    suspend fun checkForUpdates(
        localVersionCode: Int = 1,
        localVersionName: String = "1.0.0",
        overrideUrl: String? = null
    ): UpdateCheckResult = withContext(ioDispatcher) {
        val targetUrl = overrideUrl ?: endpointUrl
        val request = Request.Builder()
            .url(targetUrl)
            .header("Accept", "application/vnd.github+json, application/json")
            .header("User-Agent", "Antigravity-Android/$localVersionName")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckResult.Error(
                        message = "Update check failed with HTTP ${response.code}",
                        statusCode = response.code
                    )
                }

                val bodyString = response.body?.string()
                if (bodyString.isNullOrBlank()) {
                    return@withContext UpdateCheckResult.Error(
                        message = "Empty update manifest response",
                        statusCode = response.code
                    )
                }

                val manifest = UpdateParser.parseAuto(bodyString)
                UpdateParser.evaluate(
                    localVersionCode = localVersionCode,
                    localVersionName = localVersionName,
                    manifest = manifest
                )
            }
        } catch (e: Exception) {
            UpdateCheckResult.Error(
                message = e.message ?: "Failed to check for updates",
                statusCode = null
            )
        }
    }
}
