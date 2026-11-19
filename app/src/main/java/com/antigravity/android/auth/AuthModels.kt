package com.antigravity.android.auth

import com.antigravity.android.data.network.ConnectFrameBuilder
import kotlinx.serialization.Serializable

/**
 * Subscription/account tier for an Antigravity user profile.
 */
@Serializable
enum class AccountTier(
    val id: String,
    val displayName: String,
    val badgeText: String
) {
    FREE("free", "Antigravity Free", "FREE"),
    PRO("pro", "Antigravity Pro", "PRO"),
    ULTRA("ultra", "Antigravity Ultra", "ULTRA"),
    ENTERPRISE("enterprise", "Antigravity Enterprise", "TEAM");

    companion object {
        fun fromId(value: String?): AccountTier {
            if (value.isNullOrBlank()) return PRO
            return entries.firstOrNull {
                it.id.equals(value.trim(), ignoreCase = true) ||
                    it.name.equals(value.trim(), ignoreCase = true) ||
                    it.displayName.equals(value.trim(), ignoreCase = true)
            } ?: PRO
        }
    }
}

/**
 * Supported authentication mechanism for the active Antigravity session.
 */
@Serializable
enum class AuthMethod(
    val id: String,
    val displayName: String
) {
    OAUTH_GOOGLE("oauth_google", "Google OAuth"),
    ANTIGRAVITY_OAUTH("antigravity_oauth", "Antigravity OAuth"),
    API_KEY("api_key", "API Key"),
    DIRECT_TOKEN("direct_token", "Bearer Token"),
    PC_PAIRING("pc_pairing", "PC Session Pairing");

    companion object {
        fun fromId(value: String?): AuthMethod {
            if (value.isNullOrBlank()) return OAUTH_GOOGLE
            return entries.firstOrNull {
                it.id.equals(value.trim(), ignoreCase = true) ||
                    it.name.equals(value.trim(), ignoreCase = true)
            } ?: OAUTH_GOOGLE
        }
    }
}

/**
 * Supported AI models available in Antigravity for Android & PC Orchestrator.
 */
@Serializable
enum class AiModel(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val description: String,
    val supportsThinking: Boolean = true,
    val supportsSwarm: Boolean = true,
    val contextWindowTokens: Int = 2_000_000
) {
    GEMINI_4_ARGON_HIGH(
        modelId = "gemini-4-argon-high",
        displayName = "Gemini 4 Argon High",
        badge = "FLAGSHIP",
        description = "Deepmind flagship agentic reasoning, code generation & native swarm orchestration",
        supportsThinking = true,
        supportsSwarm = true,
        contextWindowTokens = 2_000_000
    ),
    GEMINI_PRO(
        modelId = "gemini-2.5-pro",
        displayName = "Gemini Pro",
        badge = "PRO",
        description = "Balanced deep coding, multi-file refactoring & architecture analysis",
        supportsThinking = true,
        supportsSwarm = true,
        contextWindowTokens = 2_000_000
    ),
    GEMINI_FLASH(
        modelId = "gemini-2.5-flash",
        displayName = "Gemini Flash",
        badge = "FAST",
        description = "Ultra-low latency on-device phone automation, quick actions & instant responses",
        supportsThinking = false,
        supportsSwarm = true,
        contextWindowTokens = 1_000_000
    ),
    GEMINI_ULTRA(
        modelId = "gemini-ultra",
        displayName = "Gemini Ultra",
        badge = "ULTRA",
        description = "Maximum reasoning depth for large multi-repository systems",
        supportsThinking = true,
        supportsSwarm = true,
        contextWindowTokens = 2_000_000
    );

    val id: String
        get() = modelId

    val label: String
        get() = displayName

    companion object {
        val DEFAULT: AiModel = GEMINI_4_ARGON_HIGH

        fun allModels(): List<AiModel> = entries.toList()

        fun fromId(value: String?): AiModel {
            if (value.isNullOrBlank()) return DEFAULT
            val normalized = value.trim()
            return entries.firstOrNull {
                it.modelId.equals(normalized, ignoreCase = true) ||
                    it.name.equals(normalized, ignoreCase = true) ||
                    it.displayName.equals(normalized, ignoreCase = true)
            } ?: when {
                normalized.contains("argon", ignoreCase = true) -> GEMINI_4_ARGON_HIGH
                normalized.contains("flash", ignoreCase = true) -> GEMINI_FLASH
                normalized.contains("ultra", ignoreCase = true) -> GEMINI_ULTRA
                normalized.contains("pro", ignoreCase = true) -> GEMINI_PRO
                else -> DEFAULT
            }
        }
    }
}

/**
 * Workspace & PC-to-Phone synchronization settings associated with the user's account.
 */
@Serializable
data class WorkspaceSyncSettings(
    val autoSyncHistory: Boolean = true,
    val syncCustomSkillsAndRules: Boolean = true,
    val syncMcpServers: Boolean = true,
    val allowRemotePhoneToolExecution: Boolean = true,
    val autoConnectPcOrchestrator: Boolean = true,
    val pcOrchestratorEndpoint: String = DEFAULT_PC_ENDPOINT,
    val workspaceRootPath: String = DEFAULT_PHONE_WORKSPACE_PATH,
    val lastSyncedAtEpochMs: Long? = null
) {
    companion object {
        const val DEFAULT_PC_ENDPOINT = "ws://127.0.0.1:49784/connect-websocket"
        const val DEFAULT_PHONE_WORKSPACE_PATH = "/storage/emulated/0/AntigravityWorkspace"
    }
}

/**
 * Active pairing session linking the Android client to a PC Antigravity Orchestrator daemon.
 */
@Serializable
data class PcPairingSession(
    val sessionId: String,
    val pcHostname: String,
    val endpointUrl: String,
    val csrfToken: String,
    val bearerToken: String = "",
    val pairedAtEpochMs: Long = System.currentTimeMillis(),
    val isConnected: Boolean = true
)

/**
 * Decoded QR code / one-tap pairing token payload emitted by PC Antigravity.
 */
@Serializable
data class PcPairingPayload(
    val endpointUrl: String,
    val csrfToken: String,
    val sessionToken: String = "",
    val pcHostname: String = "Antigravity-PC",
    val accountEmail: String? = null,
    val accountName: String? = null,
    val selectedModelId: String? = null
)

/**
 * User profile, credential state, active model selection, and workspace sync settings.
 */
@Serializable
data class AntigravityAccount(
    val id: String,
    val displayName: String,
    val email: String,
    val avatarUrl: String? = null,
    val tier: AccountTier = AccountTier.PRO,
    val plan: String = tier.displayName,
    val authMethod: AuthMethod = AuthMethod.OAUTH_GOOGLE,
    val oauthToken: String? = null,
    val bearerToken: String? = null,
    val refreshToken: String? = null,
    val apiKey: String? = null,
    val csrfToken: String = "",
    val tokenExpiresAtEpochMs: Long = Long.MAX_VALUE,
    val selectedModel: AiModel = AiModel.GEMINI_4_ARGON_HIGH,
    val workspaceSyncSettings: WorkspaceSyncSettings = WorkspaceSyncSettings(),
    val pairedPcSession: PcPairingSession? = null,
    val lastSignInEpochMs: Long = System.currentTimeMillis()
) {
    /**
     * Resolves the active Bearer or OAuth token (falling back to PC pairing bearer token or API key).
     */
    val activeAccessToken: String
        get() = bearerToken?.takeIf { it.isNotBlank() }
            ?: oauthToken?.takeIf { it.isNotBlank() }
            ?: pairedPcSession?.bearerToken?.takeIf { it.isNotBlank() }
            ?: apiKey?.takeIf { it.isNotBlank() }
            ?: ""

    val isPairedWithPc: Boolean
        get() = pairedPcSession != null

    val initials: String
        get() = displayName.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString("") { it.first().uppercaseChar().toString() }
            .ifBlank { email.take(2).uppercase() }

    fun isTokenExpired(nowEpochMs: Long = System.currentTimeMillis()): Boolean {
        return nowEpochMs >= tokenExpiresAtEpochMs
    }
}

/**
 * Snapshot of HTTP / Connect-RPC authentication headers for outgoing requests.
 */
data class AuthHeaders(
    val authorization: String?,
    val csrfToken: String,
    val apiKey: String? = null,
    val selectedModelId: String = AiModel.DEFAULT.modelId,
    val accountId: String? = null,
    val hostClient: String = ConnectFrameBuilder.DEFAULT_HOST_CLIENT
) {
    fun toMap(): Map<String, String> {
        val map = linkedMapOf<String, String>()
        if (!authorization.isNullOrBlank()) {
            map[HEADER_AUTHORIZATION] = authorization
        }
        if (csrfToken.isNotBlank()) {
            map[ConnectFrameBuilder.HEADER_CSRF] = csrfToken
        }
        if (!apiKey.isNullOrBlank()) {
            map[HEADER_API_KEY] = apiKey
        }
        if (selectedModelId.isNotBlank()) {
            map[HEADER_MODEL] = selectedModelId
        }
        if (!accountId.isNullOrBlank()) {
            map[HEADER_ACCOUNT_ID] = accountId
        }
        map[ConnectFrameBuilder.HEADER_HOST_CLIENT] = hostClient
        return map
    }

    fun toConnectFrameHeaders(
        contentType: String = ConnectFrameBuilder.CONTENT_TYPE_JSON
    ): Map<String, List<String>> {
        val headers = linkedMapOf<String, List<String>>()
        headers[ConnectFrameBuilder.HEADER_CONTENT_TYPE] = listOf(contentType)
        toMap().forEach { (key, value) ->
            headers[key] = listOf(value)
        }
        return headers
    }

    companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val HEADER_API_KEY = "x-goog-api-key"
        const val HEADER_MODEL = "x-antigravity-model"
        const val HEADER_ACCOUNT_ID = "x-antigravity-account-id"
    }
}

/**
 * Reactive authentication state exposed by [AccountRepository] / [AccountSessionManager].
 * Supports both exhaustive pattern matching (`when (state)`) and direct convenience property access.
 */
sealed class AuthState {
    open val isAuthenticated: Boolean
        get() = this is Authenticated

    open val account: AntigravityAccount?
        get() = (this as? Authenticated)?.account

    open val currentAccount: AntigravityAccount?
        get() = account

    open val selectedModel: AiModel
        get() = account?.selectedModel ?: AiModel.DEFAULT

    open val isPairedWithPc: Boolean
        get() = account?.pairedPcSession != null

    open val pairedPcSession: PcPairingSession?
        get() = account?.pairedPcSession

    open val workspaceSyncSettings: WorkspaceSyncSettings
        get() = account?.workspaceSyncSettings ?: WorkspaceSyncSettings()

    open val isLoading: Boolean
        get() = this is Authenticating

    open val errorMessage: String?
        get() = (this as? Error)?.message

    data object SignedOut : AuthState()

    data class Authenticating(
        val method: AuthMethod = AuthMethod.OAUTH_GOOGLE,
        val statusMessage: String = "Signing in to Antigravity..."
    ) : AuthState()

    data class Authenticated(
        override val account: AntigravityAccount
    ) : AuthState() {
        override val isPairedWithPc: Boolean = account.pairedPcSession != null
    }

    data class Error(
        val message: String,
        val previousAccount: AntigravityAccount? = null,
        val isRecoverable: Boolean = true
    ) : AuthState() {
        override val account: AntigravityAccount?
            get() = previousAccount
    }
}
