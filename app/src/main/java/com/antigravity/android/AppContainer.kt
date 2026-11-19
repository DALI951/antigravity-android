package com.antigravity.android

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.antigravity.android.agent.ApkDownloadService
import com.antigravity.android.agent.AppInstallerManager
import com.antigravity.android.agent.PhoneAgentToolExecutor
import com.antigravity.android.auth.AccountRepository
import com.antigravity.android.auth.AccountSessionManager
import com.antigravity.android.data.network.ConnectWebSocketClient
import com.antigravity.android.data.network.OrchestratorService
import com.antigravity.android.data.network.OrchestratorServiceImpl
import com.antigravity.android.domain.OrchestratorRepository
import com.antigravity.android.domain.OrchestratorRepositoryImpl
import com.antigravity.android.ui.viewmodel.CinemaViewModel
import com.antigravity.android.updater.UpdateChecker
import com.antigravity.android.updater.UpdateRepository
import java.io.File

/**
 * Top-level Dependency Injection container for Antigravity for Android.
 * Wires Account/Auth, Connect-RPC Orchestrator, Autonomous On-Device Phone Agent,
 * Direct APK Installer, and Self-Updater into a cohesive runtime graph.
 */
class AppContainer(context: Context? = null) {

    private val appContext: Context? = context?.applicationContext ?: context

    val webSocketClient: ConnectWebSocketClient = ConnectWebSocketClient()

    val orchestratorService: OrchestratorService = OrchestratorServiceImpl(
        client = webSocketClient
    )

    val orchestratorRepository: OrchestratorRepository = OrchestratorRepositoryImpl(
        service = orchestratorService,
        client = webSocketClient
    )

    val accountRepository: AccountRepository = AccountSessionManager(
        context = appContext,
        orchestratorRepository = orchestratorRepository,
        webSocketClient = webSocketClient
    )

    val apkDownloadService: ApkDownloadService = ApkDownloadService(
        defaultTargetDir = appContext?.cacheDir?.let { File(it, "updates") }
            ?: File(System.getProperty("java.io.tmpdir"), "antigravity_updates")
    )

    val appInstallerManager: AppInstallerManager = AppInstallerManager(
        context = appContext,
        downloadService = apkDownloadService
    )

    val updateChecker: UpdateChecker = UpdateChecker()

    val updateRepository: UpdateRepository = UpdateRepository(
        context = appContext,
        updateChecker = updateChecker,
        appInstallerManager = appInstallerManager
    )

    val phoneAgentToolExecutor: PhoneAgentToolExecutor = PhoneAgentToolExecutor(
        context = appContext,
        appInstallerManager = appInstallerManager,
        updateRepository = updateRepository,
        workspaceDir = appContext?.filesDir?.let { File(it, "workspace") }
            ?: File(System.getProperty("java.io.tmpdir"), "antigravity_workspace")
    )

    init {
        orchestratorRepository.setAuthHeaderProvider {
            accountRepository.getAuthHeaders()
        }
    }

    fun createCinemaViewModel(): CinemaViewModel {
        return CinemaViewModel(
            orchestratorRepository = orchestratorRepository,
            accountRepository = accountRepository,
            appInstallerManager = appInstallerManager,
            phoneAgentToolExecutor = phoneAgentToolExecutor,
            updateRepository = updateRepository
        )
    }

    fun provideCinemaViewModelFactory(): ViewModelProvider.Factory {
        return object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(CinemaViewModel::class.java)) {
                    return createCinemaViewModel() as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
