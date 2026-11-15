package com.antigravity.android.viewmodel

import com.antigravity.android.updater.DownloadProgress
import com.antigravity.android.updater.UpdateManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

sealed class UpdateState {
    object Idle : UpdateState()
    object CheckingForUpdates : UpdateState()
    data class AvailableForDownload(val manifest: UpdateManifest) : UpdateState()
    data class Downloading(val progress: DownloadProgress) : UpdateState()
    data class Downloaded(val apkPath: String) : UpdateState()
    data class Ready(val apkPath: String) : UpdateState()
    data class Failed(val error: String) : UpdateState()
}

data class UpdateUiState(
    val state: UpdateState = UpdateState.Idle,
    val isBannerVisible: Boolean = false,
    val isDialogVisible: Boolean = false,
    val snoozedUntil: Long = 0L
)

class UpdateStateHolder {
    private var _uiState = UpdateUiState()
    val uiState: UpdateUiState get() = _uiState

    fun onChecking() {
        _uiState = _uiState.copy(state = UpdateState.CheckingForUpdates)
    }

    fun onUpdateAvailable(manifest: UpdateManifest) {
        val now = System.currentTimeMillis()
        val showBanner = manifest.isCritical || now >= _uiState.snoozedUntil
        _uiState = _uiState.copy(
            state = UpdateState.AvailableForDownload(manifest),
            isBannerVisible = showBanner
        )
    }

    fun onStartDownloading() {
        _uiState = _uiState.copy(
            state = UpdateState.Downloading(DownloadProgress(0, 100, 0, 0))
        )
    }

    fun onDownloadProgress(progress: DownloadProgress) {
        _uiState = _uiState.copy(state = UpdateState.Downloading(progress))
    }

    fun onReady(apkPath: String) {
        _uiState = _uiState.copy(state = UpdateState.Ready(apkPath))
    }

    fun onFailed(reason: String) {
        _uiState = _uiState.copy(state = UpdateState.Failed(reason))
    }

    fun snoozeBanner(snoozeDurationMs: Long = 86400000L) { // 24 hours
        val manifest = (_uiState.state as? UpdateState.AvailableForDownload)?.manifest
        if (manifest?.isCritical == true) {
            // Critical updates cannot be snoozed
            return
        }
        _uiState = _uiState.copy(
            isBannerVisible = false,
            snoozedUntil = System.currentTimeMillis() + snoozeDurationMs
        )
    }

    fun openDialog() {
        _uiState = _uiState.copy(isDialogVisible = true)
    }

    fun closeDialog() {
        val manifest = (_uiState.state as? UpdateState.AvailableForDownload)?.manifest
        if (manifest?.isCritical == true) {
            // Cannot dismiss dialog for mandatory critical updates
            return
        }
        _uiState = _uiState.copy(isDialogVisible = false)
    }
}

class UpdateViewModelTest {

    // ==========================================
    // Tier 1: Happy-Path Update Wire Contract & UI Tiering (F13, F24)
    // ==========================================

    @Test
    fun testUpdateLifecycleStateMachine() {
        val holder = UpdateStateHolder()
        assertEquals(UpdateState.Idle, holder.uiState.state)

        holder.onChecking()
        assertEquals(UpdateState.CheckingForUpdates, holder.uiState.state)

        val manifest = UpdateManifest(
            versionCode = 203201,
            versionName = "2.32.1",
            apkUrl = "http://apk",
            sha256 = "hash"
        )
        holder.onUpdateAvailable(manifest)
        assertTrue(holder.uiState.state is UpdateState.AvailableForDownload)
        assertTrue(holder.uiState.isBannerVisible)

        holder.onStartDownloading()
        assertTrue(holder.uiState.state is UpdateState.Downloading)

        holder.onDownloadProgress(DownloadProgress(bytesRead = 50, totalBytes = 100, percentage = 50))
        val currentProgress = (holder.uiState.state as UpdateState.Downloading).progress
        assertEquals(50, currentProgress.percentage)

        holder.onReady("/cache/updates/update_203201.apk")
        assertTrue(holder.uiState.state is UpdateState.Ready)
        assertEquals("/cache/updates/update_203201.apk", (holder.uiState.state as UpdateState.Ready).apkPath)
    }

    @Test
    fun testSnoozeBanner_hidesBannerForNonCritical() {
        val holder = UpdateStateHolder()
        val manifest = UpdateManifest(
            versionCode = 200,
            versionName = "2.0.0",
            apkUrl = "http://apk",
            sha256 = "hash",
            isCritical = false
        )
        holder.onUpdateAvailable(manifest)
        assertTrue(holder.uiState.isBannerVisible)

        holder.snoozeBanner()
        assertFalse("Banner must be hidden after snooze", holder.uiState.isBannerVisible)
        assertTrue("Snooze timestamp must be in future", holder.uiState.snoozedUntil > System.currentTimeMillis())
    }

    @Test
    fun testCriticalUpdate_suppressesSnoozeAndDismiss() {
        val holder = UpdateStateHolder()
        val criticalManifest = UpdateManifest(
            versionCode = 200,
            versionName = "2.0.0",
            apkUrl = "http://apk",
            sha256 = "hash",
            isCritical = true
        )
        holder.onUpdateAvailable(criticalManifest)
        holder.openDialog()

        assertTrue(holder.uiState.isBannerVisible)
        assertTrue(holder.uiState.isDialogVisible)

        // Attempt snooze
        holder.snoozeBanner()
        assertTrue("Critical update banner cannot be snoozed", holder.uiState.isBannerVisible)

        // Attempt close dialog
        holder.closeDialog()
        assertTrue("Critical update dialog cannot be dismissed", holder.uiState.isDialogVisible)
    }

    // ==========================================
    // Tier 2: Boundary, Corner & Error Cases
    // ==========================================

    @Test
    fun testUpdateFailedTransition() {
        val holder = UpdateStateHolder()
        holder.onFailed("Network connection reset")

        assertTrue(holder.uiState.state is UpdateState.Failed)
        assertEquals("Network connection reset", (holder.uiState.state as UpdateState.Failed).error)
    }
}
