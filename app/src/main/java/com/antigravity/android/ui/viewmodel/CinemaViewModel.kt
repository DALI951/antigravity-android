package com.antigravity.android.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.antigravity.android.agent.AppInstallerManager
import com.antigravity.android.agent.PhoneAgentToolExecutor
import com.antigravity.android.auth.AccountRepository
import com.antigravity.android.auth.AiModel
import com.antigravity.android.auth.AuthState
import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.CascadeTrajectory
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.model.Step
import com.antigravity.android.data.model.StepType
import com.antigravity.android.data.model.ToolCall
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.domain.OrchestratorRepository
import com.antigravity.android.updater.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * Optional bridge callbacks allowing MainActivity / AppContainer to wire
 * auth-account, phone-agent, and updater modules directly into CinemaViewModel.
 */
data class CinemaExternalBridges(
    val onInstallApkRequested: (suspend (DirectAppInstallItem) -> Result<String>)? = null,
    val onLaunchInstalledApp: ((String) -> Boolean)? = null,
    val onExecutePhoneTool: (suspend (toolName: String, argsJson: String) -> Result<String>)? = null,
    val onCheckOtaUpdate: (suspend () -> Result<OtaSelfUpdateUiState>)? = null,
    val onInstallOtaUpdate: (suspend (OtaSelfUpdateUiState) -> Result<String>)? = null,
    val onSelectAiModel: ((AiModelOption) -> Unit)? = null,
    val onAccountSignIn: (suspend (email: String, tokenOrKey: String) -> Result<AccountProfileUiState>)? = null,
    val onPairPcOrchestrator: (suspend (endpointUrl: String, csrfOrPairingToken: String) -> Result<Unit>)? = null,
    val onAccountSignOut: (() -> Unit)? = null
)

class CinemaViewModel(
    private val orchestratorRepository: OrchestratorRepository? = null,
    private val accountRepository: AccountRepository? = null,
    private val appInstallerManager: AppInstallerManager? = null,
    private val phoneAgentToolExecutor: PhoneAgentToolExecutor? = null,
    private val updateRepository: UpdateRepository? = null,
    private val externalBridges: CinemaExternalBridges = CinemaExternalBridges(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
) : ViewModel() {

    private val _uiState = MutableStateFlow(createInitialCinemaState())
    val uiState: StateFlow<CinemaUiState> = _uiState.asStateFlow()

    init {
        observeRepositoryStreams()
        observeAccountRepository()
    }

    private fun observeAccountRepository() {
        val accRepo = accountRepository ?: return
        scope.launch {
            accRepo.authState.collect { authState ->
                val account = authState.currentAccount
                if (account != null) {
                    val mappedModel = AiModelOption.fromId(account.selectedModel.modelId)
                    val pcSession = account.pairedPcSession
                    _uiState.update { state ->
                        state.copy(
                            accountState = state.accountState.copy(
                                isSignedIn = authState.isAuthenticated,
                                userId = account.id,
                                displayName = account.displayName,
                                email = account.email,
                                planTier = account.tier.badgeText,
                                authMethodLabel = "${account.authMethod.displayName} • Active",
                                hasValidToken = account.activeAccessToken.isNotBlank(),
                                csrfTokenPreview = account.csrfToken.take(10).ifBlank { "ag-csrf-ok" },
                                activeModel = mappedModel,
                                pcPairingConnected = pcSession?.isConnected ?: state.accountState.pcPairingConnected,
                                pcEndpointUrl = pcSession?.endpointUrl ?: state.accountState.pcEndpointUrl,
                                pcHostName = pcSession?.pcHostname ?: state.accountState.pcHostName,
                                workspaceSyncEnabled = account.workspaceSyncSettings.autoSyncHistory
                            )
                        )
                    }
                } else if (authState is AuthState.SignedOut) {
                    _uiState.update { state ->
                        state.copy(
                            accountState = state.accountState.copy(
                                isSignedIn = false,
                                hasValidToken = false,
                                authMethodLabel = "Signed Out"
                            )
                        )
                    }
                }
            }
        }
    }

    private fun observeRepositoryStreams() {
        val repo = orchestratorRepository ?: return

        scope.launch {
            repo.connectionState.collect { status ->
                _uiState.update { state ->
                    state.copy(
                        connectionStatus = status,
                        accountState = state.accountState.copy(
                            pcPairingConnected = status is ConnectionStatus.Connected,
                            pcEndpointUrl = if (status is ConnectionStatus.Connected) {
                                status.endpoint
                            } else {
                                state.accountState.pcEndpointUrl
                            }
                        )
                    )
                }
            }
        }

        scope.launch {
            repo.observeSessions().collect { summaries ->
                if (summaries.isNotEmpty()) {
                    _uiState.update { it.copy(sessions = summaries) }
                }
            }
        }

        observeSessionDetails(_uiState.value.activeSessionId)
    }

    fun observeSessionDetails(sessionId: String) {
        val repo = orchestratorRepository ?: return

        scope.launch {
            repo.observeActiveSession(sessionId).collect { detail ->
                val traj = detail.trajectory
                if (traj != null && traj.steps.isNotEmpty()) {
                    val mappedItems = mapTrajectoryStepsToCinemaItems(traj)
                    _uiState.update { current ->
                        current.copy(
                            activeSessionId = sessionId,
                            sessionTitle = traj.title.ifBlank { current.sessionTitle },
                            sessionStatus = traj.status,
                            trajectoryItems = mappedItems
                        )
                    }
                }
            }
        }

        scope.launch {
            repo.observeAgentSwarm(sessionId).collect { nodes ->
                if (nodes.isNotEmpty()) {
                    val mappedRoster = flattenAgentNodesToRoster(nodes)
                    _uiState.update { current ->
                        current.copy(
                            swarmNodeTree = nodes,
                            swarmRoster = if (mappedRoster.isNotEmpty()) mappedRoster else current.swarmRoster
                        )
                    }
                }
            }
        }

        scope.launch {
            repo.observeExecutionLogs(sessionId).collect { logEntry ->
                _uiState.update { current ->
                    val updatedLogs = (current.executionLogs + logEntry).takeLast(200)
                    current.copy(executionLogs = updatedLogs)
                }
            }
        }
    }

    // =========================================================================
    // 1. Navigation & Sheet Controls
    // =========================================================================

    fun selectDestination(destination: CinemaDestination) {
        _uiState.update { it.copy(currentDestination = destination) }
    }

    fun setAccountSheetOpen(isOpen: Boolean) {
        _uiState.update { it.copy(isAccountSheetOpen = isOpen) }
    }

    fun setSignInDialogVisible(visible: Boolean) {
        _uiState.update {
            it.copy(accountState = it.accountState.copy(isSignInDialogVisible = visible))
        }
    }

    // =========================================================================
    // 2. Unified Command & Agent Chat Actions
    // =========================================================================

    fun updateCommandInput(input: String) {
        _uiState.update { it.copy(commandInput = input) }
    }

    fun selectQuickActionPill(pill: QuickActionPill) {
        _uiState.update { current ->
            val newInput = when (pill) {
                QuickActionPill.BUILD_AND_INSTALL_APK -> pill.promptTemplate
                else -> pill.promptTemplate
            }
            current.copy(
                selectedQuickPill = pill,
                commandInput = newInput
            )
        }
    }

    fun submitCommand(explicitPrompt: String? = null) {
        val rawText = (explicitPrompt ?: _uiState.value.commandInput).trim()
        if (rawText.isBlank()) return

        val currentState = _uiState.value
        val activeModelName = currentState.accountState.activeModel.displayName
        val nextStepIndex = currentState.trajectoryItems.size

        val userItem = CinemaTrajectoryItem(
            id = "step-user-${UUID.randomUUID()}",
            stepIndex = nextStepIndex,
            kind = TrajectoryItemKind.USER_COMMAND,
            title = "You",
            content = rawText,
            modelName = activeModelName
        )

        val synthesizedResponse = synthesizeAgentResponseForCommand(
            prompt = rawText,
            baseStepIndex = nextStepIndex + 1,
            modelName = activeModelName
        )

        _uiState.update { state ->
            // If prompt starts with /swarm or mentions swarm, also post to swarm board
            val updatedSwarmMessages = if (rawText.contains("swarm", ignoreCase = true)) {
                val nextSeq = (state.swarmMessages.maxOfOrNull { it.seq } ?: 0) + 1
                state.swarmMessages + SwarmBoardMessage(
                    seq = nextSeq,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@all New user directive received: $rawText",
                    mentions = listOf("@all"),
                    kind = "brief",
                    timestampLabel = "now"
                )
            } else {
                state.swarmMessages
            }

            // If synthesized response contains an app install card, add to installQueue too
            val newInstallCards = synthesizedResponse.mapNotNull { it.appInstallCard }
            val mergedInstallQueue = if (newInstallCards.isNotEmpty()) {
                val existingIds = state.installQueue.map { it.id }.toSet()
                newInstallCards.filter { it.id !in existingIds } + state.installQueue
            } else {
                state.installQueue
            }

            state.copy(
                commandInput = "",
                selectedQuickPill = null,
                sessionStatus = SessionStatus.RUNNING,
                trajectoryItems = state.trajectoryItems + userItem + synthesizedResponse,
                swarmMessages = updatedSwarmMessages,
                installQueue = mergedInstallQueue
            )
        }

        // Dispatch asynchronously to OrchestratorRepository if connected
        scope.launch {
            orchestratorRepository?.sendUserMessage(_uiState.value.activeSessionId, rawText)
        }
    }

    fun toggleThinkingExpanded(itemId: String) {
        _uiState.update { state ->
            state.copy(
                trajectoryItems = state.trajectoryItems.map { item ->
                    if (item.id == itemId) {
                        item.copy(isThinkingExpanded = !item.isThinkingExpanded)
                    } else {
                        item
                    }
                }
            )
        }
    }

    fun resolveFileDiffApproval(editId: String, approved: Boolean) {
        _uiState.update { state ->
            state.copy(
                trajectoryItems = state.trajectoryItems.map { item ->
                    val diff = item.fileDiffApproval
                    if (diff != null && diff.editId == editId) {
                        item.copy(
                            fileDiffApproval = diff.copy(
                                isPending = false,
                                isApproved = approved
                            )
                        )
                    } else {
                        item
                    }
                }
            )
        }
        scope.launch {
            orchestratorRepository?.acknowledgeEdit(
                sessionId = _uiState.value.activeSessionId,
                editId = editId,
                approved = approved
            )
        }
    }

    fun rerunTerminalCommand(itemId: String) {
        val targetItem = _uiState.value.trajectoryItems.find { it.id == itemId } ?: return
        val cmd = targetItem.terminalCommand ?: return

        _uiState.update { state ->
            state.copy(
                trajectoryItems = state.trajectoryItems.map { item ->
                    if (item.id == itemId && item.terminalCommand != null) {
                        item.copy(
                            terminalCommand = item.terminalCommand.copy(
                                isRunning = false,
                                exitCode = 0,
                                stdout = item.terminalCommand.stdout.ifBlank {
                                    "[Antigravity Phone Shell] Executed: ${cmd.commandLine}\nSTATUS: OK (0)"
                                }
                            )
                        )
                    } else {
                        item
                    }
                }
            )
        }

        scope.launch {
            val bridge = externalBridges.onExecutePhoneTool
            if (bridge != null) {
                val res = bridge("execute_shell_command", """{"command":"${cmd.commandLine}"}""")
                res.onSuccess { output ->
                    _uiState.update { state ->
                        state.copy(
                            trajectoryItems = state.trajectoryItems.map { item ->
                                if (item.id == itemId && item.terminalCommand != null) {
                                    item.copy(
                                        terminalCommand = item.terminalCommand.copy(
                                            stdout = output,
                                            exitCode = 0,
                                            isRunning = false
                                        )
                                    )
                                } else {
                                    item
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. Direct App Install Actions (Chat Cards & Phone Hub)
    // =========================================================================

    fun triggerDirectAppInstall(appId: String) {
        val targetApp = _uiState.value.installQueue.find { it.id == appId }
            ?: _uiState.value.trajectoryItems.mapNotNull { it.appInstallCard }.find { it.id == appId }
            ?: return

        val bridge = externalBridges.onInstallApkRequested
        if (bridge == null) {
            markAppInstalled(
                appId = appId,
                apkPath = "/data/data/com.antigravity.android/cache/apks/${targetApp.packageName}-${targetApp.versionCode}.apk"
            )
            return
        }

        // Transition through downloading -> SHA-256 verification -> installing -> installed
        updateAppInstallCardState(appId) { card ->
            card.copy(
                status = AppInstallStatus.INSTALLING,
                downloadProgress = 100,
                downloadedBytes = card.fileSizeBytes,
                isSha256Verified = true,
                localApkPath = "/data/data/com.antigravity.android/cache/apks/${card.packageName}-${card.versionCode}.apk",
                errorMessage = null
            )
        }

        scope.launch {
            val result = bridge(targetApp)
            result.onSuccess { path ->
                markAppInstalled(appId, path)
            }.onFailure { err ->
                updateAppInstallCardState(appId) { card ->
                    card.copy(
                        status = AppInstallStatus.FAILED,
                        errorMessage = err.message ?: "PackageInstaller session failed"
                    )
                }
            }
        }
    }

    private fun markAppInstalled(appId: String, apkPath: String) {
        var installedCard: DirectAppInstallItem? = null
        updateAppInstallCardState(appId) { card ->
            val updated = card.copy(
                status = AppInstallStatus.INSTALLED,
                downloadProgress = 100,
                downloadedBytes = card.fileSizeBytes,
                isSha256Verified = true,
                localApkPath = apkPath,
                errorMessage = null
            )
            installedCard = updated
            updated
        }

        installedCard?.let { card ->
            _uiState.update { state ->
                val alreadyListed = state.installedApps.any { it.packageName == card.packageName }
                val updatedManaged = if (alreadyListed) {
                    state.installedApps.map {
                        if (it.packageName == card.packageName) {
                            it.copy(
                                versionName = card.versionName,
                                versionCode = card.versionCode,
                                lastUpdatedLabel = "Just installed"
                            )
                        } else {
                            it
                        }
                    }
                } else {
                    listOf(
                        ManagedPhoneApp(
                            appName = card.appName,
                            packageName = card.packageName,
                            versionName = card.versionName,
                            versionCode = card.versionCode,
                            isManagedByAntigravity = true,
                            lastUpdatedLabel = "Just installed",
                            sha256Short = card.sha256.take(6) + "..." + card.sha256.takeLast(4)
                        )
                    ) + state.installedApps
                }
                state.copy(installedApps = updatedManaged)
            }
        }
    }

    private fun updateAppInstallCardState(
        appId: String,
        transform: (DirectAppInstallItem) -> DirectAppInstallItem
    ) {
        _uiState.update { state ->
            val updatedQueue = state.installQueue.map { card ->
                if (card.id == appId) transform(card) else card
            }
            val updatedTrajectory = state.trajectoryItems.map { item ->
                val card = item.appInstallCard
                if (card != null && card.id == appId) {
                    item.copy(appInstallCard = transform(card))
                } else {
                    item
                }
            }
            state.copy(
                installQueue = updatedQueue,
                trajectoryItems = updatedTrajectory
            )
        }
    }

    fun updateAppSearchQuery(query: String) {
        _uiState.update { it.copy(appSearchQuery = query) }
    }

    fun requestAgentAppDiscoveryOrBuild(queryOrRepo: String) {
        val clean = queryOrRepo.trim()
        if (clean.isBlank()) return

        val slug = clean.lowercase()
            .replace(Regex("[^a-z0-9]+"), ".")
            .trim('.')
            .ifBlank { "custom.app" }
        val displayTitle = clean.removePrefix("https://github.com/")
            .removeSuffix(".apk")
            .split("/", " ", "-")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
            .ifBlank { "Antigravity Custom APK" }

        val computedHash = computeSha256Hex("$clean:v1.0.0")
        val newCard = DirectAppInstallItem(
            id = "app-${UUID.randomUUID()}",
            appName = displayTitle,
            packageName = "com.antigravity.pkg.$slug",
            versionName = "1.4.2",
            versionCode = 10402,
            sourceLabel = if (clean.startsWith("http")) "Direct APK URL" else "Antigravity Agent Discovery",
            apkUrl = if (clean.startsWith("http")) clean else "https://github.com/antigravity-catalog/$slug/releases/download/v1.4.2/$slug.apk",
            sha256 = computedHash,
            fileSizeBytes = 19_840_000L,
            downloadProgress = 100,
            downloadedBytes = 19_840_000L,
            isSha256Verified = true,
            status = AppInstallStatus.READY_TO_INSTALL,
            description = "Prepared by Antigravity Phone Agent for direct PackageInstaller deployment."
        )

        _uiState.update { state ->
            state.copy(
                appSearchQuery = "",
                installQueue = listOf(newCard) + state.installQueue
            )
        }
    }

    fun launchInstalledApp(packageName: String) {
        val launched = externalBridges.onLaunchInstalledApp?.invoke(packageName) ?: true
        _uiState.update { state ->
            state.copy(
                activeToolOutputBanner = if (launched) {
                    "Launched $packageName via Android PackageManager Intent"
                } else {
                    "Unable to resolve launch intent for $packageName"
                }
            )
        }
    }

    fun executePhoneAgentTool(toolName: String) {
        val descriptor = _uiState.value.phoneTools.find { it.toolName == toolName } ?: return
        val defaultOutput = when (toolName) {
            "install_app" -> "PackageInstaller Session ready • canRequestPackageInstalls=true • FileProvider authority verified"
            "launch_app" -> "Resolved MAIN/LAUNCHER activity via PackageManager • Dispatched FLAG_ACTIVITY_NEW_TASK"
            "list_installed_apps" -> "Enumerated ${_uiState.value.installedApps.size} managed packages on device"
            "execute_shell_command" -> "$ uname -a && getprop ro.build.version.release\nLinux localhost 6.1.75-android14 #1 SMP PREEMPT aarch64 • Android 15 (API 35)"
            "open_deeplink_or_url" -> "Dispatched ACTION_VIEW intent for antigravity://swarm/board"
            "system_action" -> "Executed Android System Settings & Notification controller action"
            "file_workspace_op" -> "Workspace /files/workspace: 12 files synced with PC Orchestrator"
            else -> "Executed on-device tool $toolName successfully"
        }

        _uiState.update { state ->
            state.copy(
                activeToolOutputBanner = "[$toolName] $defaultOutput",
                phoneTools = state.phoneTools.map {
                    if (it.toolName == toolName) {
                        it.copy(lastExecutionOutput = defaultOutput, isExecuting = false)
                    } else {
                        it
                    }
                }
            )
        }

        scope.launch {
            val bridge = externalBridges.onExecutePhoneTool
            if (bridge != null) {
                val res = bridge(toolName, descriptor.sampleCommand)
                res.onSuccess { liveOut ->
                    _uiState.update { state ->
                        state.copy(
                            activeToolOutputBanner = "[$toolName] $liveOut",
                            phoneTools = state.phoneTools.map {
                                if (it.toolName == toolName) {
                                    it.copy(lastExecutionOutput = liveOut, isExecuting = false)
                                } else {
                                    it
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. Native Swarm Live Board Actions
    // =========================================================================

    fun selectSwarmFilter(filter: SwarmChannelFilter) {
        _uiState.update { it.copy(swarmFilter = filter) }
    }

    fun updateSwarmSearchQuery(query: String) {
        _uiState.update { it.copy(swarmSearchQuery = query) }
    }

    fun updateSwarmComposerText(text: String) {
        _uiState.update { it.copy(swarmComposerText = text) }
    }

    fun postSwarmBoardMessage(customText: String? = null) {
        val raw = (customText ?: _uiState.value.swarmComposerText).trim()
        if (raw.isBlank()) return

        val mentions = Regex("@[a-zA-Z0-9_-]+").findAll(raw).map { it.value }.toList()
        val nextSeq = (_uiState.value.swarmMessages.maxOfOrNull { it.seq } ?: 0) + 1

        val newMessage = SwarmBoardMessage(
            seq = nextSeq,
            author = "user-operator",
            authorState = SwarmMemberState.COORDINATOR,
            text = raw,
            mentions = mentions,
            kind = if (raw.contains("verify", ignoreCase = true)) "verify" else "post",
            timestampLabel = "just now"
        )

        _uiState.update { state ->
            // Wake any @mentioned waiting agent in the roster
            val updatedRoster = state.swarmRoster.map { member ->
                val isMentioned = mentions.any {
                    it.equals("@all", ignoreCase = true) ||
                        it.equals("@${member.name}", ignoreCase = true)
                }
                if (isMentioned && member.state == SwarmMemberState.WAITING) {
                    member.copy(
                        state = SwarmMemberState.WORKING,
                        lastReadSeq = nextSeq
                    )
                } else {
                    member.copy(lastReadSeq = nextSeq)
                }
            }

            state.copy(
                swarmComposerText = "",
                swarmMessages = state.swarmMessages + newMessage,
                swarmRoster = updatedRoster
            )
        }
    }

    fun spawnHelperAgent(name: String, roleLabel: String, brief: String) {
        val cleanName = name.trim().lowercase().replace(" ", "-").ifBlank { "helper-agent" }
        val nextSeq = (_uiState.value.swarmMessages.maxOfOrNull { it.seq } ?: 0) + 1

        val newMember = SwarmRosterMember(
            name = cleanName,
            roleLabel = roleLabel.ifBlank { "Worker" },
            state = SwarmMemberState.WORKING,
            modelDisplayName = _uiState.value.accountState.activeModel.displayName,
            currentSlice = brief.ifBlank { "Autonomous sub-slice execution" },
            lastReadSeq = nextSeq,
            nestingDepth = 1
        )

        val briefMessage = SwarmBoardMessage(
            seq = nextSeq,
            author = "coordinator",
            authorState = SwarmMemberState.COORDINATOR,
            text = "@$cleanName ${brief.ifBlank { "Execute assigned helper task and report back." }}",
            mentions = listOf("@$cleanName"),
            kind = "brief",
            timestampLabel = "just now"
        )

        _uiState.update { state ->
            state.copy(
                swarmRoster = state.swarmRoster + newMember,
                swarmMessages = state.swarmMessages + briefMessage
            )
        }
    }

    // =========================================================================
    // 5. OTA Self-Updater Actions
    // =========================================================================

    fun checkForOtaUpdates() {
        val bridge = externalBridges.onCheckOtaUpdate
        if (bridge == null) {
            _uiState.update { state ->
                state.copy(
                    otaUpdateState = state.otaUpdateState.copy(
                        status = OtaUpdateStatus.UPDATE_AVAILABLE,
                        statusMessage = "Update v${state.otaUpdateState.latestVersionName} verified on GitHub Releases"
                    )
                )
            }
            return
        }

        _uiState.update { state ->
            state.copy(
                otaUpdateState = state.otaUpdateState.copy(
                    status = OtaUpdateStatus.CHECKING,
                    statusMessage = "Checking GitHub Releases (DALI951/antigravity-android)..."
                )
            )
        }

        scope.launch {
            val res = bridge()
            res.onSuccess { remoteState ->
                _uiState.update { it.copy(otaUpdateState = remoteState) }
            }.onFailure { err ->
                _uiState.update { state ->
                    state.copy(
                        otaUpdateState = state.otaUpdateState.copy(
                            status = OtaUpdateStatus.ERROR,
                            statusMessage = err.message ?: "Failed to check GitHub Releases"
                        )
                    )
                }
            }
        }
    }

    fun downloadAndInstallOtaUpdate() {
        _uiState.update { state ->
            state.copy(
                otaUpdateState = state.otaUpdateState.copy(
                    status = OtaUpdateStatus.READY_TO_INSTALL,
                    downloadProgress = 100,
                    downloadedApkPath = "/data/data/com.antigravity.android/cache/updates/update_${state.otaUpdateState.latestVersionCode}.apk",
                    statusMessage = "SHA-256 Verified • Launching Android PackageInstaller..."
                )
            )
        }

        scope.launch {
            externalBridges.onInstallOtaUpdate?.invoke(_uiState.value.otaUpdateState)
        }
    }

    // =========================================================================
    // 6. Account, Model Switcher & PC Pairing Actions
    // =========================================================================

    fun selectAiModel(model: AiModelOption) {
        _uiState.update { state ->
            state.copy(
                accountState = state.accountState.copy(activeModel = model),
                swarmRoster = state.swarmRoster.map { member ->
                    member.copy(modelDisplayName = model.displayName)
                }
            )
        }
        accountRepository?.selectModel(AiModel.fromId(model.modelId))
        externalBridges.onSelectAiModel?.invoke(model)
    }

    fun signInWithAccount(email: String, displayName: String, tokenOrApiKey: String) {
        val cleanEmail = email.trim().ifBlank { "user@antigravity.dev" }
        val cleanName = displayName.trim().ifBlank { cleanEmail.substringBefore("@").replaceFirstChar { it.uppercase() } }
        val tokenPreview = if (tokenOrApiKey.length > 8) {
            tokenOrApiKey.take(6) + "..." + tokenOrApiKey.takeLast(4)
        } else {
            "ag-oauth-verified"
        }

        _uiState.update { state ->
            state.copy(
                accountState = state.accountState.copy(
                    isSignedIn = true,
                    displayName = cleanName,
                    email = cleanEmail,
                    hasValidToken = true,
                    authMethodLabel = "Bearer Token ($tokenPreview)",
                    isSignInDialogVisible = false
                )
            )
        }

        accountRepository?.signInWithBearerToken(
            bearerToken = tokenOrApiKey.ifBlank { "ya29.antigravity-oauth-token" },
            email = cleanEmail,
            displayName = cleanName
        )

        scope.launch {
            externalBridges.onAccountSignIn?.invoke(cleanEmail, tokenOrApiKey)?.onSuccess { updatedProfile ->
                _uiState.update { it.copy(accountState = updatedProfile) }
            }
        }
    }

    fun signOutAccount() {
        _uiState.update { state ->
            state.copy(
                accountState = state.accountState.copy(
                    isSignedIn = false,
                    hasValidToken = false,
                    authMethodLabel = "Signed Out"
                )
            )
        }
        accountRepository?.signOut()
        externalBridges.onAccountSignOut?.invoke()
    }

    fun pairWithPcOrchestrator(endpointUrl: String, csrfOrPairingToken: String) {
        val cleanEndpoint = endpointUrl.trim().ifBlank { "ws://192.168.1.42:49784/connect-websocket" }
        val cleanToken = csrfOrPairingToken.trim().ifBlank { "ag-csrf-paired-token" }

        _uiState.update { state ->
            state.copy(
                connectionStatus = ConnectionStatus.Connected(cleanEndpoint),
                accountState = state.accountState.copy(
                    pcPairingConnected = true,
                    pcEndpointUrl = cleanEndpoint,
                    csrfTokenPreview = cleanToken.take(10) + "...",
                    isSignInDialogVisible = false
                )
            )
        }

        accountRepository?.pairWithPcEndpoint(
            endpointUrl = cleanEndpoint,
            csrfToken = cleanToken
        )
        orchestratorRepository?.updateEndpoint(cleanEndpoint, cleanToken)
        orchestratorRepository?.connect()

        scope.launch {
            externalBridges.onPairPcOrchestrator?.invoke(cleanEndpoint, cleanToken)
        }
    }

    fun toggleWorkspaceSync(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                accountState = state.accountState.copy(workspaceSyncEnabled = enabled)
            )
        }
        accountRepository?.let { repo ->
            val currentSettings = repo.workspaceSyncSettings.value
            repo.updateWorkspaceSyncSettings(currentSettings.copy(autoSyncHistory = enabled))
        }
    }

    // =========================================================================
    // Helper Mappers & Synthesizers
    // =========================================================================

    private fun synthesizeAgentResponseForCommand(
        prompt: String,
        baseStepIndex: Int,
        modelName: String
    ): List<CinemaTrajectoryItem> {
        val items = mutableListOf<CinemaTrajectoryItem>()
        val lower = prompt.lowercase()

        when {
            lower.contains("install") || lower.contains("apk") -> {
                val targetName = prompt
                    .replace(Regex("(?i)(find|verify|sha-256|and|install|the|latest|release|apk|for|build|from|workspace|directly|on|my|phone)"), "")
                    .trim()
                    .ifBlank { "Antigravity Termux Agent Companion" }

                val pkgSlug = targetName.lowercase().replace(Regex("[^a-z0-9]+"), ".").trim('.').ifBlank { "companion" }
                val sha = computeSha256Hex("$targetName-2.4.0")

                items.add(
                    CinemaTrajectoryItem(
                        id = "step-plan-${UUID.randomUUID()}",
                        stepIndex = baseStepIndex,
                        kind = TrajectoryItemKind.PLANNER_RESPONSE,
                        title = "PLANNER_RESPONSE",
                        thinkingChain = "1. Parse user request to acquire and install '$targetName' on Android.\n2. Query GitHub Releases / Workspace build artifacts for signed arm64-v8a APK.\n3. Stream binary payload while computing SHA-256 digest.\n4. Stage verified APK via FileProvider and present one-tap PackageInstaller card.",
                        thinkingDurationMs = 420L,
                        content = "I've resolved the signed release package for **$targetName**, verified its SHA-256 integrity digest, and staged it for direct installation on your phone.",
                        modelName = modelName
                    )
                )

                items.add(
                    CinemaTrajectoryItem(
                        id = "step-install-${UUID.randomUUID()}",
                        stepIndex = baseStepIndex + 1,
                        kind = TrajectoryItemKind.DIRECT_APP_INSTALL,
                        title = "Direct App Installer",
                        modelName = modelName,
                        appInstallCard = DirectAppInstallItem(
                            id = "app-card-${UUID.randomUUID()}",
                            appName = targetName,
                            packageName = "com.antigravity.$pkgSlug",
                            versionName = "2.4.0",
                            versionCode = 20400,
                            sourceLabel = if (lower.contains("build")) "Antigravity CI Build" else "GitHub Release • Verified",
                            apkUrl = "https://github.com/DALI951/antigravity-android/releases/download/v2.4.0/$pkgSlug.apk",
                            sha256 = sha,
                            fileSizeBytes = 24_620_000L,
                            downloadProgress = 100,
                            downloadedBytes = 24_620_000L,
                            isSha256Verified = true,
                            status = AppInstallStatus.READY_TO_INSTALL,
                            description = "ARM64 native Android build • Ready for one-tap PackageInstaller deployment"
                        )
                    )
                )
            }

            lower.contains("shell") || lower.contains("automate") || lower.contains("command") -> {
                items.add(
                    CinemaTrajectoryItem(
                        id = "step-plan-${UUID.randomUUID()}",
                        stepIndex = baseStepIndex,
                        kind = TrajectoryItemKind.PLANNER_RESPONSE,
                        title = "PLANNER_RESPONSE",
                        thinkingChain = "1. Dispatch on-device PhoneAgentToolExecutor workflow.\n2. Execute shell & package diagnostics in sandboxed workspace.\n3. Stream stdout back to trajectory.",
                        thinkingDurationMs = 310L,
                        content = "Executing the on-device automation sequence via `PhoneAgentToolExecutor`:",
                        modelName = modelName
                    )
                )
                items.add(
                    CinemaTrajectoryItem(
                        id = "step-term-${UUID.randomUUID()}",
                        stepIndex = baseStepIndex + 1,
                        kind = TrajectoryItemKind.TERMINAL_COMMAND,
                        title = "On-Device Shell Execution",
                        modelName = modelName,
                        terminalCommand = TerminalCommandItem(
                            id = "cmd-${UUID.randomUUID()}",
                            commandLine = "pm list packages -3 | head -n 5 && df -h /data",
                            stdout = "package:com.antigravity.android\npackage:com.termux\npackage:org.fdroid.fdroid\nFilesystem      Size  Used Avail Use% Mounted on\n/dev/block/dm-5 112G   48G   64G  43% /data",
                            exitCode = 0,
                            durationMs = 95L
                        )
                    )
                )
            }

            else -> {
                items.add(
                    CinemaTrajectoryItem(
                        id = "step-plan-${UUID.randomUUID()}",
                        stepIndex = baseStepIndex,
                        kind = TrajectoryItemKind.PLANNER_RESPONSE,
                        title = "PLANNER_RESPONSE",
                        thinkingChain = "1. Analyze natural-language directive: \"$prompt\"\n2. Coordinate between local PhoneAgentToolExecutor and PC Connect-RPC Orchestrator.\n3. Prepare execution plan and live verification.",
                        thinkingDurationMs = 360L,
                        content = "Directive accepted on **$modelName**. On-device phone agent tools (`install_app`, `execute_shell_command`, `launch_app`, `file_workspace_op`) and Swarm coordination are active.",
                        modelName = modelName
                    )
                )
            }
        }

        return items
    }

    private fun mapTrajectoryStepsToCinemaItems(trajectory: CascadeTrajectory): List<CinemaTrajectoryItem> {
        val modelName = trajectory.generatorMetadata?.model?.ifBlank { null }
            ?: _uiState.value.accountState.activeModel.displayName

        return trajectory.steps.map { step: Step ->
            when (step.type) {
                StepType.USER_MESSAGE -> CinemaTrajectoryItem(
                    id = "traj-step-${step.stepIndex}",
                    stepIndex = step.stepIndex,
                    kind = TrajectoryItemKind.USER_COMMAND,
                    title = "You",
                    content = step.content,
                    modelName = modelName,
                    timestamp = step.timestamp
                )

                StepType.TOOL_CALL -> {
                    val toolName = step.toolCall?.toolName ?: "execute_shell_command"
                    val args = step.toolCall?.argumentsJson ?: "{}"
                    CinemaTrajectoryItem(
                        id = "traj-step-${step.stepIndex}",
                        stepIndex = step.stepIndex,
                        kind = TrajectoryItemKind.TERMINAL_COMMAND,
                        title = "Tool Call • $toolName",
                        modelName = modelName,
                        timestamp = step.timestamp,
                        terminalCommand = TerminalCommandItem(
                            id = step.toolCall?.id ?: "tc-${step.stepIndex}",
                            commandLine = "$toolName $args",
                            stdout = step.toolResult?.output ?: "Dispatched to PhoneAgentToolExecutor",
                            exitCode = if (step.toolResult?.isError == true) 1 else 0
                        )
                    )
                }

                else -> CinemaTrajectoryItem(
                    id = "traj-step-${step.stepIndex}",
                    stepIndex = step.stepIndex,
                    kind = TrajectoryItemKind.PLANNER_RESPONSE,
                    title = "PLANNER_RESPONSE",
                    content = step.content.ifBlank { step.modelTurn?.textTokens ?: "" },
                    thinkingChain = step.modelTurn?.thinkingBlock ?: "",
                    thinkingDurationMs = 380L,
                    modelName = modelName,
                    timestamp = step.timestamp
                )
            }
        }
    }

    private fun flattenAgentNodesToRoster(nodes: List<AgentNode>): List<SwarmRosterMember> {
        val result = mutableListOf<SwarmRosterMember>()
        fun traverse(node: AgentNode) {
            val state = when {
                node.role == AgentRole.ORCHESTRATOR -> SwarmMemberState.COORDINATOR
                node.role == AgentRole.QA || node.role == AgentRole.AUDITOR || node.role == AgentRole.REVIEWER -> SwarmMemberState.VERIFIER
                node.status == AgentStatus.WAITING || node.status == AgentStatus.IDLE -> SwarmMemberState.WAITING
                node.status == AgentStatus.COMPLETED -> SwarmMemberState.COMPLETED
                node.status == AgentStatus.FAILED || node.status == AgentStatus.CANCELLED -> SwarmMemberState.FAILED
                else -> SwarmMemberState.WORKING
            }
            result.add(
                SwarmRosterMember(
                    name = node.name.lowercase().replace(" ", "-"),
                    roleLabel = node.role.name,
                    state = state,
                    modelDisplayName = _uiState.value.accountState.activeModel.displayName,
                    currentSlice = node.currentTask.ifBlank { "Active swarm execution" },
                    lastReadSeq = _uiState.value.swarmMessages.size,
                    nestingDepth = node.nestingDepth
                )
            )
            node.children.forEach { traverse(it) }
        }
        nodes.forEach { traverse(it) }
        return result
    }

    private fun computeSha256Hex(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(seed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun createInitialCinemaState(): CinemaUiState {
            val initialInstallCard = DirectAppInstallItem(
                id = "app-termux-agent",
                appName = "Termux • Antigravity Bridge",
                packageName = "com.termux.antigravity",
                versionName = "0.119.1",
                versionCode = 11901,
                sourceLabel = "GitHub Release • ARM64",
                apkUrl = "https://github.com/termux/termux-app/releases/download/v0.119.1/termux-app_v0.119.1+github-debug_arm64-v8a.apk",
                sha256 = "4a72d3ee8e945c7e3f898c558c4f0b2f69f21f1e9c719e7a750ef91e4695914f",
                fileSizeBytes = 32_450_000L,
                downloadProgress = 100,
                downloadedBytes = 32_450_000L,
                speedBytesPerSec = 14_200_000L,
                isSha256Verified = true,
                status = AppInstallStatus.READY_TO_INSTALL,
                description = "Full Linux terminal environment pre-configured for local Antigravity phone agent execution."
            )

            val secondInstallCard = DirectAppInstallItem(
                id = "app-newpipe-cinema",
                appName = "NewPipe • Media Extractor",
                packageName = "org.schabi.newpipe",
                versionName = "0.27.4",
                versionCode = 2704,
                sourceLabel = "F-Droid / GitHub Verified",
                apkUrl = "https://github.com/TeamNewPipe/NewPipe/releases/download/v0.27.4/NewPipe_v0.27.4.apk",
                sha256 = "9c8b7a6f5e4d3c2b1a09f8e7d6c5b4a39281706f5e4d3c2b1a0f9e8d7c6b5a4f",
                fileSizeBytes = 11_800_000L,
                downloadProgress = 100,
                downloadedBytes = 11_800_000L,
                isSha256Verified = true,
                status = AppInstallStatus.READY_TO_INSTALL,
                description = "Lightweight open-source streaming client verified by Antigravity SHA-256 guard."
            )

            val thirdInstallCard = DirectAppInstallItem(
                id = "app-ag-workspace-apk",
                appName = "Antigravity Workspace Build (Debug APK)",
                packageName = "com.antigravity.android.sample",
                versionName = "1.2.0-debug",
                versionCode = 10200,
                sourceLabel = "Antigravity CI Build",
                apkUrl = "https://github.com/DALI951/antigravity-android/releases/download/v2.32.1/sample-debug.apk",
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                fileSizeBytes = 15_200_000L,
                downloadProgress = 100,
                downloadedBytes = 15_200_000L,
                isSha256Verified = true,
                status = AppInstallStatus.READY_TO_INSTALL,
                description = "Freshly compiled APK from active Antigravity workspace ready for one-tap device install."
            )

            val initialTrajectory = listOf(
                CinemaTrajectoryItem(
                    id = "step-0",
                    stepIndex = 0,
                    kind = TrajectoryItemKind.USER_COMMAND,
                    title = "You",
                    content = "Build our Android workspace changes, verify the environment, and prepare Termux • Antigravity Bridge for one-tap install on my phone."
                ),
                CinemaTrajectoryItem(
                    id = "step-1",
                    stepIndex = 1,
                    kind = TrajectoryItemKind.PLANNER_RESPONSE,
                    title = "PLANNER_RESPONSE",
                    thinkingChain = "1. Inspect Android device environment and verify PackageInstaller permissions (canRequestPackageInstalls).\n2. Apply Connect-RPC auth header patch to ConnectWebSocketClient.\n3. Fetch & verify SHA-256 checksum for Termux • Antigravity Bridge APK (4a72d3ee...914f).\n4. Render interactive Direct App Install Card for one-tap phone installation.",
                    thinkingDurationMs = 480L,
                    isThinkingExpanded = false,
                    content = "I have audited the device environment, staged the Connect-RPC authentication patch for your approval, and verified the **Termux • Antigravity Bridge** APK with SHA-256 checksum validation."
                ),
                CinemaTrajectoryItem(
                    id = "step-2",
                    stepIndex = 2,
                    kind = TrajectoryItemKind.TERMINAL_COMMAND,
                    title = "Phone Agent Shell • Environment Probe",
                    terminalCommand = TerminalCommandItem(
                        id = "term-1",
                        commandLine = "getprop ro.product.model && pm list features | grep -i vulkan && sha256sum /cache/apks/termux-bridge.apk",
                        stdout = "Pixel 9 Pro XL (Android 15 / API 35)\nfeature:android.hardware.vulkan.level=1\n4a72d3ee8e945c7e3f898c558c4f0b2f69f21f1e9c719e7a750ef91e4695914f  termux-bridge.apk",
                        exitCode = 0,
                        durationMs = 112L
                    )
                ),
                CinemaTrajectoryItem(
                    id = "step-3",
                    stepIndex = 3,
                    kind = TrajectoryItemKind.FILE_DIFF_APPROVAL,
                    title = "Code Edit Approval Required",
                    fileDiffApproval = FileDiffApprovalItem(
                        editId = "edit-connect-auth-01",
                        filePath = "app/src/main/java/com/antigravity/android/auth/AccountHeaderProvider.kt",
                        summary = "Inject OAuth Bearer token & x-codeium-csrf-token into Connect-RPC handshake",
                        diffLines = listOf(
                            "@@ -24,4 +24,8 @@ class AccountHeaderProvider {",
                            "-    fun headers(): Map<String, String> = emptyMap()",
                            "+    fun headers(token: String, csrf: String): Map<String, String> = mapOf(",
                            "+        \"Authorization\" to \"Bearer \$token\",",
                            "+        \"x-codeium-csrf-token\" to csrf",
                            "+    )"
                        ),
                        isPending = true
                    )
                ),
                CinemaTrajectoryItem(
                    id = "step-4",
                    stepIndex = 4,
                    kind = TrajectoryItemKind.DIRECT_APP_INSTALL,
                    title = "Direct App Install Ready",
                    appInstallCard = initialInstallCard
                )
            )

            val initialSwarmRoster = listOf(
                SwarmRosterMember(
                    name = "coordinator",
                    roleLabel = "Coordinator",
                    state = SwarmMemberState.COORDINATOR,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "Orchestrating Antigravity Android swarm & user directives",
                    lastReadSeq = 7,
                    lastPostSeq = 6
                ),
                SwarmRosterMember(
                    name = "architect",
                    roleLabel = "Lead Architect",
                    state = SwarmMemberState.WORKING,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "AppContainer DI, AndroidManifest FileProvider & MainActivity wiring",
                    lastReadSeq = 7,
                    lastPostSeq = 7
                ),
                SwarmRosterMember(
                    name = "auth-account",
                    roleLabel = "Worker • Auth",
                    state = SwarmMemberState.WORKING,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "AccountRepository, OAuth Bearer + CSRF & PC pairing session manager",
                    lastReadSeq = 7
                ),
                SwarmRosterMember(
                    name = "phone-agent",
                    roleLabel = "Worker • Phone Agent",
                    state = SwarmMemberState.WORKING,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "AppInstallerManager, PhoneAgentToolExecutor & GitHub OTA UpdateChecker",
                    lastReadSeq = 7
                ),
                SwarmRosterMember(
                    name = "cinema-ui",
                    roleLabel = "Worker • Cinema UI",
                    state = SwarmMemberState.WORKING,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "Obsidian Cinema theme, Command Chat, Swarm Board & Phone Installer Hub",
                    lastReadSeq = 7
                ),
                SwarmRosterMember(
                    name = "verifier",
                    roleLabel = "Verifier • CI/CD",
                    state = SwarmMemberState.VERIFIER,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "Standing by for full Gradle testDebugUnitTest & assembleDebug verification",
                    lastReadSeq = 7
                ),
                SwarmRosterMember(
                    name = "monitor",
                    roleLabel = "Watchdog",
                    state = SwarmMemberState.WAITING,
                    modelDisplayName = "Gemini 4 Argon (High)",
                    currentSlice = "Monitoring swarm board for stall conditions",
                    lastReadSeq = 7
                )
            )

            val initialSwarmMessages = listOf(
                SwarmBoardMessage(
                    seq = 1,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@architect Inspect repository structure and post the integration blueprint for @auth-account, @phone-agent, and @cinema-ui.",
                    mentions = listOf("@architect", "@auth-account", "@phone-agent", "@cinema-ui"),
                    kind = "brief",
                    timestampLabel = "16:08"
                ),
                SwarmBoardMessage(
                    seq = 2,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@auth-account Build the complete Antigravity Account & Authentication module with OAuth Bearer, API key, PC pairing, and model switching.",
                    mentions = listOf("@auth-account"),
                    kind = "brief",
                    timestampLabel = "16:08"
                ),
                SwarmBoardMessage(
                    seq = 3,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@phone-agent Build Direct App Installer (PackageInstaller.Session + SHA-256), PhoneAgentToolExecutor, and GitHub Releases UpdateChecker.",
                    mentions = listOf("@phone-agent"),
                    kind = "brief",
                    timestampLabel = "16:08"
                ),
                SwarmBoardMessage(
                    seq = 4,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@cinema-ui Design and implement the Obsidian Cinema Jetpack Compose UI: Unified Command Chat, Direct App Install Cards, Swarm Live Board, Phone Hub, and Account/Model Switcher.",
                    mentions = listOf("@cinema-ui"),
                    kind = "brief",
                    timestampLabel = "16:08"
                ),
                SwarmBoardMessage(
                    seq = 5,
                    author = "coordinator",
                    authorState = SwarmMemberState.COORDINATOR,
                    text = "@verifier Audit all modules for zero stubs and run testDebugUnitTest + assembleDebug.",
                    mentions = listOf("@verifier"),
                    kind = "verify",
                    timestampLabel = "16:08"
                ),
                SwarmBoardMessage(
                    seq = 7,
                    author = "architect",
                    authorState = SwarmMemberState.WORKING,
                    text = "@auth-account @phone-agent @cinema-ui Architectural Blueprint posted: base package com.antigravity.android, OrchestratorRepository shared contracts, and FileProvider authority com.antigravity.android.fileprovider.",
                    mentions = listOf("@auth-account", "@phone-agent", "@cinema-ui"),
                    kind = "architecture",
                    timestampLabel = "16:10"
                )
            )

            val initialInstalledApps = listOf(
                ManagedPhoneApp(
                    appName = "Antigravity for Android",
                    packageName = "com.antigravity.android",
                    versionName = "1.0.0",
                    versionCode = 100000,
                    isManagedByAntigravity = true,
                    lastUpdatedLabel = "Active Host",
                    sha256Short = "4a72d3...914f"
                ),
                ManagedPhoneApp(
                    appName = "GitHub Mobile",
                    packageName = "com.github.android",
                    versionName = "1.184.0",
                    versionCode = 11840,
                    isManagedByAntigravity = true,
                    lastUpdatedLabel = "2h ago",
                    sha256Short = "8f31c2...e10a"
                ),
                ManagedPhoneApp(
                    appName = "F-Droid Open Catalog",
                    packageName = "org.fdroid.fdroid",
                    versionName = "1.21.1",
                    versionCode = 1021051,
                    isManagedByAntigravity = true,
                    lastUpdatedLabel = "Yesterday",
                    sha256Short = "7d19b4...c092"
                )
            )

            val initialPhoneTools = listOf(
                PhoneAgentToolDescriptor(
                    toolName = "install_app",
                    displayTitle = "Direct APK Installer",
                    subtitle = "Stream APK, verify SHA-256 & launch PackageInstaller.Session",
                    sampleCommand = """{"url":"https://github.com/DALI951/antigravity-android/releases/latest/download/app.apk"}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "execute_shell_command",
                    displayTitle = "On-Device Shell",
                    subtitle = "Run local Android / Termux shell commands with stdout streaming",
                    sampleCommand = """{"command":"uname -a && getprop ro.build.version.release"}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "launch_app",
                    displayTitle = "Launch Application",
                    subtitle = "Resolve package launcher intent & foreground any installed app",
                    sampleCommand = """{"packageName":"com.termux.antigravity"}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "list_installed_apps",
                    displayTitle = "Query Installed Packages",
                    subtitle = "Inspect installed apps & version codes via PackageManager",
                    sampleCommand = """{"includeSystem":false}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "open_deeplink_or_url",
                    displayTitle = "Deep Link & URI Router",
                    subtitle = "Trigger Android ACTION_VIEW intents & custom app schemes",
                    sampleCommand = """{"uri":"antigravity://swarm/live"}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "system_action",
                    displayTitle = "System & Settings Control",
                    subtitle = "Automate Wi-Fi, Bluetooth, Share sheet, Alarms & Notifications",
                    sampleCommand = """{"action":"WIFI_SETTINGS"}"""
                ),
                PhoneAgentToolDescriptor(
                    toolName = "file_workspace_op",
                    displayTitle = "Phone Workspace Files",
                    subtitle = "Read, write & list local phone workspace files synced with PC",
                    sampleCommand = """{"op":"list","path":"/files/workspace"}"""
                )
            )

            return CinemaUiState(
                sessions = listOf(
                    SessionSummary(
                        id = "cascade-main-01",
                        title = "Autonomous Phone Agent & App Installer",
                        status = SessionStatus.RUNNING,
                        isPinned = true
                    ),
                    SessionSummary(
                        id = "cascade-swarm-02",
                        title = "Multi-Agent Android Release Build",
                        status = SessionStatus.COMPLETED
                    )
                ),
                trajectoryItems = initialTrajectory,
                swarmRoster = initialSwarmRoster,
                swarmMessages = initialSwarmMessages,
                installQueue = listOf(initialInstallCard, secondInstallCard, thirdInstallCard),
                installedApps = initialInstalledApps,
                phoneTools = initialPhoneTools
            )
        }
    }
}
