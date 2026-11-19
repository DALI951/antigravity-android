package com.antigravity.android.auth

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistence contract for storing the user's [AntigravityAccount], active [AiModel],
 * and [WorkspaceSyncSettings] across app restarts.
 */
interface AccountStorage {
    fun saveAccount(account: AntigravityAccount)
    fun loadAccount(): AntigravityAccount?
    fun clearAccount()

    fun saveSelectedModel(model: AiModel)
    fun loadSelectedModel(): AiModel?

    fun saveWorkspaceSyncSettings(settings: WorkspaceSyncSettings)
    fun loadWorkspaceSyncSettings(): WorkspaceSyncSettings?
}

/**
 * Thread-safe in-memory implementation of [AccountStorage] suitable for unit tests
 * and environments without an Android [Context].
 */
class InMemoryAccountStorage(
    initialAccount: AntigravityAccount? = null,
    initialModel: AiModel? = null,
    initialSyncSettings: WorkspaceSyncSettings? = null
) : AccountStorage {

    private val lock = Any()
    private var account: AntigravityAccount? = initialAccount
    private var selectedModel: AiModel? = initialModel ?: initialAccount?.selectedModel
    private var syncSettings: WorkspaceSyncSettings? = initialSyncSettings ?: initialAccount?.workspaceSyncSettings

    override fun saveAccount(account: AntigravityAccount) = synchronized(lock) {
        this.account = account
        this.selectedModel = account.selectedModel
        this.syncSettings = account.workspaceSyncSettings
    }

    override fun loadAccount(): AntigravityAccount? = synchronized(lock) {
        account
    }

    override fun clearAccount() = synchronized(lock) {
        account = null
    }

    override fun saveSelectedModel(model: AiModel) = synchronized(lock) {
        selectedModel = model
        account = account?.copy(selectedModel = model)
    }

    override fun loadSelectedModel(): AiModel? = synchronized(lock) {
        selectedModel
    }

    override fun saveWorkspaceSyncSettings(settings: WorkspaceSyncSettings) = synchronized(lock) {
        syncSettings = settings
        account = account?.copy(workspaceSyncSettings = settings)
    }

    override fun loadWorkspaceSyncSettings(): WorkspaceSyncSettings? = synchronized(lock) {
        syncSettings
    }
}

/**
 * Genuine Android [SharedPreferences] JSON-backed implementation of [AccountStorage].
 */
class SharedPreferencesAccountStorage(
    private val sharedPreferences: SharedPreferences,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : AccountStorage {

    override fun saveAccount(account: AntigravityAccount) {
        val serializedAccount = json.encodeToString(account)
        val serializedSync = json.encodeToString(account.workspaceSyncSettings)
        sharedPreferences.edit()
            .putString(KEY_ACCOUNT_JSON, serializedAccount)
            .putString(KEY_SELECTED_MODEL, account.selectedModel.modelId)
            .putString(KEY_WORKSPACE_SYNC_JSON, serializedSync)
            .apply()
    }

    override fun loadAccount(): AntigravityAccount? {
        val raw = sharedPreferences.getString(KEY_ACCOUNT_JSON, null) ?: return null
        return runCatching {
            json.decodeFromString<AntigravityAccount>(raw)
        }.getOrNull()
    }

    override fun clearAccount() {
        sharedPreferences.edit()
            .remove(KEY_ACCOUNT_JSON)
            .apply()
    }

    override fun saveSelectedModel(model: AiModel) {
        val editor = sharedPreferences.edit()
            .putString(KEY_SELECTED_MODEL, model.modelId)
        val existingAccount = loadAccount()
        if (existingAccount != null) {
            editor.putString(KEY_ACCOUNT_JSON, json.encodeToString(existingAccount.copy(selectedModel = model)))
        }
        editor.apply()
    }

    override fun loadSelectedModel(): AiModel? {
        val rawModel = sharedPreferences.getString(KEY_SELECTED_MODEL, null)
            ?: return loadAccount()?.selectedModel
        return AiModel.fromId(rawModel)
    }

    override fun saveWorkspaceSyncSettings(settings: WorkspaceSyncSettings) {
        val editor = sharedPreferences.edit()
            .putString(KEY_WORKSPACE_SYNC_JSON, json.encodeToString(settings))
        val existingAccount = loadAccount()
        if (existingAccount != null) {
            editor.putString(
                KEY_ACCOUNT_JSON,
                json.encodeToString(existingAccount.copy(workspaceSyncSettings = settings))
            )
        }
        editor.apply()
    }

    override fun loadWorkspaceSyncSettings(): WorkspaceSyncSettings? {
        val raw = sharedPreferences.getString(KEY_WORKSPACE_SYNC_JSON, null)
            ?: return loadAccount()?.workspaceSyncSettings
        return runCatching {
            json.decodeFromString<WorkspaceSyncSettings>(raw)
        }.getOrNull()
    }

    companion object {
        const val PREFS_NAME = "antigravity_account_session_prefs"
        const val KEY_ACCOUNT_JSON = "account_json"
        const val KEY_SELECTED_MODEL = "selected_ai_model"
        const val KEY_WORKSPACE_SYNC_JSON = "workspace_sync_settings_json"

        fun create(context: Context?): AccountStorage {
            if (context == null) return InMemoryAccountStorage()
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs != null) {
                    SharedPreferencesAccountStorage(prefs)
                } else {
                    InMemoryAccountStorage()
                }
            } catch (_: Throwable) {
                InMemoryAccountStorage()
            }
        }
    }
}
