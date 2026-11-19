package com.antigravity.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.antigravity.android.data.network.ConnectionStatus
import com.antigravity.android.ui.screens.AccountAndModelSwitcherSheet
import com.antigravity.android.ui.screens.CommandChatScreen
import com.antigravity.android.ui.screens.PhoneHubScreen
import com.antigravity.android.ui.screens.SignInAndPairingDialog
import com.antigravity.android.ui.screens.SwarmBoardScreen
import com.antigravity.android.ui.theme.AgentWaitingAmber
import com.antigravity.android.ui.theme.AgentWorkingEmerald
import com.antigravity.android.ui.theme.AntigravityCyan
import com.antigravity.android.ui.theme.AntigravityCyanSurface
import com.antigravity.android.ui.theme.AntigravityViolet
import com.antigravity.android.ui.theme.AntigravityVioletBright
import com.antigravity.android.ui.theme.AntigravityVioletSurface
import com.antigravity.android.ui.theme.CinemaGradients
import com.antigravity.android.ui.theme.CinemaTextStyles
import com.antigravity.android.ui.theme.ObsidianBorder
import com.antigravity.android.ui.theme.ObsidianCinemaTheme
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianElevated
import com.antigravity.android.ui.theme.ObsidianSlate
import com.antigravity.android.ui.theme.PulsingAgentIndicator
import com.antigravity.android.ui.theme.TextPrimary
import com.antigravity.android.ui.theme.TextSecondary
import com.antigravity.android.ui.viewmodel.CinemaDestination
import com.antigravity.android.ui.viewmodel.CinemaUiState
import com.antigravity.android.ui.viewmodel.CinemaViewModel

@Composable
fun AntigravityCinemaApp(
    cinemaViewModel: CinemaViewModel = viewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by cinemaViewModel.uiState.collectAsState()

    ObsidianCinemaTheme {
        AntigravityCinemaRootScreen(
            uiState = uiState,
            viewModel = cinemaViewModel,
            modifier = modifier
        )
    }
}

@Composable
fun AntigravityCinemaRootScreen(
    uiState: CinemaUiState,
    viewModel: CinemaViewModel,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianDeep,
        topBar = {
            ObsidianCinemaTopBar(
                uiState = uiState,
                onOpenAccountSheet = { viewModel.setAccountSheetOpen(true) }
            )
        },
        bottomBar = {
            ObsidianCinemaBottomNav(
                currentDestination = uiState.currentDestination,
                swarmCount = uiState.swarmRoster.size,
                installQueueCount = uiState.installQueue.size,
                onSelectDestination = viewModel::selectDestination
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(ObsidianDeep)
        ) {
            when (uiState.currentDestination) {
                CinemaDestination.COMMAND_CHAT -> {
                    CommandChatScreen(
                        uiState = uiState,
                        onCommandInputChanged = viewModel::updateCommandInput,
                        onQuickPillSelected = viewModel::selectQuickActionPill,
                        onSubmitCommand = viewModel::submitCommand,
                        onToggleThinking = viewModel::toggleThinkingExpanded,
                        onApproveDiff = { editId -> viewModel.resolveFileDiffApproval(editId, true) },
                        onRejectDiff = { editId -> viewModel.resolveFileDiffApproval(editId, false) },
                        onRerunTerminalCommand = viewModel::rerunTerminalCommand,
                        onInstallAppClicked = viewModel::triggerDirectAppInstall
                    )
                }

                CinemaDestination.SWARM_BOARD -> {
                    SwarmBoardScreen(
                        uiState = uiState,
                        onSelectFilter = viewModel::selectSwarmFilter,
                        onSearchQueryChanged = viewModel::updateSwarmSearchQuery,
                        onComposerTextChanged = viewModel::updateSwarmComposerText,
                        onPostBoardMessage = viewModel::postSwarmBoardMessage,
                        onSpawnHelperAgent = viewModel::spawnHelperAgent
                    )
                }

                CinemaDestination.PHONE_HUB -> {
                    PhoneHubScreen(
                        uiState = uiState,
                        onAppSearchQueryChanged = viewModel::updateAppSearchQuery,
                        onRequestAppDiscoveryOrBuild = viewModel::requestAgentAppDiscoveryOrBuild,
                        onInstallAppClicked = viewModel::triggerDirectAppInstall,
                        onLaunchInstalledApp = viewModel::launchInstalledApp,
                        onExecutePhoneTool = viewModel::executePhoneAgentTool,
                        onCheckForOtaUpdates = viewModel::checkForOtaUpdates,
                        onDownloadAndInstallOta = viewModel::downloadAndInstallOtaUpdate
                    )
                }
            }
        }

        if (uiState.isAccountSheetOpen) {
            AccountAndModelSwitcherSheet(
                accountState = uiState.accountState,
                onDismiss = { viewModel.setAccountSheetOpen(false) },
                onSelectModel = viewModel::selectAiModel,
                onOpenSignInAndPairingDialog = {
                    viewModel.setAccountSheetOpen(false)
                    viewModel.setSignInDialogVisible(true)
                },
                onSignOut = viewModel::signOutAccount,
                onToggleWorkspaceSync = viewModel::toggleWorkspaceSync
            )
        }

        if (uiState.accountState.isSignInDialogVisible) {
            SignInAndPairingDialog(
                accountState = uiState.accountState,
                onDismiss = { viewModel.setSignInDialogVisible(false) },
                onSignInSubmit = viewModel::signInWithAccount,
                onPairPcSubmit = viewModel::pairWithPcOrchestrator
            )
        }
    }
}

@Composable
private fun ObsidianCinemaTopBar(
    uiState: CinemaUiState,
    onOpenAccountSheet: () -> Unit
) {
    val isConnected = uiState.connectionStatus is ConnectionStatus.Connected ||
        uiState.accountState.pcPairingConnected

    Surface(
        color = ObsidianDeep,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CinemaGradients.ObsidianHeaderBackdrop)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Brand Logo & Connection Pulse
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CinemaGradients.CyanToViolet),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "Antigravity Logo",
                        tint = ObsidianDeep,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "ANTIGRAVITY",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        PulsingAgentIndicator(
                            color = if (isConnected) AgentWorkingEmerald else AgentWaitingAmber,
                            isPulsing = isConnected,
                            dotSize = 7.dp
                        )
                    }
                    Text(
                        text = if (isConnected) {
                            "PHONE AGENT • PC PAIRED"
                        } else {
                            "ON-DEVICE AUTONOMOUS MODE"
                        },
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AntigravityCyan
                    )
                }
            }

            // Right: Account & Active AI Model Switcher Trigger Pill
            val shape = RoundedCornerShape(50)
            Row(
                modifier = Modifier
                    .clip(shape)
                    .background(ObsidianElevated)
                    .border(BorderStroke(1.dp, AntigravityViolet.copy(alpha = 0.7f)), shape)
                    .clickable { onOpenAccountSheet() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.AccountCircle,
                    contentDescription = "Account & Model Switcher",
                    tint = AntigravityCyan,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = if (uiState.accountState.isSignedIn) {
                            uiState.accountState.displayName
                        } else {
                            "Sign In"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.accountState.activeModel.displayName,
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AntigravityVioletBright,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ObsidianCinemaBottomNav(
    currentDestination: CinemaDestination,
    swarmCount: Int,
    installQueueCount: Int,
    onSelectDestination: (CinemaDestination) -> Unit
) {
    NavigationBar(
        containerColor = ObsidianSlate,
        contentColor = TextPrimary,
        tonalElevation = 8.dp,
        modifier = Modifier.border(1.dp, ObsidianBorder)
    ) {
        NavigationBarItem(
            selected = currentDestination == CinemaDestination.COMMAND_CHAT,
            onClick = { onSelectDestination(CinemaDestination.COMMAND_CHAT) },
            icon = {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Unified Command & Chat"
                )
            },
            label = {
                Text(
                    text = "Command",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AntigravityCyan,
                selectedTextColor = AntigravityCyan,
                indicatorColor = AntigravityCyanSurface,
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )

        NavigationBarItem(
            selected = currentDestination == CinemaDestination.SWARM_BOARD,
            onClick = { onSelectDestination(CinemaDestination.SWARM_BOARD) },
            icon = {
                BadgedBox(
                    badge = {
                        if (swarmCount > 0) {
                            Badge(
                                containerColor = AntigravityViolet,
                                contentColor = TextPrimary
                            ) {
                                Text(swarmCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Groups,
                        contentDescription = "Swarm Live Board"
                    )
                }
            },
            label = {
                Text(
                    text = "Swarm",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AntigravityVioletBright,
                selectedTextColor = AntigravityVioletBright,
                indicatorColor = AntigravityVioletSurface,
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )

        NavigationBarItem(
            selected = currentDestination == CinemaDestination.PHONE_HUB,
            onClick = { onSelectDestination(CinemaDestination.PHONE_HUB) },
            icon = {
                BadgedBox(
                    badge = {
                        if (installQueueCount > 0) {
                            Badge(
                                containerColor = AgentWorkingEmerald,
                                contentColor = ObsidianDeep
                            ) {
                                Text(installQueueCount.toString())
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.InstallMobile,
                        contentDescription = "Phone App Installer & Device Hub"
                    )
                }
            },
            label = {
                Text(
                    text = "Phone Hub",
                    style = MaterialTheme.typography.labelMedium
                )
            },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AgentWorkingEmerald,
                selectedTextColor = AgentWorkingEmerald,
                indicatorColor = AgentWorkingEmerald.copy(alpha = 0.18f),
                unselectedIconColor = TextSecondary,
                unselectedTextColor = TextSecondary
            )
        )
    }
}
