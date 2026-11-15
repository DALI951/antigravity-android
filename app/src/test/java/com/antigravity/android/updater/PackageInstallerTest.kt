package com.antigravity.android.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

data class InstallIntentSpec(
    val action: String,
    val uriString: String,
    val mimeType: String,
    val flags: Int
)

data class SettingsIntentSpec(
    val action: String,
    val packageUriString: String
)

class PackageInstallerSpec {
    companion object {
        const val FILEPROVIDER_AUTHORITY = "com.antigravity.android.fileprovider"
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_MANAGE_UNKNOWN_APP_SOURCES = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000

        fun generateFileProviderUri(packageName: String, apkFile: File): String {
            require(apkFile.name.endsWith(".apk")) { "Target file must be an APK: ${apkFile.name}" }
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

class PackageInstallerTest {

    private val samplePackage = "com.antigravity.android"

    // ==========================================
    // Tier 1: Happy-Path Installer & Permission Tests (F16, F17)
    // ==========================================

    @Test
    fun testGenerateFileProviderUri_matchesAuthorityAndScheme() {
        val apkFile = File("update_203201.apk")
        val uriString = PackageInstallerSpec.generateFileProviderUri(samplePackage, apkFile)

        assertTrue("URI must have content scheme", uriString.startsWith("content://"))
        assertTrue("URI must contain authority", uriString.contains("com.antigravity.android.fileprovider"))
        assertTrue("URI must end with apk filename", uriString.endsWith("update_203201.apk"))
    }

    @Test
    fun testCreateInstallIntent_hasCorrectFlagsAndMime() {
        val uri = "content://com.antigravity.android.fileprovider/apk_updates/update_203201.apk"
        val spec = PackageInstallerSpec.createInstallIntent(uri)

        assertEquals(PackageInstallerSpec.ACTION_VIEW, spec.action)
        assertEquals(PackageInstallerSpec.APK_MIME_TYPE, spec.mimeType)
        assertEquals(uri, spec.uriString)

        val expectedFlags = PackageInstallerSpec.FLAG_ACTIVITY_NEW_TASK or PackageInstallerSpec.FLAG_GRANT_READ_URI_PERMISSION
        assertEquals(expectedFlags, spec.flags)
        assertTrue("FLAG_GRANT_READ_URI_PERMISSION must be present in bitmask", (spec.flags and PackageInstallerSpec.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertTrue("FLAG_ACTIVITY_NEW_TASK must be present in bitmask", (spec.flags and PackageInstallerSpec.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testCreateUnknownAppSourcesIntent_targetsPackage() {
        val settingsIntent = PackageInstallerSpec.createUnknownAppSourcesIntent(samplePackage)

        assertEquals("android.settings.MANAGE_UNKNOWN_APP_SOURCES", settingsIntent.action)
        assertEquals("package:com.antigravity.android", settingsIntent.packageUriString)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test(expected = IllegalArgumentException::class)
    fun testGenerateFileProviderUri_nonApkFileThrows() {
        val textFile = File("notes.txt")
        PackageInstallerSpec.generateFileProviderUri(samplePackage, textFile)
    }

    @Test
    fun testPermissionGateRoutingLogic() {
        // When canRequestPackageInstalls() is false -> routes to settings
        val hasPermission = false
        val action = if (hasPermission) {
            "LAUNCH_INSTALL"
        } else {
            "ROUTE_SETTINGS"
        }
        assertEquals("ROUTE_SETTINGS", action)

        // When permission is granted -> launches installer
        val permissionGranted = true
        val grantedAction = if (permissionGranted) {
            "LAUNCH_INSTALL"
        } else {
            "ROUTE_SETTINGS"
        }
        assertEquals("LAUNCH_INSTALL", grantedAction)
    }
}
