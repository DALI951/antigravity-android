package com.antigravity.android.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val deniedAction = PackageInstallerSpec.determineInstallAction(canRequestInstalls = false)
        assertEquals(PackageInstallerSpec.ACTION_ROUTE_SETTINGS, deniedAction)

        val grantedAction = PackageInstallerSpec.determineInstallAction(canRequestInstalls = true)
        assertEquals(PackageInstallerSpec.ACTION_LAUNCH_INSTALL, grantedAction)
    }
}
