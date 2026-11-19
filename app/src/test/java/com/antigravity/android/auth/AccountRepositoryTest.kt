package com.antigravity.android.auth

import com.antigravity.android.data.network.ConnectFrameBuilder
import com.antigravity.android.data.network.ConnectWebSocketClient
import com.antigravity.android.data.network.OrchestratorServiceImpl
import com.antigravity.android.domain.OrchestratorRepositoryImpl
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class AccountRepositoryTest {

    private lateinit var storage: InMemoryAccountStorage
    private var currentTimeMs: Long = 1_700_000_000_000L
    private lateinit var repository: AccountSessionManager
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        storage = InMemoryAccountStorage()
        currentTimeMs = 1_700_000_000_000L
        repository = AccountSessionManager(
            storage = storage,
            clock = { currentTimeMs }
        )
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // =========================================================================
    // 1. Initial State & Account Sign-In Flows (OAuth, API Key, Bearer, One-Tap)
    // =========================================================================

    @Test
    fun testInitialState_isSignedOutWithDefaultArgonModel() {
        assertEquals(AuthState.SignedOut, repository.authState.value)
        assertFalse(repository.authState.value.isAuthenticated)
        assertNull(repository.currentAccount.value)
        assertEquals(AiModel.GEMINI_4_ARGON_HIGH, repository.selectedModel.value)
        assertFalse(repository.authState.value.isPairedWithPc)
    }

    @Test
    fun testSignInWithOAuth_createsAuthenticatedAccountAndPersists() {
        val result = repository.signInWithOAuth(
            oauthToken = "ya29.a0AfB_byC_google_oauth_token_12345",
            email = "dali@antigravity.google",
            displayName = "Dali Deepmind",
            avatarUrl = "https://antigravity.google/avatars/dali.png",
            refreshToken = "1//0g_refresh_token_xyz",
            tier = AccountTier.ULTRA,
            provider = AuthMethod.OAUTH_GOOGLE,
            expiresInSeconds = 7200L
        )

        assertTrue("OAuth sign-in should succeed", result.isSuccess)
        val account = result.getOrThrow()
        assertEquals("dali@antigravity.google", account.email)
        assertEquals("Dali Deepmind", account.displayName)
        assertEquals("DD", account.initials)
        assertEquals(AccountTier.ULTRA, account.tier)
        assertEquals("Antigravity Ultra", account.plan)
        assertEquals(AuthMethod.OAUTH_GOOGLE, account.authMethod)
        assertEquals("ya29.a0AfB_byC_google_oauth_token_12345", account.activeAccessToken)
        assertEquals(currentTimeMs + 7200_000L, account.tokenExpiresAtEpochMs)
        assertTrue(account.csrfToken.isNotBlank())

        val state = repository.authState.value
        assertTrue(state is AuthState.Authenticated)
        assertTrue(state.isAuthenticated)
        assertEquals(account, state.account)
        assertEquals(account, storage.loadAccount())
    }

    @Test
    fun testSignInWithOAuth_blankTokenOrInvalidEmail_setsErrorState() {
        val blankTokenResult = repository.signInWithOAuth(
            oauthToken = "   ",
            email = "dali@antigravity.google"
        )
        assertTrue(blankTokenResult.isFailure)
        assertTrue(repository.authState.value is AuthState.Error)

        val badEmailResult = repository.signInWithOAuth(
            oauthToken = "valid_oauth_token_123",
            email = "invalid-email-without-at"
        )
        assertTrue(badEmailResult.isFailure)
        assertTrue(repository.authState.value is AuthState.Error)
        assertNotNull(repository.authState.value.errorMessage)
    }

    @Test
    fun testSignInWithApiKey_authenticatesAndExposesApiKeyHeader() {
        val apiKey = "AIzaSyAntigravitySecretKey987654321"
        val result = repository.signInWithApiKey(
            apiKey = apiKey,
            displayName = "Key Operator",
            email = "key@antigravity.dev",
            tier = AccountTier.PRO
        )

        assertTrue(result.isSuccess)
        val account = result.getOrThrow()
        assertEquals(AuthMethod.API_KEY, account.authMethod)
        assertEquals(apiKey, account.apiKey)
        assertEquals(apiKey, account.activeAccessToken)

        val headers = repository.getAuthHeaders()
        assertEquals("Bearer $apiKey", headers[AuthHeaders.HEADER_AUTHORIZATION])
        assertEquals(apiKey, headers[AuthHeaders.HEADER_API_KEY])
        assertEquals(account.csrfToken, headers[ConnectFrameBuilder.HEADER_CSRF])
    }

    @Test
    fun testSignInWithBearerToken_stripsBearerPrefixAndStoresCsrf() {
        val result = repository.signInWithBearerToken(
            bearerToken = "Bearer ag_direct_token_998877",
            csrfToken = "custom-csrf-token-5544",
            displayName = "Direct Token User",
            email = "direct@antigravity.dev"
        )

        assertTrue(result.isSuccess)
        val account = result.getOrThrow()
        assertEquals("ag_direct_token_998877", account.bearerToken)
        assertEquals("custom-csrf-token-5544", account.csrfToken)
        assertEquals("Bearer ag_direct_token_998877", repository.getAuthHeaders()["Authorization"])
        assertEquals("custom-csrf-token-5544", repository.getAuthHeaders()[ConnectFrameBuilder.HEADER_CSRF])
    }

    @Test
    fun testSignInOneTap_authenticatesImmediately() {
        val result = repository.signInOneTap(
            email = "dali@antigravity.google",
            displayName = "Dali",
            tier = AccountTier.ULTRA
        )
        assertTrue(result.isSuccess)
        assertTrue(repository.authState.value.isAuthenticated)
        assertEquals(AccountTier.ULTRA, repository.currentAccount.value?.tier)
    }

    // =========================================================================
    // 2. Session Persistence Across Restart
    // =========================================================================

    @Test
    fun testSessionPersistence_restoresAccountModelAndSyncSettingsOnReinit() {
        repository.signInWithGoogle(
            idOrAccessToken = "google_token_persisted_001",
            email = "persisted@antigravity.google",
            displayName = "Persisted User",
            tier = AccountTier.ULTRA
        )
        repository.selectModel(AiModel.GEMINI_PRO)
        repository.updateWorkspaceSyncSettings {
            it.copy(
                autoSyncHistory = false,
                workspaceRootPath = "/sdcard/CustomAntigravity"
            )
        }

        // Simulate app restart with the same underlying storage
        val restoredManager = AccountSessionManager(
            storage = storage,
            clock = { currentTimeMs }
        )

        assertTrue(restoredManager.authState.value.isAuthenticated)
        val restoredAccount = restoredManager.currentAccount.value
        assertNotNull(restoredAccount)
        assertEquals("persisted@antigravity.google", restoredAccount?.email)
        assertEquals("google_token_persisted_001", restoredAccount?.activeAccessToken)
        assertEquals(AiModel.GEMINI_PRO, restoredManager.selectedModel.value)
        assertEquals(AiModel.GEMINI_PRO, restoredAccount?.selectedModel)
        assertFalse(restoredManager.workspaceSyncSettings.value.autoSyncHistory)
        assertEquals("/sdcard/CustomAntigravity", restoredManager.workspaceSyncSettings.value.workspaceRootPath)
    }

    // =========================================================================
    // 3. AI Model Switching
    // =========================================================================

    @Test
    fun testModelSwitching_updatesAccountAndHeaders() {
        repository.signInOneTap()
        assertEquals(AiModel.GEMINI_4_ARGON_HIGH, repository.selectedModel.value)
        assertEquals("gemini-4-argon-high", repository.getAuthHeaders()[AuthHeaders.HEADER_MODEL])

        // Switch by enum
        val flashResult = repository.selectModel(AiModel.GEMINI_FLASH)
        assertTrue(flashResult.isSuccess)
        assertEquals(AiModel.GEMINI_FLASH, repository.selectedModel.value)
        assertEquals(AiModel.GEMINI_FLASH, repository.currentAccount.value?.selectedModel)
        assertEquals("gemini-2.5-flash", repository.getAuthHeaders()[AuthHeaders.HEADER_MODEL])

        // Switch by modelId string
        val proResult = repository.selectModelById("gemini-2.5-pro")
        assertTrue(proResult.isSuccess)
        assertEquals(AiModel.GEMINI_PRO, repository.selectedModel.value)
        assertEquals("gemini-2.5-pro", repository.getAuthHeaders()[AuthHeaders.HEADER_MODEL])

        // Switch back to Argon High by display name
        val argonResult = repository.selectModelById("Gemini 4 Argon High")
        assertTrue(argonResult.isSuccess)
        assertEquals(AiModel.GEMINI_4_ARGON_HIGH, repository.selectedModel.value)

        // Unknown model ID fails gracefully
        val invalidResult = repository.selectModelById("unknown-gpt-model")
        assertTrue(invalidResult.isFailure)
        assertEquals(AiModel.GEMINI_4_ARGON_HIGH, repository.selectedModel.value)
    }

    // =========================================================================
    // 4. One-Tap PC Session Pairing via QR / Pairing Token & Endpoint Sync
    // =========================================================================

    @Test
    fun testPairWithPcSession_uriFormat_updatesAccountAndOrchestratorEndpoint() {
        val wsClient = ConnectWebSocketClient()
        val orchService = OrchestratorServiceImpl(wsClient)
        val orchRepo = OrchestratorRepositoryImpl(orchService, wsClient)
        repository.bindOrchestrator(orchRepo, wsClient)

        val qrUri = "antigravity://pair?endpoint=http://192.168.1.42:49784&csrf=pc-csrf-9001&token=pc-bearer-xyz&pcHostname=Dali-Workstation&email=dali@antigravity.google&model=gemini-4-argon-high"
        val pairResult = repository.pairWithPcSession(qrUri)

        assertTrue("URI QR pairing must succeed", pairResult.isSuccess)
        val session = pairResult.getOrThrow()
        assertEquals("ws://192.168.1.42:49784/connect-websocket", session.endpointUrl)
        assertEquals("pc-csrf-9001", session.csrfToken)
        assertEquals("pc-bearer-xyz", session.bearerToken)
        assertEquals("Dali-Workstation", session.pcHostname)

        // Verify Account State was automatically authenticated and paired
        assertTrue(repository.authState.value.isAuthenticated)
        assertTrue(repository.authState.value.isPairedWithPc)
        assertEquals("dali@antigravity.google", repository.currentAccount.value?.email)

        // Verify ConnectWebSocketClient received the updated endpoint and CSRF token
        assertEquals("ws://192.168.1.42:49784/connect-websocket", wsClient.getEndpointUrl())
        assertEquals("pc-csrf-9001", wsClient.getCsrfToken())
    }

    @Test
    fun testPairWithPcSession_jsonAndBase64AndPipeFormats() {
        // 1. JSON format
        val jsonPayload = """
            {
                "endpointUrl": "10.0.2.2:49784",
                "csrfToken": "csrf-json-111",
                "sessionToken": "tok-json-222",
                "pcHostname": "Studio-PC",
                "selectedModelId": "gemini-2.5-pro"
            }
        """.trimIndent()
        val jsonResult = repository.pairWithPcSession(jsonPayload)
        assertTrue(jsonResult.isSuccess)
        assertEquals("ws://10.0.2.2:49784/connect-websocket", jsonResult.getOrThrow().endpointUrl)
        assertEquals("csrf-json-111", repository.getActiveCsrfToken())
        assertEquals(AiModel.GEMINI_PRO, repository.selectedModel.value)

        // 2. Base64-encoded QR token
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(jsonPayload.toByteArray(StandardCharsets.UTF_8))
        val b64Result = repository.pairWithPcSession("agpair://$encoded")
        assertTrue(b64Result.isSuccess)
        assertEquals("Studio-PC", b64Result.getOrThrow().pcHostname)

        // 3. Pipe-delimited agpair: format
        val pipeToken = "agpair:192.168.0.10:49784|csrf-pipe-333|bearer-pipe-444|Gaming-Rig"
        val pipeResult = repository.pairWithPcSession(pipeToken)
        assertTrue(pipeResult.isSuccess)
        assertEquals("ws://192.168.0.10:49784/connect-websocket", pipeResult.getOrThrow().endpointUrl)
        assertEquals("csrf-pipe-333", pipeResult.getOrThrow().csrfToken)
        assertEquals("Gaming-Rig", pipeResult.getOrThrow().pcHostname)

        // 4. Shorthand host:port@csrfToken
        val shorthandResult = repository.pairWithPcSession("172.16.0.5:49784@csrf-short-777")
        assertTrue(shorthandResult.isSuccess)
        assertEquals("ws://172.16.0.5:49784/connect-websocket", shorthandResult.getOrThrow().endpointUrl)
        assertEquals("csrf-short-777", shorthandResult.getOrThrow().csrfToken)

        // Unpair
        repository.unpairPcSession()
        assertNull(repository.pairedPcSession.value)
        assertFalse(repository.authState.value.isPairedWithPc)
    }

    // =========================================================================
    // 5. Token Refresh & Expiration Handling
    // =========================================================================

    @Test
    fun testTokenExpirationAndRefresh_rotatesTokenAndExtendsExpiry() {
        repository.signInWithOAuth(
            oauthToken = "initial_oauth_token",
            email = "refresh@antigravity.google",
            expiresInSeconds = 60L
        )

        val initialAccount = repository.currentAccount.value!!
        assertFalse(initialAccount.isTokenExpired(currentTimeMs))
        assertEquals("initial_oauth_token", repository.ensureValidToken().getOrThrow())

        // Advance clock past expiration
        currentTimeMs += 120_000L
        assertTrue(initialAccount.isTokenExpired(currentTimeMs))

        // ensureValidToken should automatically trigger refreshAccessToken
        val refreshedTokenResult = repository.ensureValidToken()
        assertTrue(refreshedTokenResult.isSuccess)
        val newToken = refreshedTokenResult.getOrThrow()
        assertTrue(newToken.startsWith("ag_refreshed_"))
        assertFalse(repository.currentAccount.value!!.isTokenExpired(currentTimeMs))
    }

    // =========================================================================
    // 6. ConnectProtocol Frame & OkHttp Auth Header Injection
    // =========================================================================

    @Test
    fun testCreateAuthenticatedStartFrame_injectsBearerCsrfAndModelHeaders() {
        repository.signInWithBearerToken(
            bearerToken = "ag_secret_bearer_token",
            csrfToken = "connect-csrf-token-888",
            email = "connect@antigravity.google"
        )
        repository.selectModel(AiModel.GEMINI_4_ARGON_HIGH)

        val frame = repository.createAuthenticatedStartFrame(
            procedure = "/exa.language_server_pb.LanguageServerService/StartCascade",
            payload = buildJsonObject { put("initial_prompt", "Hello Phone Agent") },
            isStream = false
        )

        assertEquals("start", frame.type)
        assertEquals("/exa.language_server_pb.LanguageServerService/StartCascade", frame.procedure)
        val headers = frame.headers!!
        assertEquals(listOf("Bearer ag_secret_bearer_token"), headers[AuthHeaders.HEADER_AUTHORIZATION])
        assertEquals(listOf("connect-csrf-token-888"), headers[ConnectFrameBuilder.HEADER_CSRF])
        assertEquals(listOf("gemini-4-argon-high"), headers[AuthHeaders.HEADER_MODEL])
        assertEquals(listOf("antigravity-android"), headers[ConnectFrameBuilder.HEADER_HOST_CLIENT])
        assertEquals(listOf("application/json"), headers[ConnectFrameBuilder.HEADER_CONTENT_TYPE])
    }

    @Test
    fun testOkHttpAuthInterceptor_injectsHeadersOnRealHttpCall() {
        repository.signInWithBearerToken(
            bearerToken = "live_interceptor_token_123",
            csrfToken = "interceptor_csrf_456",
            email = "http@antigravity.google"
        )

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(repository.createOkHttpAuthInterceptor())
            .build()

        val request = Request.Builder()
            .url(mockWebServer.url("/exa.language_server_pb.LanguageServerService/GetAllCascadeSummaries"))
            .build()

        val response = okHttpClient.newCall(request).execute()
        assertTrue(response.isSuccessful)

        val recorded = mockWebServer.takeRequest()
        assertEquals("Bearer live_interceptor_token_123", recorded.getHeader("Authorization"))
        assertEquals("interceptor_csrf_456", recorded.getHeader(ConnectFrameBuilder.HEADER_CSRF))
        assertEquals("gemini-4-argon-high", recorded.getHeader(AuthHeaders.HEADER_MODEL))
        assertEquals("antigravity-android", recorded.getHeader(ConnectFrameBuilder.HEADER_HOST_CLIENT))
    }

    // =========================================================================
    // 7. Sign-Out Flow
    // =========================================================================

    @Test
    fun testSignOut_clearsStorageAndResetsState() {
        repository.signInOneTap()
        repository.pairWithPcEndpoint(
            endpointUrl = "192.168.1.55:49784",
            csrfToken = "csrf-signout-test"
        )
        assertTrue(repository.authState.value.isAuthenticated)
        assertNotNull(storage.loadAccount())

        repository.signOut()

        assertEquals(AuthState.SignedOut, repository.authState.value)
        assertFalse(repository.authState.value.isAuthenticated)
        assertNull(repository.currentAccount.value)
        assertNull(repository.pairedPcSession.value)
        assertNull(storage.loadAccount())
    }
}
