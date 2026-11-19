package com.antigravity.android.ui.viewmodel

import com.antigravity.android.data.model.AgentNode
import com.antigravity.android.data.model.AgentRole
import com.antigravity.android.data.model.AgentStatus
import com.antigravity.android.data.model.LogEntry
import com.antigravity.android.data.model.SessionStatus
import com.antigravity.android.data.model.SessionSummary
import com.antigravity.android.data.network.ConnectionStatus

enum class CinemaDestination(val route: String, val title: String) {
    COMMAND_CHAT("command_chat", "Command"),
    SWARM_BOARD("swarm_board", "Swarm"),
    PHONE_HUB("phone_hub", "Phone Hub")
}

enum class QuickActionPill(
    val label: String,
    val promptTemplate: String,
    val tag: String
) {
    INSTALL_APP(
        label = "Install an App",
        promptTemplate = "Find, verify SHA-256, and install the latest release APK for ",
        tag = "install_app"
    ),
    BUILD_AND_INSTALL_APK(
        label = "Build & Install APK",
        promptTemplate = "Build the Android debug APK from workspace and install it directly on my phone",
        tag = "build_apk"
    ),
    AUTOMATE_PHONE_TASK(
        label = "Automate Phone Task",
        promptTemplate = "Run an on-device phone automation workflow to ",
        tag = "automate_phone"
    ),
    LAUNCH_SWARM(
        label = "Launch Swarm",
        promptTemplate = "/swarm Launch a multi-agent swarm with coordinator, architect, worker, and verifier to ",
        tag = "launch_swarm"
    )
}

enum class AiModelOption(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val description: String,
    val reasoningTier: String
) {
    GEMINI_4_ARGON_HIGH(
        modelId = "gemini-4-argon-high",
        displayName = "Gemini 4 Argon High",
        badge = "MAX REASONING",
        description = "Deep multi-agent swarm orchestration, full-stack synthesis & autonomous tool execution",
        reasoningTier = "Argon High • Thinking Level 3"
    ),
    GEMINI_PRO(
        modelId = "gemini-pro",
        displayName = "Gemini Pro",
        badge = "BALANCED",
        description = "High-precision code generation, repository analysis & multi-step phone workflows",
        reasoningTier = "Pro • Thinking Level 2"
    ),
    GEMINI_FLASH(
        modelId = "gemini-flash",
        displayName = "Gemini Flash",
        badge = "ULTRA FAST",
        description = "Sub-second on-device tool calls, quick app installs & instant shell automation",
        reasoningTier = "Flash • Low Latency"
    );

    companion object {
        fun fromId(id: String): AiModelOption {
            return entries.firstOrNull {
                it.modelId.equals(id, ignoreCase = true) ||
                    it.displayName.equals(id, ignoreCase = true)
            } ?: GEMINI_4_ARGON_HIGH
        }
    }
}

enum class AppInstallStatus {
    AVAILABLE,
    DOWNLOADING,
    VERIFYING_SHA256,
    READY_TO_INSTALL,
    INSTALLING,
    INSTALLED,
    FAILED
}

data class DirectAppInstallItem(
    val id: String,
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int = 1,
    val sourceLabel: String = "GitHub Release",
    val apkUrl: String,
    val sha256: String,
    val fileSizeBytes: Long = 28_450_000L,
    val downloadProgress: Int = 0, // 0..100
    val downloadedBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val isSha256Verified: Boolean = true,
    val status: AppInstallStatus = AppInstallStatus.READY_TO_INSTALL,
    val localApkPath: String? = null,
    val description: String = "",
    val errorMessage: String? = null
)

data class TerminalCommandItem(
    val id: String,
    val commandLine: String,
    val workingDirectory: String = "/data/data/com.antigravity.android/files/workspace",
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = 0,
    val isRunning: Boolean = false,
    val durationMs: Long = 140L
)

data class FileDiffApprovalItem(
    val editId: String,
    val filePath: String,
    val summary: String,
    val diffLines: List<String>,
    val isPending: Boolean = true,
    val isApproved: Boolean? = null
)

enum class TrajectoryItemKind {
    USER_COMMAND,
    PLANNER_RESPONSE,
    TERMINAL_COMMAND,
    FILE_DIFF_APPROVAL,
    DIRECT_APP_INSTALL,
    SYSTEM_EVENT
}

data class CinemaTrajectoryItem(
    val id: String,
    val stepIndex: Int,
    val kind: TrajectoryItemKind,
    val title: String = "",
    val content: String = "",
    val thinkingChain: String = "",
    val thinkingDurationMs: Long = 0L,
    val isThinkingExpanded: Boolean = false,
    val modelName: String = AiModelOption.GEMINI_4_ARGON_HIGH.displayName,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val terminalCommand: TerminalCommandItem? = null,
    val fileDiffApproval: FileDiffApprovalItem? = null,
    val appInstallCard: DirectAppInstallItem? = null
)

enum class SwarmMemberState(val wireName: String, val displayLabel: String) {
    WORKING("working", "WORKING"),
    WAITING("waiting", "WAITING"),
    VERIFIER("verifier", "VERIFIER"),
    COORDINATOR("coordinator", "COORDINATOR"),
    COMPLETED("completed", "DONE"),
    FAILED("failed", "FAILED")
}

data class SwarmRosterMember(
    val name: String,
    val roleLabel: String,
    val state: SwarmMemberState,
    val modelDisplayName: String = "Gemini 4 Argon (High)",
    val currentSlice: String,
    val lastReadSeq: Int,
    val lastPostSeq: Int? = null,
    val nestingDepth: Int = 0
)

enum class SwarmChannelFilter(val label: String) {
    ALL("All Stream"),
    MENTIONS("@Mentions"),
    BRIEFS("Briefs"),
    VERIFIER("Verifier & CI"),
    ARCHITECTURE("Architecture")
}

data class SwarmBoardMessage(
    val seq: Int,
    val author: String,
    val authorState: SwarmMemberState,
    val text: String,
    val mentions: List<String> = emptyList(),
    val kind: String = "post", // "brief", "post", "verify", "architecture"
    val timestampLabel: String = "just now"
)

data class ManagedPhoneApp(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val isManagedByAntigravity: Boolean = true,
    val lastUpdatedLabel: String = "Today",
    val sha256Short: String = "4a72d3...914f"
)

data class PhoneAgentToolDescriptor(
    val toolName: String,
    val displayTitle: String,
    val subtitle: String,
    val sampleCommand: String,
    val lastExecutionOutput: String? = null,
    val isExecuting: Boolean = false
)

enum class OtaUpdateStatus {
    IDLE,
    CHECKING,
    UPDATE_AVAILABLE,
    DOWNLOADING,
    VERIFYING_CHECKSUM,
    READY_TO_INSTALL,
    UP_TO_DATE,
    ERROR
}

data class OtaSelfUpdateUiState(
    val currentVersionName: String = "1.0.0",
    val currentVersionCode: Int = 100000,
    val latestVersionName: String = "2.32.1",
    val latestVersionCode: Int = 203201,
    val repositorySlug: String = "DALI951/antigravity-android",
    val releaseNotes: String = "• Autonomous On-Device Phone Agent Execution Engine\n• Direct APK PackageInstaller with SHA-256 verification\n• Native Swarm Live Board & Obsidian Cinema UI\n• Seamless Antigravity Account & PC Orchestrator Pairing",
    val apkUrl: String = "https://github.com/DALI951/antigravity-android/releases/download/v2.32.1/antigravity-v2.32.1.apk",
    val sha256: String = "4a72d3ee8e945c7e3f898c558c4f0b2f69f21f1e9c719e7a750ef91e4695914f",
    val downloadProgress: Int = 0,
    val status: OtaUpdateStatus = OtaUpdateStatus.UPDATE_AVAILABLE,
    val downloadedApkPath: String? = null,
    val statusMessage: String = "New release v2.32.1 available on GitHub Releases"
)

data class AccountProfileUiState(
    val isSignedIn: Boolean = true,
    val userId: String = "ag-user-951",
    val displayName: String = "Dali",
    val email: String = "dali@antigravity.dev",
    val planTier: String = "ANTIGRAVITY ULTRA",
    val authMethodLabel: String = "Google OAuth2 • Bearer Active",
    val hasValidToken: Boolean = true,
    val csrfTokenPreview: String = "ag-csrf-89f2...e41c",
    val activeModel: AiModelOption = AiModelOption.GEMINI_4_ARGON_HIGH,
    val pcPairingConnected: Boolean = true,
    val pcEndpointUrl: String = "ws://192.168.1.42:49784/connect-websocket",
    val pcHostName: String = "DALI-WORKSTATION-PC",
    val workspaceSyncEnabled: Boolean = true,
    val isSignInDialogVisible: Boolean = false
)

data class CinemaUiState(
    val currentDestination: CinemaDestination = CinemaDestination.COMMAND_CHAT,
    val activeSessionId: String = "cascade-main-01",
    val sessionTitle: String = "Autonomous Phone Agent & App Installer",
    val sessionStatus: SessionStatus = SessionStatus.RUNNING,
    val sessions: List<SessionSummary> = emptyList(),
    val connectionStatus: ConnectionStatus = ConnectionStatus.Connected("ws://192.168.1.42:49784/connect-websocket"),
    val commandInput: String = "",
    val selectedQuickPill: QuickActionPill? = null,
    val trajectoryItems: List<CinemaTrajectoryItem> = emptyList(),
    val executionLogs: List<LogEntry> = emptyList(),
    // Swarm Board Screen state
    val swarmTitle: String = "Antigravity for Android (Phone Agent, Account Sync & Direct App Installer)",
    val swarmRoster: List<SwarmRosterMember> = emptyList(),
    val swarmNodeTree: List<AgentNode> = emptyList(),
    val swarmMessages: List<SwarmBoardMessage> = emptyList(),
    val swarmFilter: SwarmChannelFilter = SwarmChannelFilter.ALL,
    val swarmSearchQuery: String = "",
    val swarmComposerText: String = "",
    // Phone App Installer & Device Hub state
    val appSearchQuery: String = "",
    val installQueue: List<DirectAppInstallItem> = emptyList(),
    val installedApps: List<ManagedPhoneApp> = emptyList(),
    val phoneTools: List<PhoneAgentToolDescriptor> = emptyList(),
    val activeToolOutputBanner: String? = null,
    val otaUpdateState: OtaSelfUpdateUiState = OtaSelfUpdateUiState(),
    // Account & Model Switcher Sheet state
    val accountState: AccountProfileUiState = AccountProfileUiState(),
    val isAccountSheetOpen: Boolean = false
) {
    val filteredSwarmMessages: List<SwarmBoardMessage>
        get() = swarmMessages.filter { msg ->
            val matchesFilter = when (swarmFilter) {
                SwarmChannelFilter.ALL -> true
                SwarmChannelFilter.MENTIONS -> msg.mentions.isNotEmpty() || msg.text.contains("@")
                SwarmChannelFilter.BRIEFS -> msg.kind.equals("brief", ignoreCase = true)
                SwarmChannelFilter.VERIFIER -> msg.author.contains("verifier", ignoreCase = true) ||
                    msg.mentions.any { it.contains("verifier", ignoreCase = true) } ||
                    msg.kind.equals("verify", ignoreCase = true)
                SwarmChannelFilter.ARCHITECTURE -> msg.author.contains("architect", ignoreCase = true) ||
                    msg.kind.equals("architecture", ignoreCase = true)
            }
            val matchesSearch = swarmSearchQuery.isBlank() ||
                msg.text.contains(swarmSearchQuery, ignoreCase = true) ||
                msg.author.contains(swarmSearchQuery, ignoreCase = true) ||
                "#${msg.seq}" == swarmSearchQuery.trim()
            matchesFilter && matchesSearch
        }

    val filteredInstallQueue: List<DirectAppInstallItem>
        get() = if (appSearchQuery.isBlank()) {
            installQueue
        } else {
            installQueue.filter {
                it.appName.contains(appSearchQuery, ignoreCase = true) ||
                    it.packageName.contains(appSearchQuery, ignoreCase = true) ||
                    it.description.contains(appSearchQuery, ignoreCase = true) ||
                    it.sourceLabel.contains(appSearchQuery, ignoreCase = true)
            }
        }
}
