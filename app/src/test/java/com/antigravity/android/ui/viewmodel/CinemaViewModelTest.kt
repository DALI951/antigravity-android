package com.antigravity.android.ui.viewmodel

import com.antigravity.android.auth.AccountSessionManager
import com.antigravity.android.auth.AiModel
import com.antigravity.android.ui.theme.AntigravityCyan
import com.antigravity.android.ui.theme.AntigravityViolet
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianSlate
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaViewModelTest {

    @Test
    fun testObsidianCinemaThemeTokens_matchSpecification() {
        assertEquals(Color(0xFF080A0F), ObsidianDeep)
        assertEquals(Color(0xFF10141D), ObsidianSlate)
        assertEquals(Color(0xFF00F0FF), AntigravityCyan)
        assertEquals(Color(0xFF8B6FFF), AntigravityViolet)
    }

    @Test
    fun testInitialState_hasCompleteTrajectorySwarmAndPhoneHub() {
        val viewModel = CinemaViewModel()
        val state = viewModel.uiState.value

        assertEquals(CinemaDestination.COMMAND_CHAT, state.currentDestination)
        assertEquals(AiModelOption.GEMINI_4_ARGON_HIGH, state.accountState.activeModel)
        assertTrue(state.trajectoryItems.size >= 5)
        assertTrue(state.trajectoryItems.any { it.kind == TrajectoryItemKind.PLANNER_RESPONSE })
        assertTrue(state.trajectoryItems.any { it.kind == TrajectoryItemKind.TERMINAL_COMMAND })
        assertTrue(state.trajectoryItems.any { it.kind == TrajectoryItemKind.FILE_DIFF_APPROVAL })
        assertTrue(state.trajectoryItems.any { it.kind == TrajectoryItemKind.DIRECT_APP_INSTALL })

        // Swarm roster must contain working, waiting, verifier, and coordinator states
        val rosterStates = state.swarmRoster.map { it.state }.toSet()
        assertTrue(rosterStates.contains(SwarmMemberState.WORKING))
        assertTrue(rosterStates.contains(SwarmMemberState.WAITING))
        assertTrue(rosterStates.contains(SwarmMemberState.VERIFIER))
        assertTrue(rosterStates.contains(SwarmMemberState.COORDINATOR))

        // Phone tools must cover all 7 autonomous capabilities
        assertEquals(7, state.phoneTools.size)
        assertTrue(state.installQueue.isNotEmpty())
    }

    @Test
    fun testDestinationNavigationAndAccountSheetToggles() {
        val viewModel = CinemaViewModel()

        viewModel.selectDestination(CinemaDestination.SWARM_BOARD)
        assertEquals(CinemaDestination.SWARM_BOARD, viewModel.uiState.value.currentDestination)

        viewModel.selectDestination(CinemaDestination.PHONE_HUB)
        assertEquals(CinemaDestination.PHONE_HUB, viewModel.uiState.value.currentDestination)

        viewModel.setAccountSheetOpen(true)
        assertTrue(viewModel.uiState.value.isAccountSheetOpen)

        viewModel.setAccountSheetOpen(false)
        assertFalse(viewModel.uiState.value.isAccountSheetOpen)

        viewModel.setSignInDialogVisible(true)
        assertTrue(viewModel.uiState.value.accountState.isSignInDialogVisible)
    }

    @Test
    fun testQuickActionPillsAndCommandSubmission_createsPlannerAndInstallCards() {
        val viewModel = CinemaViewModel()
        val initialCount = viewModel.uiState.value.trajectoryItems.size

        viewModel.selectQuickActionPill(QuickActionPill.INSTALL_APP)
        assertEquals(QuickActionPill.INSTALL_APP, viewModel.uiState.value.selectedQuickPill)
        assertTrue(viewModel.uiState.value.commandInput.contains("install", ignoreCase = true))

        viewModel.submitCommand("Find and install K9 Mail APK on my phone")
        val updatedState = viewModel.uiState.value

        assertEquals("", updatedState.commandInput)
        assertNull(updatedState.selectedQuickPill)
        assertTrue(updatedState.trajectoryItems.size > initialCount)

        val latestInstallCard = updatedState.trajectoryItems.last().appInstallCard
        assertNotNull(latestInstallCard)
        assertTrue(latestInstallCard!!.isSha256Verified)
        assertEquals(100, latestInstallCard.downloadProgress)
    }

    @Test
    fun testThinkingToggleAndFileDiffApprovalResolution() {
        val viewModel = CinemaViewModel()
        val plannerItem = viewModel.uiState.value.trajectoryItems.first {
            it.kind == TrajectoryItemKind.PLANNER_RESPONSE
        }
        assertFalse(plannerItem.isThinkingExpanded)

        viewModel.toggleThinkingExpanded(plannerItem.id)
        val toggled = viewModel.uiState.value.trajectoryItems.first { it.id == plannerItem.id }
        assertTrue(toggled.isThinkingExpanded)

        val diffItem = viewModel.uiState.value.trajectoryItems.first {
            it.kind == TrajectoryItemKind.FILE_DIFF_APPROVAL
        }
        val editId = diffItem.fileDiffApproval!!.editId
        assertTrue(diffItem.fileDiffApproval!!.isPending)

        viewModel.resolveFileDiffApproval(editId, approved = true)
        val resolvedDiff = viewModel.uiState.value.trajectoryItems
            .first { it.id == diffItem.id }
            .fileDiffApproval!!
        assertFalse(resolvedDiff.isPending)
        assertEquals(true, resolvedDiff.isApproved)
    }

    @Test
    fun testDirectAppInstall_marksCardInstalledAndAddsToManagedApps() {
        val viewModel = CinemaViewModel()
        val targetCard = viewModel.uiState.value.installQueue.first()
        assertEquals(AppInstallStatus.READY_TO_INSTALL, targetCard.status)

        viewModel.triggerDirectAppInstall(targetCard.id)

        val updatedCard = viewModel.uiState.value.installQueue.first { it.id == targetCard.id }
        assertEquals(AppInstallStatus.INSTALLED, updatedCard.status)
        assertTrue(updatedCard.isSha256Verified)
        assertTrue(
            viewModel.uiState.value.installedApps.any { it.packageName == targetCard.packageName }
        )
    }

    @Test
    fun testSwarmBoardFilteringPostingAndWakingWaitingAgent() {
        val viewModel = CinemaViewModel()

        // Monitor starts in WAITING state
        val monitorBefore = viewModel.uiState.value.swarmRoster.first { it.name == "monitor" }
        assertEquals(SwarmMemberState.WAITING, monitorBefore.state)

        // Post message mentioning @monitor
        viewModel.postSwarmBoardMessage("@monitor Check swarm liveness now")

        val monitorAfter = viewModel.uiState.value.swarmRoster.first { it.name == "monitor" }
        assertEquals(SwarmMemberState.WORKING, monitorAfter.state)

        val lastMessage = viewModel.uiState.value.swarmMessages.last()
        assertTrue(lastMessage.mentions.contains("@monitor"))

        // Filter by VERIFIER channel
        viewModel.selectSwarmFilter(SwarmChannelFilter.VERIFIER)
        val verifierMessages = viewModel.uiState.value.filteredSwarmMessages
        assertTrue(verifierMessages.isNotEmpty())
        assertTrue(verifierMessages.all {
            it.kind == "verify" || it.mentions.contains("@verifier") || it.author == "verifier"
        })

        // Spawn helper agent
        val prevRosterCount = viewModel.uiState.value.swarmRoster.size
        viewModel.spawnHelperAgent("apk-signer", "Worker • Security", "Verify V2/V3 APK signature")
        assertEquals(prevRosterCount + 1, viewModel.uiState.value.swarmRoster.size)
    }

    @Test
    fun testPhoneHubDiscoveryToolExecutionAndOtaUpdater() {
        val viewModel = CinemaViewModel()
        val initialQueueCount = viewModel.uiState.value.installQueue.size

        viewModel.requestAgentAppDiscoveryOrBuild("https://github.com/ organicmaps/organicmaps.apk")
        assertEquals(initialQueueCount + 1, viewModel.uiState.value.installQueue.size)

        viewModel.executePhoneAgentTool("execute_shell_command")
        assertNotNull(viewModel.uiState.value.activeToolOutputBanner)
        assertTrue(viewModel.uiState.value.activeToolOutputBanner!!.contains("execute_shell_command"))

        viewModel.checkForOtaUpdates()
        viewModel.downloadAndInstallOtaUpdate()
        val otaState = viewModel.uiState.value.otaUpdateState
        assertEquals(OtaUpdateStatus.READY_TO_INSTALL, otaState.status)
        assertEquals(100, otaState.downloadProgress)
        assertNotNull(otaState.downloadedApkPath)
    }

    @Test
    fun testAccountModelSwitcherAndPcPairing_syncsWithAccountRepository() {
        val accountRepo = AccountSessionManager()
        val viewModel = CinemaViewModel(accountRepository = accountRepo)

        viewModel.selectAiModel(AiModelOption.GEMINI_PRO)
        assertEquals(AiModelOption.GEMINI_PRO, viewModel.uiState.value.accountState.activeModel)
        assertEquals(AiModel.GEMINI_PRO, accountRepo.selectedModel.value)

        viewModel.selectAiModel(AiModelOption.GEMINI_FLASH)
        assertEquals(AiModelOption.GEMINI_FLASH, viewModel.uiState.value.accountState.activeModel)
        assertEquals(AiModel.GEMINI_FLASH, accountRepo.selectedModel.value)

        viewModel.signInWithAccount(
            email = "cinema@antigravity.dev",
            displayName = "Cinema Operator",
            tokenOrApiKey = "ya29.antigravity-cinema-bearer-token"
        )
        assertTrue(viewModel.uiState.value.accountState.isSignedIn)
        assertEquals("cinema@antigravity.dev", viewModel.uiState.value.accountState.email)
        assertTrue(accountRepo.authState.value.isAuthenticated)

        viewModel.pairWithPcOrchestrator(
            endpointUrl = "ws://10.0.0.88:49784/connect-websocket",
            csrfOrPairingToken = "csrf-secret-999"
        )
        assertTrue(viewModel.uiState.value.accountState.pcPairingConnected)
        assertEquals("ws://10.0.0.88:49784/connect-websocket", viewModel.uiState.value.accountState.pcEndpointUrl)

        viewModel.signOutAccount()
        assertFalse(viewModel.uiState.value.accountState.isSignedIn)
    }
}
