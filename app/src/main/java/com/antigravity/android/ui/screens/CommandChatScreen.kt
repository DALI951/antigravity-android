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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.antigravity.android.ui.components.DirectAppInstallCard
import com.antigravity.android.ui.components.FileDiffApprovalCard
import com.antigravity.android.ui.components.PlannerResponseCard
import com.antigravity.android.ui.components.TerminalCommandCard
import com.antigravity.android.ui.components.UserCommandCard
import com.antigravity.android.ui.theme.AgentWorkingEmerald
import com.antigravity.android.ui.theme.AntigravityCyan
import com.antigravity.android.ui.theme.AntigravityCyanSurface
import com.antigravity.android.ui.theme.AntigravityViolet
import com.antigravity.android.ui.theme.AntigravityVioletBright
import com.antigravity.android.ui.theme.AntigravityVioletSurface
import com.antigravity.android.ui.theme.CinemaGradients
import com.antigravity.android.ui.theme.CinemaStatusBadge
import com.antigravity.android.ui.theme.CinemaTextStyles
import com.antigravity.android.ui.theme.ObsidianBorder
import com.antigravity.android.ui.theme.ObsidianDeep
import com.antigravity.android.ui.theme.ObsidianElevated
import com.antigravity.android.ui.theme.ObsidianSlate
import com.antigravity.android.ui.theme.PulsingAgentIndicator
import com.antigravity.android.ui.theme.TextMuted
import com.antigravity.android.ui.theme.TextPrimary
import com.antigravity.android.ui.theme.TextSecondary
import com.antigravity.android.ui.viewmodel.CinemaUiState
import com.antigravity.android.ui.viewmodel.QuickActionPill
import com.antigravity.android.ui.viewmodel.TrajectoryItemKind

@Composable
fun CommandChatScreen(
    uiState: CinemaUiState,
    onCommandInputChanged: (String) -> Unit,
    onQuickPillSelected: (QuickActionPill) -> Unit,
    onSubmitCommand: (String?) -> Unit,
    onToggleThinking: (String) -> Unit,
    onApproveDiff: (String) -> Unit,
    onRejectDiff: (String) -> Unit,
    onRerunTerminalCommand: (String) -> Unit,
    onInstallAppClicked: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ObsidianDeep)
    ) {
        // Active Session & Cascade Trajectory Status Strip
        SessionTrajectoryStatusHeader(
            sessionTitle = uiState.sessionTitle,
            stepCount = uiState.trajectoryItems.size,
            activeModelName = uiState.accountState.activeModel.displayName,
            activeSwarmCount = uiState.swarmRoster.count { it.state.wireName == "working" || it.state.wireName == "coordinator" }
        )

        // Live Trajectory Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(
                items = uiState.trajectoryItems,
                key = { it.id }
            ) { item ->
                when (item.kind) {
                    TrajectoryItemKind.USER_COMMAND -> {
                        UserCommandCard(item = item)
                    }

                    TrajectoryItemKind.PLANNER_RESPONSE -> {
                        PlannerResponseCard(
                            item = item,
                            onToggleThinking = onToggleThinking
                        )
                    }

                    TrajectoryItemKind.TERMINAL_COMMAND -> {
                        item.terminalCommand?.let { terminal ->
                            TerminalCommandCard(
                                itemId = item.id,
                                terminal = terminal,
                                onRerunCommand = onRerunTerminalCommand
                            )
                        }
                    }

                    TrajectoryItemKind.FILE_DIFF_APPROVAL -> {
                        item.fileDiffApproval?.let { approval ->
                            FileDiffApprovalCard(
                                approval = approval,
                                onApprove = onApproveDiff,
                                onReject = onRejectDiff
                            )
                        }
                    }

                    TrajectoryItemKind.DIRECT_APP_INSTALL -> {
                        item.appInstallCard?.let { appCard ->
                            DirectAppInstallCard(
                                app = appCard,
                                onInstallClick = onInstallAppClicked
                            )
                        }
                    }

                    TrajectoryItemKind.SYSTEM_EVENT -> {
                        PlannerResponseCard(
                            item = item,
                            onToggleThinking = onToggleThinking
                        )
                    }
                }
            }
        }

        // Bottom Docked Unified Command Bar with Quick-Action Pills
        UnifiedCommandDock(
            commandInput = uiState.commandInput,
            selectedPill = uiState.selectedQuickPill,
            onInputChanged = onCommandInputChanged,
            onQuickPillClicked = onQuickPillSelected,
            onSendClicked = { onSubmitCommand(null) }
        )
    }
}

@Composable
private fun SessionTrajectoryStatusHeader(
    sessionTitle: String,
    stepCount: Int,
    activeModelName: String,
    activeSwarmCount: Int
) {
    Surface(
        color = ObsidianSlate,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                PulsingAgentIndicator(
                    color = AgentWorkingEmerald,
                    isPulsing = true,
                    dotSize = 8.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = sessionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "LIVE TRAJECTORY • $stepCount STEPS • $activeSwarmCount AGENTS ACTIVE",
                        style = CinemaTextStyles.ChecksumBadge,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            CinemaStatusBadge(
                label = activeModelName,
                accentColor = AntigravityCyan,
                isPulsing = false
            )
        }
    }
}

@Composable
private fun UnifiedCommandDock(
    commandInput: String,
    selectedPill: QuickActionPill?,
    onInputChanged: (String) -> Unit,
    onQuickPillClicked: (QuickActionPill) -> Unit,
    onSendClicked: () -> Unit
) {
    Surface(
        color = ObsidianSlate,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // Horizontal Quick-Action Pills Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                QuickActionPill.entries.forEach { pill ->
                    val isSelected = pill == selectedPill
                    val icon = pillIcon(pill)
                    val shape = RoundedCornerShape(50)

                    Row(
                        modifier = Modifier
                            .clip(shape)
                            .background(
                                if (isSelected) AntigravityVioletSurface else ObsidianElevated
                            )
                            .border(
                                BorderStroke(
                                    1.dp,
                                    if (isSelected) AntigravityCyan else ObsidianBorder
                                ),
                                shape
                            )
                            .clickable { onQuickPillClicked(pill) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = pill.label,
                            tint = if (isSelected) AntigravityCyan else AntigravityVioletBright,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = pill.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) AntigravityCyan else TextPrimary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // "Tell it to do anything" Natural Language Command Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commandInput,
                    onValueChange = onInputChanged,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Tell Antigravity to do anything on your phone...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Antigravity Command",
                            tint = AntigravityCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = false,
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = ObsidianDeep,
                        unfocusedContainerColor = ObsidianDeep,
                        focusedBorderColor = AntigravityCyan,
                        unfocusedBorderColor = ObsidianBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AntigravityCyan
                    )
                )

                Spacer(modifier = Modifier.width(10.dp))

                // Glowing Send Button
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (commandInput.isNotBlank()) {
                                CinemaGradients.CyanToViolet
                            } else {
                                CinemaGradients.GlassCardBorder
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(onClick = onSendClicked) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Execute Directive",
                            tint = TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun pillIcon(pill: QuickActionPill): ImageVector {
    return when (pill) {
        QuickActionPill.INSTALL_APP -> Icons.Default.InstallMobile
        QuickActionPill.BUILD_AND_INSTALL_APK -> Icons.Default.Build
        QuickActionPill.AUTOMATE_PHONE_TASK -> Icons.Default.PhoneAndroid
        QuickActionPill.LAUNCH_SWARM -> Icons.Default.Groups
    }
}
