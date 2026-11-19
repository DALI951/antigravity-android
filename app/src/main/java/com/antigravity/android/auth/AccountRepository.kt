package com.antigravity.android.auth

import android.content.Context
import com.antigravity.android.data.network.ConnectFrameBuilder
import com.antigravity.android.data.network.ConnectFrameEnvelope
import com.antigravity.android.data.network.ConnectWebSocketClient
import com.antigravity.android.domain.OrchestratorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Primary interface for managing the user's Antigravity account, authentication tokens,
 * AI model selection, PC orchestrator pairing, workspace sync settings, and Connect-RPC headers.
 */
interface AccountRepository {
    val authState: StateFlow<AuthState>
    val currentAccount: StateFlow<AntigravityAccount?>
    val selectedModel: StateFlow<AiModel>
    val workspaceSyncSettings: StateFlow<WorkspaceSyncSettings>
    val pairedPcSession: StateFlow<PcPairingSession?>

    fun signInWithOAuth(
        oauthToken: String,
        email: String = "developer@antigravity.dev",
        displayName: String = "Antigravity Developer",
        avatarUrl: String? = null,
        refreshToken: String? = null,
        tier: AccountTier = AccountTier.PRO,
        provider: AuthMethod = AuthMethod.OAUTH_GOOGLE,
        expiresInSeconds: Long = 3600L
    ): Result<AntigravityAccount>

    fun signInWithGoogle(
        idOrAccessToken: String,
        email: String,
        displayName: String,
        avatarUrl: String? = null,
        refreshToken: String? = null,
        tier: AccountTier = AccountTier.PRO
    ): Result<AntigravityAccount>

    fun signInWithApiKey(
        apiKey: String,
        displayName: String = "Antigravity API User",
        email: String = "apikey@antigravity.dev",
        tier: AccountTier = AccountTier.PRO
    ): Result<AntigravityAccount>

    fun signInWithBearerToken(
        bearerToken: String,
        csrfToken: String = "",
        displayName: String = "Antigravity User",
        email: String = "developer@antigravity.dev",
        tier: AccountTier = AccountTier.PRO,
        expiresInSeconds: Long = 86400L
    ): Result<AntigravityAccount>

    fun signInOneTap(
        email: String = "dali@antigravity.google",
        displayName: String = "Dali (Antigravity Ultra)",
        tier: AccountTier = AccountTier.ULTRA
    ): Result<AntigravityAccount>

    fun parsePairingToken(rawTokenOrQr: String): Result<PcPairingPayload>
    fun pairWithPcSession(pairingTokenOrQr: String): Result<PcPairingSession>
    fun pairWithPcEndpoint(
        endpointUrl: String,
        csrfToken: String,
        pcHostname: String = "Antigravity-PC",
        bearerToken: String = ""
    ): Result<PcPairingSession>
    fun unpairPcSession(): Result<Unit>

    fun refreshAccessToken(
        newAccessToken: String? = null,
        expiresInSeconds: Long = 3600L
    ): Result<AntigravityAccount>
    fun ensureValidToken(): Result<String>

    fun selectModel(model: AiModel): Result<AiModel>
    fun selectModelById(modelId: String): Result<AiModel>
    fun getAvailableModels(): List<AiModel>

    fun updateWorkspaceSyncSettings(settings: WorkspaceSyncSettings): Result<WorkspaceSyncSettings>
    fun updateWorkspaceSyncSettings(
        transform: (WorkspaceSyncSettings) -> WorkspaceSyncSettings
    ): Result<WorkspaceSyncSettings>
    fun triggerWorkspaceSync(): Result<WorkspaceSyncSettings>

    fun getAuthHeadersObject(): AuthHeaders
    fun getAuthHeaders(): Map<String, String>
    fun getConnectFrameHeaders(): Map<String, List<String>>
    fun getActiveCsrfToken(): String
    fun getActiveBearerToken(): String?

    fun createAuthenticatedStartFrame(
        procedure: String,
        payload: JsonObject = buildJsonObject {},
        streamId: String = UUID.randomUUID().toString(),
        isStream: Boolean = false
    ): ConnectFrameEnvelope

    fun createOkHttpAuthInterceptor(): Interceptor

    fun bindOrchestrator(
        orchestratorRepository: OrchestratorRepository?,
        webSocketClient: ConnectWebSocketClient? = null
    )

    fun signOut()

    companion object {
        operator fun invoke(
            storage: AccountStorage = InMemoryAccountStorage(),
            orchestratorRepository: OrchestratorRepository? = null,
            webSocketClient: ConnectWebSocketClient? = null,
            clock: () -> Long = { System.currentTimeMillis() }
        ): AccountSessionManager = AccountSessionManager(
            storage = storage,
            orchestratorRepository = orchestratorRepository,
            webSocketClient = webSocketClient,
            clock = clock
        )

        operator fun invoke(
            context: Context?,
            orchestratorRepository: OrchestratorRepository? = null,
            webSocketClient: ConnectWebSocketClient? = null
        ): AccountSessionManager = AccountSessionManager(
            context = context,
            orchestratorRepository = orchestratorRepository,
            webSocketClient = webSocketClient
        )
    }
}

typealias AccountRepositoryImpl = AccountSessionManager

/**
 * Concrete implementation of [AccountRepository] handling sign-in flows, PC QR/token pairing,
 * persistent session state, AI model switching, token refresh, and Connect-RPC header injection.
 */
class AccountSessionManager(
    private val storage: AccountStorage = InMemoryAccountStorage(),
    private var orchestratorRepository: OrchestratorRepository? = null,
    private var webSocketClient: ConnectWebSocketClient? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AccountRepository {

    constructor(
        context: Context?,
        orchestratorRepository: OrchestratorRepository? = null,
        webSocketClient: ConnectWebSocketClient? = null
    ) : this(
        storage = SharedPreferencesAccountStorage.create(context),
        orchestratorRepository = orchestratorRepository,
        webSocketClient = webSocketClient
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentAccount = MutableStateFlow<AntigravityAccount?>(null)
    override val currentAccount: StateFlow<AntigravityAccount?> = _currentAccount.asStateFlow()

    private val _selectedModel = MutableStateFlow(AiModel.DEFAULT)
    override val selectedModel: StateFlow<AiModel> = _selectedModel.asStateFlow()

    private val _workspaceSyncSettings = MutableStateFlow(WorkspaceSyncSettings())
    override val workspaceSyncSettings: StateFlow<WorkspaceSyncSettings> = _workspaceSyncSettings.asStateFlow()

    private val _pairedPcSession = MutableStateFlow<PcPairingSession?>(null)
    override val pairedPcSession: StateFlow<PcPairingSession?> = _pairedPcSession.asStateFlow()

    init {
        restoreSessionFromStorage()
    }

    private fun restoreSessionFromStorage() {
        val storedModel = storage.loadSelectedModel()
        if (storedModel != null) {
            _selectedModel.value = storedModel
        }
        val storedSync = storage.loadWorkspaceSyncSettings()
        if (storedSync != null) {
            _workspaceSyncSettings.value = storedSync
        }
        val storedAccount = storage.loadAccount()
        if (storedAccount != null) {
            val effectiveAccount = storedAccount.copy(
                selectedModel = storedModel ?: storedAccount.selectedModel,
                workspaceSyncSettings = storedSync ?: storedAccount.workspaceSyncSettings
            )
            applyAuthenticatedAccount(effectiveAccount, persist = false)
        }
    }

    override fun bindOrchestrator(
        orchestratorRepository: OrchestratorRepository?,
        webSocketClient: ConnectWebSocketClient?
    ) {
        this.orchestratorRepository = orchestratorRepository
        this.webSocketClient = webSocketClient
        syncEndpointWithOrchestrator(_currentAccount.value)
    }

    override fun signInWithOAuth(
        oauthToken: String,
        email: String,
        displayName: String,
        avatarUrl: String?,
        refreshToken: String?,
        tier: AccountTier,
        provider: AuthMethod,
        expiresInSeconds: Long
    ): Result<AntigravityAccount> = runCatching {
        val cleanToken = oauthToken.trim()
        require(cleanToken.isNotBlank()) { "OAuth token must not be blank" }
        val cleanEmail = email.trim()
        require(cleanEmail.isNotBlank() && cleanEmail.contains("@")) {
            "A valid email address is required for OAuth sign-in"
        }
        require(expiresInSeconds > 0) { "Token expiration duration must be positive" }

        _authState.value = AuthState.Authenticating(
            method = provider,
            statusMessage = "Signing in as $cleanEmail..."
        )

        val now = clock()
        val existingPairedSession = _pairedPcSession.value
        val csrf = existingPairedSession?.csrfToken?.takeIf { it.isNotBlank() }
            ?: _currentAccount.value?.csrfToken?.takeIf { it.isNotBlank() }
            ?: generateDeterministicCsrf(cleanEmail, cleanToken)

        val account = AntigravityAccount(
            id = generateAccountId(cleanEmail),
            displayName = displayName.trim().ifBlank { cleanEmail.substringBefore("@") },
            email = cleanEmail,
            avatarUrl = avatarUrl?.trim()?.takeIf { it.isNotBlank() },
            tier = tier,
            plan = tier.displayName,
            authMethod = provider,
            oauthToken = cleanToken,
            bearerToken = cleanToken,
            refreshToken = refreshToken?.trim()?.takeIf { it.isNotBlank() } ?: "ag_refresh_${UUID.randomUUID()}",
            apiKey = null,
            csrfToken = csrf,
            tokenExpiresAtEpochMs = now + expiresInSeconds * 1000L,
            selectedModel = _selectedModel.value,
            workspaceSyncSettings = _workspaceSyncSettings.value,
            pairedPcSession = existingPairedSession,
            lastSignInEpochMs = now
        )

        applyAuthenticatedAccount(account, persist = true)
        account
    }.onFailure { err ->
        _authState.value = AuthState.Error(
            message = err.message ?: "OAuth sign-in failed",
            previousAccount = _currentAccount.value
        )
    }

    override fun signInWithGoogle(
        idOrAccessToken: String,
        email: String,
        displayName: String,
        avatarUrl: String?,
        refreshToken: String?,
        tier: AccountTier
    ): Result<AntigravityAccount> {
        return signInWithOAuth(
            oauthToken = idOrAccessToken,
            email = email,
            displayName = displayName,
            avatarUrl = avatarUrl,
            refreshToken = refreshToken,
            tier = tier,
            provider = AuthMethod.OAUTH_GOOGLE
        )
    }

    override fun signInWithApiKey(
        apiKey: String,
        displayName: String,
        email: String,
        tier: AccountTier
    ): Result<AntigravityAccount> = runCatching {
        val cleanKey = apiKey.trim()
        require(cleanKey.length >= 8) { "API key must be at least 8 characters long" }
        val cleanEmail = email.trim().ifBlank { "apikey@antigravity.dev" }

        _authState.value = AuthState.Authenticating(
            method = AuthMethod.API_KEY,
            statusMessage = "Validating API key..."
        )

        val now = clock()
        val existingPairedSession = _pairedPcSession.value
        val csrf = existingPairedSession?.csrfToken?.takeIf { it.isNotBlank() }
            ?: _currentAccount.value?.csrfToken?.takeIf { it.isNotBlank() }
            ?: generateDeterministicCsrf(cleanEmail, cleanKey)

        val account = AntigravityAccount(
            id = generateAccountId(cleanEmail),
            displayName = displayName.trim().ifBlank { "Antigravity API User" },
            email = cleanEmail,
            avatarUrl = null,
            tier = tier,
            plan = tier.displayName,
            authMethod = AuthMethod.API_KEY,
            oauthToken = null,
            bearerToken = cleanKey,
            refreshToken = null,
            apiKey = cleanKey,
            csrfToken = csrf,
            tokenExpiresAtEpochMs = Long.MAX_VALUE,
            selectedModel = _selectedModel.value,
            workspaceSyncSettings = _workspaceSyncSettings.value,
            pairedPcSession = existingPairedSession,
            lastSignInEpochMs = now
        )

        applyAuthenticatedAccount(account, persist = true)
        account
    }.onFailure { err ->
        _authState.value = AuthState.Error(
            message = err.message ?: "API Key sign-in failed",
            previousAccount = _currentAccount.value
        )
    }

    override fun signInWithBearerToken(
        bearerToken: String,
        csrfToken: String,
        displayName: String,
        email: String,
        tier: AccountTier,
        expiresInSeconds: Long
    ): Result<AntigravityAccount> = runCatching {
        val cleanToken = bearerToken.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        require(cleanToken.isNotBlank()) { "Bearer token must not be blank" }
        require(expiresInSeconds > 0) { "Token expiration duration must be positive" }

        val cleanEmail = email.trim().ifBlank { "developer@antigravity.dev" }
        val now = clock()
        val existingPairedSession = _pairedPcSession.value
        val resolvedCsrf = csrfToken.trim().ifBlank {
            existingPairedSession?.csrfToken?.takeIf { it.isNotBlank() }
                ?: generateDeterministicCsrf(cleanEmail, cleanToken)
        }

        val account = AntigravityAccount(
            id = generateAccountId(cleanEmail),
            displayName = displayName.trim().ifBlank { "Antigravity User" },
            email = cleanEmail,
            avatarUrl = null,
            tier = tier,
            plan = tier.displayName,
            authMethod = AuthMethod.DIRECT_TOKEN,
            oauthToken = null,
            bearerToken = cleanToken,
            refreshToken = "ag_refresh_${UUID.randomUUID()}",
            apiKey = null,
            csrfToken = resolvedCsrf,
            tokenExpiresAtEpochMs = now + expiresInSeconds * 1000L,
            selectedModel = _selectedModel.value,
            workspaceSyncSettings = _workspaceSyncSettings.value,
            pairedPcSession = existingPairedSession,
            lastSignInEpochMs = now
        )

        applyAuthenticatedAccount(account, persist = true)
        account
    }.onFailure { err ->
        _authState.value = AuthState.Error(
            message = err.message ?: "Bearer token sign-in failed",
            previousAccount = _currentAccount.value
        )
    }

    override fun signInOneTap(
        email: String,
        displayName: String,
        tier: AccountTier
    ): Result<AntigravityAccount> {
        val generatedToken = "ag_oauth_live_${generateAccountId(email)}_${clock()}"
        return signInWithOAuth(
            oauthToken = generatedToken,
            email = email,
            displayName = displayName,
            tier = tier,
            provider = AuthMethod.ANTIGRAVITY_OAUTH,
            expiresInSeconds = 86400L
        )
    }

    override fun parsePairingToken(rawTokenOrQr: String): Result<PcPairingPayload> = runCatching {
        val trimmed = rawTokenOrQr.trim()
        require(trimmed.isNotBlank()) { "Pairing token or QR payload cannot be blank" }

        // 1. Check if Base64-encoded JSON or URI (with optional "agpair://" or "agpair64:" prefix)
        val decodedCandidate = tryDecodeBase64Payload(trimmed)
        val input = decodedCandidate ?: trimmed

        when {
            // JSON payload: {"endpointUrl": "...", "csrfToken": "..."}
            input.startsWith("{") && input.endsWith("}") -> {
                parseJsonPairingPayload(input)
            }
            // Pipe-delimited token: agpair:<endpoint>|<csrf>|<token>|<hostname>
            input.startsWith("agpair:", ignoreCase = true) -> {
                val body = input.substringAfter(":")
                val parts = body.split("|")
                require(parts.size >= 2) { "Invalid agpair token format: expected at least endpoint and csrf" }
                val endpoint = normalizeWsEndpoint(parts[0])
                val csrf = parts[1].trim()
                require(csrf.isNotBlank()) { "CSRF token in pairing payload cannot be blank" }
                val sessionToken = parts.getOrNull(2)?.trim().orEmpty()
                val hostname = parts.getOrNull(3)?.trim()?.takeIf { it.isNotBlank() } ?: "Antigravity-PC"
                val email = parts.getOrNull(4)?.trim()?.takeIf { it.isNotBlank() }
                PcPairingPayload(
                    endpointUrl = endpoint,
                    csrfToken = csrf,
                    sessionToken = sessionToken.ifBlank { "ag_pc_session_$csrf" },
                    pcHostname = hostname,
                    accountEmail = email
                )
            }
            // URI with query parameters: antigravity://pair?endpoint=...&csrf=... or ws://.../pair?csrf=...
            input.contains("://") && input.contains("?") -> {
                parseUriPairingPayload(input)
            }
            // Shorthand host:port@csrfToken
            input.contains("@") && !input.contains(" ") -> {
                val endpointPart = input.substringBeforeLast("@").trim()
                val csrfPart = input.substringAfterLast("@").trim()
                require(endpointPart.isNotBlank() && csrfPart.isNotBlank()) {
                    "Invalid shorthand pairing code; expected host:port@csrfToken"
                }
                PcPairingPayload(
                    endpointUrl = normalizeWsEndpoint(endpointPart),
                    csrfToken = csrfPart,
                    sessionToken = "ag_pc_session_$csrfPart",
                    pcHostname = endpointPart.substringBefore(":").removePrefix("ws://").removePrefix("http://")
                        .ifBlank { "Antigravity-PC" }
                )
            }
            else -> throw IllegalArgumentException("Unrecognized PC pairing token format")
        }
    }

    override fun pairWithPcSession(pairingTokenOrQr: String): Result<PcPairingSession> = runCatching {
        val payload = parsePairingToken(pairingTokenOrQr).getOrThrow()
        val now = clock()
        val bearer = payload.sessionToken.ifBlank {
            _currentAccount.value?.activeAccessToken?.takeIf { it.isNotBlank() }
                ?: "ag_pc_bearer_${payload.csrfToken}"
        }

        val session = PcPairingSession(
            sessionId = "pcsess_${generateShortHash(payload.endpointUrl + ":" + payload.csrfToken)}",
            pcHostname = payload.pcHostname,
            endpointUrl = normalizeWsEndpoint(payload.endpointUrl),
            csrfToken = payload.csrfToken,
            bearerToken = bearer,
            pairedAtEpochMs = now,
            isConnected = true
        )

        val resolvedModel = payload.selectedModelId?.let { AiModel.fromId(it) } ?: _selectedModel.value
        _selectedModel.value = resolvedModel

        val updatedSync = _workspaceSyncSettings.value.copy(
            pcOrchestratorEndpoint = session.endpointUrl,
            lastSyncedAtEpochMs = now
        )
        _workspaceSyncSettings.value = updatedSync

        val existingAccount = _currentAccount.value
        val updatedAccount = if (existingAccount != null) {
            existingAccount.copy(
                csrfToken = session.csrfToken,
                bearerToken = existingAccount.bearerToken?.takeIf { it.isNotBlank() } ?: bearer,
                selectedModel = resolvedModel,
                workspaceSyncSettings = updatedSync,
                pairedPcSession = session
            )
        } else {
            val email = payload.accountEmail?.takeIf { it.isNotBlank() } ?: "paired-pc@antigravity.dev"
            val name = payload.accountName?.takeIf { it.isNotBlank() } ?: "${session.pcHostname} Operator"
            AntigravityAccount(
                id = generateAccountId(email),
                displayName = name,
                email = email,
                tier = AccountTier.PRO,
                plan = AccountTier.PRO.displayName,
                authMethod = AuthMethod.PC_PAIRING,
                oauthToken = null,
                bearerToken = bearer,
                refreshToken = "ag_refresh_${session.sessionId}",
                apiKey = null,
                csrfToken = session.csrfToken,
                tokenExpiresAtEpochMs = now + 86400_000L,
                selectedModel = resolvedModel,
                workspaceSyncSettings = updatedSync,
                pairedPcSession = session,
                lastSignInEpochMs = now
            )
        }

        applyAuthenticatedAccount(updatedAccount, persist = true)
        session
    }

    override fun pairWithPcEndpoint(
        endpointUrl: String,
        csrfToken: String,
        pcHostname: String,
        bearerToken: String
    ): Result<PcPairingSession> = runCatching {
        val cleanEndpoint = endpointUrl.trim()
        val cleanCsrf = csrfToken.trim()
        require(cleanEndpoint.isNotBlank()) { "PC endpoint URL cannot be blank" }
        require(cleanCsrf.isNotBlank()) { "CSRF token cannot be blank" }

        val syntheticToken = "agpair:${normalizeWsEndpoint(cleanEndpoint)}|$cleanCsrf|${bearerToken.trim()}|${pcHostname.trim().ifBlank { "Antigravity-PC" }}"
        pairWithPcSession(syntheticToken).getOrThrow()
    }

    override fun unpairPcSession(): Result<Unit> = runCatching {
        _pairedPcSession.value = null
        val existing = _currentAccount.value
        if (existing != null) {
            val updated = existing.copy(pairedPcSession = null)
            applyAuthenticatedAccount(updated, persist = true)
        }
    }

    override fun refreshAccessToken(
        newAccessToken: String?,
        expiresInSeconds: Long
    ): Result<AntigravityAccount> = runCatching {
        val existing = _currentAccount.value
            ?: throw IllegalStateException("Cannot refresh token: no active signed-in account")
        require(expiresInSeconds > 0) { "Expiration duration must be positive" }

        val hasRefreshCapability = !existing.refreshToken.isNullOrBlank() ||
            !existing.oauthToken.isNullOrBlank() ||
            !existing.bearerToken.isNullOrBlank()
        require(hasRefreshCapability) { "Current account does not support token refresh" }

        val now = clock()
        val rotatedToken = newAccessToken?.trim()?.takeIf { it.isNotBlank() }
            ?: "ag_refreshed_${generateShortHash(existing.id + ":" + now)}_$now"

        val updated = existing.copy(
            oauthToken = if (existing.oauthToken != null) rotatedToken else null,
            bearerToken = rotatedToken,
            tokenExpiresAtEpochMs = now + expiresInSeconds * 1000L
        )

        applyAuthenticatedAccount(updated, persist = true)
        updated
    }

    override fun ensureValidToken(): Result<String> = runCatching {
        val account = _currentAccount.value
            ?: throw IllegalStateException("No authenticated Antigravity account")

        val now = clock()
        val validAccount = if (account.isTokenExpired(now)) {
            refreshAccessToken().getOrThrow()
        } else {
            account
        }

        val token = validAccount.activeAccessToken
        require(token.isNotBlank()) { "No valid access token or API key available" }
        token
    }

    override fun selectModel(model: AiModel): Result<AiModel> = runCatching {
        _selectedModel.value = model
        storage.saveSelectedModel(model)

        val existing = _currentAccount.value
        if (existing != null) {
            val updated = existing.copy(selectedModel = model)
            applyAuthenticatedAccount(updated, persist = true)
        }
        model
    }

    override fun selectModelById(modelId: String): Result<AiModel> = runCatching {
        require(modelId.isNotBlank()) { "Model ID cannot be blank" }
        val normalized = modelId.trim()
        val matched = AiModel.entries.firstOrNull {
            it.modelId.equals(normalized, ignoreCase = true) ||
                it.name.equals(normalized, ignoreCase = true) ||
                it.displayName.equals(normalized, ignoreCase = true)
        } ?: throw IllegalArgumentException("Unsupported AI model: $modelId")

        selectModel(matched).getOrThrow()
    }

    override fun getAvailableModels(): List<AiModel> = AiModel.allModels()

    override fun updateWorkspaceSyncSettings(
        settings: WorkspaceSyncSettings
    ): Result<WorkspaceSyncSettings> = runCatching {
        _workspaceSyncSettings.value = settings
        storage.saveWorkspaceSyncSettings(settings)

        val existing = _currentAccount.value
        if (existing != null) {
            val updated = existing.copy(workspaceSyncSettings = settings)
            applyAuthenticatedAccount(updated, persist = true)
        }
        settings
    }

    override fun updateWorkspaceSyncSettings(
        transform: (WorkspaceSyncSettings) -> WorkspaceSyncSettings
    ): Result<WorkspaceSyncSettings> {
        val updated = transform(_workspaceSyncSettings.value)
        return updateWorkspaceSyncSettings(updated)
    }

    override fun triggerWorkspaceSync(): Result<WorkspaceSyncSettings> = runCatching {
        val now = clock()
        val synced = _workspaceSyncSettings.value.copy(lastSyncedAtEpochMs = now)
        updateWorkspaceSyncSettings(synced).getOrThrow()
    }

    override fun getActiveCsrfToken(): String {
        val account = _currentAccount.value
        return account?.pairedPcSession?.csrfToken?.takeIf { it.isNotBlank() }
            ?: account?.csrfToken?.takeIf { it.isNotBlank() }
            ?: webSocketClient?.getCsrfToken()?.takeIf { it.isNotBlank() }
            ?: ""
    }

    override fun getActiveBearerToken(): String? {
        return _currentAccount.value?.activeAccessToken?.takeIf { it.isNotBlank() }
    }

    override fun getAuthHeadersObject(): AuthHeaders {
        val account = _currentAccount.value
        val activeToken = account?.activeAccessToken?.takeIf { it.isNotBlank() }
        val bearerHeader = activeToken?.let {
            if (it.startsWith("Bearer ", ignoreCase = true)) it else "Bearer $it"
        }
        val csrf = getActiveCsrfToken()
        return AuthHeaders(
            authorization = bearerHeader,
            csrfToken = csrf,
            apiKey = account?.apiKey?.takeIf { it.isNotBlank() },
            selectedModelId = _selectedModel.value.modelId,
            accountId = account?.id
        )
    }

    override fun getAuthHeaders(): Map<String, String> {
        return getAuthHeadersObject().toMap()
    }

    override fun getConnectFrameHeaders(): Map<String, List<String>> {
        return getAuthHeadersObject().toConnectFrameHeaders()
    }

    override fun createAuthenticatedStartFrame(
        procedure: String,
        payload: JsonObject,
        streamId: String,
        isStream: Boolean
    ): ConnectFrameEnvelope {
        val csrf = getActiveCsrfToken().ifBlank {
            throw IllegalStateException("CSRF token is required to create an authenticated Connect start frame")
        }
        val baseFrame = ConnectFrameBuilder.createStartFrame(
            procedure = procedure,
            csrfToken = csrf,
            payload = payload,
            streamId = streamId,
            isStream = isStream
        )
        val mergedHeaders = linkedMapOf<String, List<String>>()
        baseFrame.headers?.forEach { (k, v) -> mergedHeaders[k] = v }
        getConnectFrameHeaders().forEach { (k, v) -> mergedHeaders[k] = v }

        return baseFrame.copy(headers = mergedHeaders)
    }

    override fun createOkHttpAuthInterceptor(): Interceptor {
        return Interceptor { chain ->
            val requestBuilder = chain.request().newBuilder()
            getAuthHeaders().forEach { (name, value) ->
                if (value.isNotBlank()) {
                    requestBuilder.header(name, value)
                }
            }
            chain.proceed(requestBuilder.build())
        }
    }

    override fun signOut() {
        storage.clearAccount()
        _currentAccount.value = null
        _pairedPcSession.value = null
        _authState.value = AuthState.SignedOut
        orchestratorRepository?.disconnect()
        webSocketClient?.disconnect("User signed out")
    }

    private fun applyAuthenticatedAccount(account: AntigravityAccount, persist: Boolean) {
        _currentAccount.value = account
        _selectedModel.value = account.selectedModel
        _workspaceSyncSettings.value = account.workspaceSyncSettings
        _pairedPcSession.value = account.pairedPcSession
        _authState.value = AuthState.Authenticated(account)

        if (persist) {
            storage.saveAccount(account)
        }
        syncEndpointWithOrchestrator(account)
    }

    private fun syncEndpointWithOrchestrator(account: AntigravityAccount?) {
        if (account == null) return
        val paired = account.pairedPcSession
        val endpoint = paired?.endpointUrl?.takeIf { it.isNotBlank() }
            ?: account.workspaceSyncSettings.pcOrchestratorEndpoint
        val csrf = paired?.csrfToken?.takeIf { it.isNotBlank() }
            ?: account.csrfToken

        if (endpoint.isNotBlank() && csrf.isNotBlank()) {
            orchestratorRepository?.updateEndpoint(endpoint, csrf)
            webSocketClient?.updateEndpoint(endpoint, csrf)
        }
    }

    private fun tryDecodeBase64Payload(raw: String): String? {
        val candidate = when {
            raw.startsWith("agpair://", ignoreCase = true) -> raw.substringAfter("://").trim()
            raw.startsWith("agpair64:", ignoreCase = true) -> raw.substringAfter(":").trim()
            !raw.contains("://") && !raw.startsWith("{") && !raw.startsWith("agpair:") && !raw.contains("@") -> raw
            else -> return null
        }
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(candidate)
            val decoded = String(bytes, StandardCharsets.UTF_8).trim()
            if (decoded.startsWith("{") || decoded.contains("://") || decoded.startsWith("agpair:")) {
                decoded
            } else {
                null
            }
        }.getOrElse {
            runCatching {
                val bytes = Base64.getDecoder().decode(candidate)
                val decoded = String(bytes, StandardCharsets.UTF_8).trim()
                if (decoded.startsWith("{") || decoded.contains("://") || decoded.startsWith("agpair:")) {
                    decoded
                } else {
                    null
                }
            }.getOrNull()
        }
    }

    private fun parseJsonPairingPayload(jsonText: String): PcPairingPayload {
        val obj = json.parseToJsonElement(jsonText).jsonObject
        val endpoint = (obj["endpointUrl"]?.jsonPrimitive?.content
            ?: obj["endpoint"]?.jsonPrimitive?.content
            ?: obj["url"]?.jsonPrimitive?.content
            ?: "").trim()
        val csrf = (obj["csrfToken"]?.jsonPrimitive?.content
            ?: obj["csrf"]?.jsonPrimitive?.content
            ?: "").trim()
        require(endpoint.isNotBlank()) { "JSON pairing token is missing endpointUrl" }
        require(csrf.isNotBlank()) { "JSON pairing token is missing csrfToken" }

        val sessionToken = (obj["sessionToken"]?.jsonPrimitive?.content
            ?: obj["token"]?.jsonPrimitive?.content
            ?: obj["bearerToken"]?.jsonPrimitive?.content
            ?: "").trim()
        val hostname = (obj["pcHostname"]?.jsonPrimitive?.content
            ?: obj["hostname"]?.jsonPrimitive?.content
            ?: obj["host"]?.jsonPrimitive?.content
            ?: "Antigravity-PC").trim()
        val email = obj["accountEmail"]?.jsonPrimitive?.content
            ?: obj["email"]?.jsonPrimitive?.content
        val name = obj["accountName"]?.jsonPrimitive?.content
            ?: obj["name"]?.jsonPrimitive?.content
        val model = obj["selectedModelId"]?.jsonPrimitive?.content
            ?: obj["model"]?.jsonPrimitive?.content

        return PcPairingPayload(
            endpointUrl = normalizeWsEndpoint(endpoint),
            csrfToken = csrf,
            sessionToken = sessionToken.ifBlank { "ag_pc_session_$csrf" },
            pcHostname = hostname.ifBlank { "Antigravity-PC" },
            accountEmail = email,
            accountName = name,
            selectedModelId = model
        )
    }

    private fun parseUriPairingPayload(uriString: String): PcPairingPayload {
        val queryPart = uriString.substringAfter("?", "")
        val params = mutableMapOf<String, String>()
        queryPart.split("&").forEach { pair ->
            if (pair.isNotBlank()) {
                val key = URLDecoder.decode(pair.substringBefore("="), "UTF-8").trim()
                val value = URLDecoder.decode(pair.substringAfter("=", ""), "UTF-8").trim()
                if (key.isNotBlank()) {
                    params[key] = value
                }
            }
        }

        val schemeAndAuthority = uriString.substringBefore("?")
        val explicitEndpoint = params["endpoint"]
            ?: params["endpointUrl"]
            ?: params["url"]
            ?: if (schemeAndAuthority.startsWith("ws://") ||
                schemeAndAuthority.startsWith("wss://") ||
                schemeAndAuthority.startsWith("http://") ||
                schemeAndAuthority.startsWith("https://")
            ) {
                schemeAndAuthority
            } else {
                val host = params["host"] ?: params["ip"] ?: ""
                val port = params["port"] ?: "49784"
                if (host.isNotBlank()) "ws://$host:$port/connect-websocket" else ""
            }

        val csrf = (params["csrf"] ?: params["csrfToken"] ?: params["x-codeium-csrf-token"] ?: "").trim()
        require(explicitEndpoint.isNotBlank()) { "URI pairing token is missing endpoint parameter" }
        require(csrf.isNotBlank()) { "URI pairing token is missing csrf parameter" }

        val sessionToken = (params["token"] ?: params["sessionToken"] ?: params["bearer"] ?: "").trim()
        val hostname = (params["pcHostname"] ?: params["hostname"] ?: params["pc"] ?: "Antigravity-PC").trim()

        return PcPairingPayload(
            endpointUrl = normalizeWsEndpoint(explicitEndpoint),
            csrfToken = csrf,
            sessionToken = sessionToken.ifBlank { "ag_pc_session_$csrf" },
            pcHostname = hostname.ifBlank { "Antigravity-PC" },
            accountEmail = params["email"] ?: params["accountEmail"],
            accountName = params["name"] ?: params["accountName"],
            selectedModelId = params["model"] ?: params["selectedModelId"]
        )
    }

    companion object {
        fun normalizeWsEndpoint(rawUrl: String): String {
            val trimmed = rawUrl.trim()
            val withScheme = when {
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "ws://" + trimmed.substring(7)
                trimmed.startsWith("https://", ignoreCase = true) ->
                    "wss://" + trimmed.substring(8)
                trimmed.startsWith("ws://", ignoreCase = true) ||
                    trimmed.startsWith("wss://", ignoreCase = true) -> trimmed
                else -> "ws://$trimmed"
            }.trimEnd('/')

            return if (withScheme.endsWith("/connect-websocket")) {
                withScheme
            } else {
                "$withScheme/connect-websocket"
            }
        }

        fun generateAccountId(email: String): String {
            return "acct_" + generateShortHash(email.trim().lowercase())
        }

        fun generateDeterministicCsrf(seed1: String, seed2: String): String {
            val hash = sha256Hex("$seed1:$seed2")
            return "${hash.substring(0, 8)}-${hash.substring(8, 12)}-${hash.substring(12, 16)}-${hash.substring(16, 20)}-${hash.substring(20, 32)}"
        }

        private fun generateShortHash(input: String): String {
            return sha256Hex(input).take(12)
        }

        private fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(input.toByteArray(StandardCharsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
