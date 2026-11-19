package com.antigravity.android.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.android.ui.components.DirectAppInstallCard
import com.antigravity.android.ui.theme.AgentWorkingEmerald
import com.antigravity.android.ui.theme.AntigravityCyan
import com.antigravity.android.ui.theme.AntigravityCyanSurface
import com.antigravity.android.ui.theme.AntigravityViolet
import com.antigravity.android.ui.theme.AntigravityVioletBright
import com.antigravity.android.ui.theme.AntigravityVioletSurface
import com.antigravity.android.ui.theme.CinemaGradients
import com.antigravity.android.ui.theme.CinemaStatusBadge
import com.antigravity.android.ui.theme.CinemaTextStyles
import com.antigravity.android.ui.theme.GlassmorphicCard
import com.antigravity.android.ui.theme.ObsidianBorder
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianElevated
import com.antigravity.android.ui.theme.ObsidianSlate
import com.antigravity.android.ui.theme.ObsidianTerminalBg
import com.antigravity.android.ui.theme.TextMuted
import com.antigravity.android.ui.theme.TextPrimary
import com.antigravity.android.ui.theme.TextSecondary
import com.antigravity.android.ui.viewmodel.CinemaUiState
import com.antigravity.android.ui.viewmodel.ManagedPhoneApp
import com.antigravity.android.ui.viewmodel.OtaSelfUpdateUiState
import com.antigravity.android.ui.viewmodel.OtaUpdateStatus
import com.antigravity.android.ui.viewmodel.PhoneAgentToolDescriptor

@Composable
fun PhoneHubScreen(
    uiState: CinemaUiState,
    onAppSearchQueryChanged: (String) -> Unit,
    onRequestAppDiscoveryOrBuild: (String) -> Unit,
    onInstallAppClicked: (String) -> Unit,
    onLaunchInstalledApp: (String) -> Unit,
    onExecutePhoneTool: (String) -> Unit,
    onCheckForOtaUpdates: () -> Unit,
    onDownloadAndInstallOta: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianDeep),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Direct App Discovery, Builder & APK URL Stager Card
        item(key = "app_discovery_hero") {
            AppDiscoveryAndBuilderHeroCard(
                query = uiState.appSearchQuery,
                onQueryChanged = onAppSearchQueryChanged,
                onSubmitDiscovery = {
                    val target = uiState.appSearchQuery.ifBlank { "FDroid Privileged Extension" }
                    onRequestAppDiscoveryOrBuild(target)
                },
                onQuickPreset = { preset ->
                    onRequestAppDiscoveryOrBuild(preset)
                }
            )
        }

        // 2. Direct App Install Cards Queue
        item(key = "direct_install_header") {
            SectionTitleHeader(
                badge = "PACKAGEINSTALLER.SESSION • SHA-256 GUARD",
                title = "Direct Phone App Installer (${uiState.filteredInstallQueue.size})"
            )
        }

        items(
            items = uiState.filteredInstallQueue,
            key = { "hub-install-${it.id}" }
        ) { appItem ->
            DirectAppInstallCard(
                app = appItem,
                onInstallClick = onInstallAppClicked
            )
        }

        // 3. Antigravity OTA Self-Updater Card
        item(key = "ota_updater_section") {
            SectionTitleHeader(
                badge = "GITHUB RELEASES • DALI951/ANTIGRAVITY-ANDROID",
                title = "Antigravity OTA Self-Updater"
            )
            Spacer(modifier = Modifier.height(8.dp))
            OtaSelfUpdaterCard(
                otaState = uiState.otaUpdateState,
                onCheckUpdates = onCheckForOtaUpdates,
                onDownloadAndInstall = onDownloadAndInstallOta
            )
        }

        // 4. Autonomous On-Device Phone Agent Tools Hub
        item(key = "phone_tools_header") {
            SectionTitleHeader(
                badge = "AUTONOMOUS ON-DEVICE ENGINE",
                title = "Phone Agent Tool Executor"
            )
        }

        if (uiState.activeToolOutputBanner != null) {
            item(key = "active_tool_output_banner") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianTerminalBg)
                        .border(1.dp, AntigravityCyan, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = uiState.activeToolOutputBanner,
                        style = CinemaTextStyles.TerminalCommand,
                        color = AntigravityCyan
                    )
                }
            }
        }

        items(
            items = uiState.phoneTools,
            key = { "tool-${it.toolName}" }
        ) { tool ->
            PhoneAgentToolCard(
                tool = tool,
                onExecute = { onExecutePhoneTool(tool.toolName) }
            )
        }

        // 5. Installed & Managed Phone Apps Section
        item(key = "managed_apps_header") {
            SectionTitleHeader(
                badge = "ANDROID PACKAGEMANAGER",
                title = "Installed & Managed Apps (${uiState.installedApps.size})"
            )
        }

        items(
            items = uiState.installedApps,
            key = { "installed-${it.packageName}" }
        ) { installed ->
            ManagedPhoneAppRowCard(
                app = installed,
                onLaunch = { onLaunchInstalledApp(installed.packageName) }
            )
        }
    }
}

@Composable
private fun SectionTitleHeader(
    badge: String,
    title: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = badge,
            style = CinemaTextStyles.ChecksumBadge,
            color = AntigravityCyan
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
    }
}

@Composable
private fun AppDiscoveryAndBuilderHeroCard(
    query: String,
    onQueryChanged: (String) -> Unit,
    onSubmitDiscovery: () -> Unit,
    onQuickPreset: (String) -> Unit
) {
    val presets = listOf(
        "Termux Shell APK",
        "NewPipe Release",
        "Build Workspace APK",
        "F-Droid Client"
    )

    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        isHighlighted = true,
        containerColor = ObsidianSlate,
        cornerRadius = 18.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.InstallMobile,
                    contentDescription = "Direct App Installer",
                    tint = AntigravityCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ASK ANTIGRAVITY TO INSTALL ANY APP",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
            }
            CinemaStatusBadge(
                label = "FILEPROVIDER READY",
                accentColor = AgentWorkingEmerald
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Find, compile from source, or stream any Android APK from GitHub Releases, F-Droid, or a direct URL and install it directly on your phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "App name, GitHub owner/repo, or https://.../app.apk",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = AntigravityVioletBright,
                    modifier = Modifier.size(18.dp)
                )
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ObsidianDeep,
                unfocusedContainerColor = ObsidianDeep,
                focusedBorderColor = AntigravityCyan,
                unfocusedBorderColor = ObsidianBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Quick Discovery Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ObsidianElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(50))
                        .clickable { onQuickPreset(preset) }
                        .padding(horizontal = 11.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = "+ $preset",
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AntigravityVioletBright
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = onSubmitDiscovery,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = AntigravityViolet,
                contentColor = TextPrimary
            )
        ) {
            Icon(
                imageVector = Icons.Default.CloudDownload,
                contentDescription = "Find & Stage APK",
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Find, Verify SHA-256 & Stage APK",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun OtaSelfUpdaterCard(
    otaState: OtaSelfUpdateUiState,
    onCheckUpdates: () -> Unit,
    onDownloadAndInstall: () -> Unit
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        isHighlighted = otaState.status == OtaUpdateStatus.UPDATE_AVAILABLE ||
            otaState.status == OtaUpdateStatus.READY_TO_INSTALL,
        containerColor = ObsidianSlate,
        cornerRadius = 16.dp,
        contentPadding = PaddingValues(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "OTA Self-Updater",
                    tint = AntigravityCyan,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Antigravity Android v${otaState.latestVersionName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Installed: v${otaState.currentVersionName} • Repo: ${otaState.repositorySlug}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            CinemaStatusBadge(
                label = otaState.status.name,
                accentColor = if (otaState.status == OtaUpdateStatus.READY_TO_INSTALL) {
                    AgentWorkingEmerald
                } else {
                    AntigravityCyan
                },
                isPulsing = otaState.status == OtaUpdateStatus.CHECKING ||
                    otaState.status == OtaUpdateStatus.DOWNLOADING
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = otaState.releaseNotes,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(10.dp))

        // SHA-256 & Progress
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ObsidianDeep)
                .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = AgentWorkingEmerald,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "SHA-256: ${otaState.sha256.take(10)}...${otaState.sha256.takeLast(6)}",
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AgentWorkingEmerald
                    )
                }
                Text(
                    text = "${otaState.downloadProgress}%",
                    style = CinemaTextStyles.ChecksumBadge,
                    color = AntigravityCyan
                )
            }

            if (otaState.downloadProgress > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (otaState.downloadProgress.coerceIn(0, 100)) / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape),
                    color = AgentWorkingEmerald,
                    trackColor = ObsidianElevated
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = otaState.statusMessage,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCheckUpdates,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AntigravityCyan.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AntigravityCyan)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Check for Updates",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Check Releases")
            }

            Button(
                onClick = onDownloadAndInstall,
                modifier = Modifier.weight(1.3f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AgentWorkingEmerald,
                    contentColor = ObsidianDeep
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = "Download & Install OTA",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (otaState.status == OtaUpdateStatus.READY_TO_INSTALL) {
                        "Install OTA Ready ✓"
                    } else {
                        "Update Now"
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PhoneAgentToolCard(
    tool: PhoneAgentToolDescriptor,
    onExecute: () -> Unit
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = ObsidianSlate,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AntigravityVioletSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = toolIcon(tool.toolName),
                        contentDescription = tool.displayTitle,
                        tint = AntigravityCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = tool.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = tool.toolName,
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AntigravityVioletBright
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = tool.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                onClick = onExecute,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AntigravityCyanSurface,
                    contentColor = AntigravityCyan
                )
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Run tool",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Run",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }

        if (tool.lastExecutionOutput != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(ObsidianTerminalBg)
                    .border(1.dp, ObsidianBorder, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                Text(
                    text = tool.lastExecutionOutput,
                    style = CinemaTextStyles.TerminalOutput,
                    color = AgentWorkingEmerald
                )
            }
        }
    }
}

@Composable
private fun ManagedPhoneAppRowCard(
    app: ManagedPhoneApp,
    onLaunch: () -> Unit
) {
    GlassmorphicCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = ObsidianElevated,
        cornerRadius = 14.dp,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AntigravityCyanSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = app.appName,
                        tint = AntigravityCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = app.appName,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${app.packageName} • v${app.versionName}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "SHA-256 ${app.sha256Short} • ${app.lastUpdatedLabel}",
                        style = CinemaTextStyles.ChecksumBadge,
                        color = AgentWorkingEmerald
                    )
                }
            }

            OutlinedButton(
                onClick = onLaunch,
                border = BorderStroke(1.dp, AntigravityCyan.copy(alpha = 0.5f)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AntigravityCyan)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Launch,
                    contentDescription = "Launch App",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Launch",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private fun toolIcon(toolName: String): ImageVector {
    return when (toolName) {
        "install_app" -> Icons.Default.InstallMobile
        "execute_shell_command" -> Icons.Default.Terminal
        "launch_app" -> Icons.AutoMirrored.Filled.Launch
        "list_installed_apps" -> Icons.Default.CheckCircle
        "open_deeplink_or_url" -> Icons.Default.AutoAwesome
        "system_action" -> Icons.Default.SettingsSuggest
        "file_workspace_op" -> Icons.Default.FolderOpen
        else -> Icons.Default.Build
    }
}
